package io.github.gcbond.openhc.platform.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.net.EthernetEndpoint
import io.github.gcbond.openhc.platform.net.NcmDataPath
import io.github.gcbond.openhc.platform.net.TunSpec
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.platform.net.VpnTunnel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次有线（USB 数据线）连接，从等手机插上到链路断开：
 * ```
 * 等手机 → USB 授权 → 发 carInfo → 等手机切成 USB 网卡 → 再次 USB 授权（同时建 TUN）
 *       → 抢占网卡接口 → onNetworkReady（调用方 bind 监听）→ 开始收发 → … → Ended
 * ```
 * 每个等待阶段都有上限（[WiredTimeouts]），任何时候都可以 [stop]。会话结束后不会自己重来：
 * 手机每次插入只响应一次 carInfo，再连要拔线重插（见 [WiredState.Ended.replugRequired]），由应用层决定何时开新会话。
 *
 * 线程：所有状态变化、广播、超时都在会话自己的工作线程上串行处理，回调也在这个线程上调用。
 * 同一进程里同时只能有一个会话（共用一个 VpnService）；前一个进入 [WiredState.Ended] 之后才能开下一个。
 *
 * 开始之前应用层要准备好：VPN 授权（[VpnConsent]）；清单里的 VpnService 由本模块的清单声明，合并后自动带上。
 */
class WiredSession(
    context: Context,
    private val config: WiredSessionConfig,
    private val callback: Callback,
) {

    interface Callback {
        /** 状态变化。在会话工作线程上依次调用，不要做耗时操作；更新界面请自己切到主线程。 */
        fun onStateChanged(state: WiredState)

        /**
         * USB 网卡和 TUN 都就绪、开始收发之前，在会话工作线程上同步调用一次。
         *
         * 在这里 bind 监听（[WiredNetwork.bindServerSocket]，端口与 carInfo 里的一致），返回之后才开始收发：
         * 手机收到 ARP 应答后几毫秒就发起 TCP 连接，那时还没人监听的话内核会直接回绝（实测）。
         * 如果开会话前已经在通配地址上 bind 好了监听，这里什么都不用做。
         * 可以做阻塞操作，但手机切成 USB 网卡后约 19 秒内没人连上就会放弃，别耽搁太久。
         * 抛异常会让会话以 [WiredEndReason.ERROR] 结束，这次插入的 carInfo 也就用掉了。
         */
        fun onNetworkReady(network: WiredNetwork) {}
    }

    private enum class Phase { IDLE, WAIT_PHONE, PERMISSION_PHONE, WAIT_NCM, PERMISSION_NCM, CONNECTED, ENDED }

    private val app: Context = context.applicationContext ?: context
    private val permissionAction = app.packageName + ".openhc.WIRED_USB_PERMISSION"
    private val started = AtomicBoolean(false)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var vpnRevoked = false

    @Volatile
    private var handler: Handler? = null

    /** 当前状态（任意线程可读）。 */
    @Volatile
    var state: WiredState = WiredState.Idle
        private set

    // ---------------------------------------------------------------- 以下只在工作线程上访问

    private var thread: HandlerThread? = null
    private var usbManager: UsbManager? = null
    private var phase = Phase.IDLE

    /** 每次换阶段加一；延迟任务到点时核对，阶段变过就作废。 */
    private var phaseToken = 0

    /** 当前关注的设备（设备节点路径，每次插入、每次重新枚举都会变）。 */
    private var target: String? = null
    private var onGranted: ((UsbDevice) -> Unit)? = null
    private var carInfoSent = false

    /** 发 carInfo 时已经存在的 USB 网卡设备，不是这次切过来的，不去碰。 */
    private var staleNcm: Set<String> = emptySet()
    private var receiverRegistered = false
    private var vpnTunnel: VpnTunnel? = null
    private var usbLink: NcmUsbLink? = null
    private var dataPath: NcmDataPath? = null

    /** 开始会话，立即返回。只能调一次。 */
    fun start() {
        check(started.compareAndSet(false, true)) { "会话只能启动一次" }
        val worker = HandlerThread("OpenHC-Wired").also { it.start() }
        thread = worker
        val h = Handler(worker.looper)
        handler = h
        h.post { begin() }
    }

    /** 结束会话，立即返回；收尾完成后状态变为 [WiredState.Ended]。可在任意线程、重复调用。 */
    fun stop() {
        stopRequested = true
        handler?.post { end(WiredEndReason.STOPPED) }
    }

    // ---------------------------------------------------------------- 阶段推进

    private fun begin() {
        if (!ACTIVE.compareAndSet(null, this)) return end(WiredEndReason.BUSY)
        if (stopRequested) return end(WiredEndReason.STOPPED)
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return end(WiredEndReason.USB_HOST_UNSUPPORTED)
        usbManager = usb
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            log("系统没有声明支持 USB 主机模式，照常尝试")
        }
        // 先确认 VPN 授权再发 carInfo：手机每次插入只响应一次 carInfo，不能在建不了 TUN 的情况下把它用掉。
        when (val consent = VpnConsent.check(app)) {
            VpnConsent.Status.Granted -> Unit
            is VpnConsent.Status.NeedsUserConsent -> return end(WiredEndReason.VPN_CONSENT_REQUIRED)
            is VpnConsent.Status.Unavailable -> return end(WiredEndReason.VPN_UNAVAILABLE, consent.reason)
        }
        registerReceiver()
        enterPhase(Phase.WAIT_PHONE)
        val phoneWaitMs = config.timeouts.phoneWaitMs
        if (phoneWaitMs > 0) {
            handler?.postDelayed({
                if (!carInfoSent && (phase == Phase.WAIT_PHONE || phase == Phase.PERMISSION_PHONE)) {
                    end(WiredEndReason.PHONE_NOT_FOUND)
                }
            }, phoneWaitMs)
        }
        scanDevices()
        handler?.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /**
     * 按当前设备列表推进。插拔广播之外每秒也查一次：有的车机系统不发插入广播，或者广播在注册之前就发过了。
     */
    private fun scanDevices() {
        val usb = usbManager ?: return
        val devices = try {
            usb.deviceList.values.toList()
        } catch (e: RuntimeException) {
            return
        }
        when (phase) {
            Phase.WAIT_PHONE -> {
                val normal = devices.firstOrNull { UsbDescriber.phoneMode(it) == PhoneUsbMode.NORMAL }
                when {
                    normal != null -> beginPhonePermission(normal)
                    devices.any { UsbDescriber.phoneMode(it) == PhoneUsbMode.USB_DEBUGGING } ->
                        setState(WiredState.UsbDebuggingOn)
                    else -> setState(WiredState.WaitingForPhone)
                }
            }
            Phase.PERMISSION_PHONE ->
                if (devices.none { it.deviceName == target }) backToWaitPhone("授权前手机被拔掉")
            Phase.WAIT_NCM -> devices.firstOrNull {
                it.deviceName !in staleNcm && UsbDescriber.phoneMode(it) == PhoneUsbMode.NCM
            }?.let { onNcmDevice(it) }
            Phase.PERMISSION_NCM, Phase.CONNECTED ->
                if (devices.none { it.deviceName == target }) end(WiredEndReason.PHONE_UNPLUGGED)
            Phase.IDLE, Phase.ENDED -> Unit
        }
    }

    private fun beginPhonePermission(device: UsbDevice) {
        target = device.deviceName
        enterPhase(Phase.PERMISSION_PHONE)
        log("发现手机 ${UsbDescriber.summary(device)} ${device.deviceName}")
        awaitPermission(device, UsbStage.PHONE, config.timeouts.phonePermissionMs) { sendCarInfo(it) }
    }

    private fun backToWaitPhone(reason: String) {
        log(reason)
        target = null
        onGranted = null
        enterPhase(Phase.WAIT_PHONE)
        setState(WiredState.WaitingForPhone)
        scanDevices()
    }

    private fun sendCarInfo(device: UsbDevice) {
        val usb = usbManager ?: return
        setState(WiredState.SendingCarInfo)
        staleNcm = ncmDeviceNames(usb)
        val expected = config.carInfo.size
        var result = 0
        for (attempt in 1..CAR_INFO_ATTEMPTS) {
            if (stopRequested) return end(WiredEndReason.STOPPED)
            if (!isPresent(usb, device)) return backToWaitPhone("发 carInfo 前手机被拔掉")
            result = CarInfoSender.send(usb, device, config.carInfoRequest, config.carInfo)
            if (result == expected) break
            // 发送失败（手机还没准备好等）不算用掉了这次插入，可以重发。
            log("carInfo 第 $attempt 次发送失败（返回 $result）")
            if (attempt < CAR_INFO_ATTEMPTS) SystemClock.sleep(CAR_INFO_RETRY_DELAY_MS)
        }
        if (result != expected) return end(WiredEndReason.CAR_INFO_FAILED, "控制传输返回 $result，应为 $expected")
        carInfoSent = true
        log("carInfo 已发出（$result 字节），等手机切成 USB 网卡")
        enterPhase(Phase.WAIT_NCM)
        setState(WiredState.WaitingForPhoneSwitch(slow = false))
        timeout(config.timeouts.phoneSwitchHintMs) { setState(WiredState.WaitingForPhoneSwitch(slow = true)) }
        timeout(config.timeouts.phoneSwitchMs) { end(WiredEndReason.PHONE_DID_NOT_SWITCH) }
        scanDevices()
    }

    private fun onNcmDevice(device: UsbDevice) {
        target = device.deviceName
        enterPhase(Phase.PERMISSION_NCM)
        log("手机已切成 USB 网卡 ${UsbDescriber.summary(device)} ${device.deviceName}")
        setState(WiredState.OpeningNetwork)
        awaitPermission(device, UsbStage.USB_NETWORK, config.timeouts.usbNetworkPermissionMs) { openNetwork(it) }
        // 趁用户看授权框（或系统自动授权）的工夫先把 TUN 建好；授权结果排在这之后处理。
        ensureTunnel()
    }

    private fun ensureTunnel(): Boolean {
        if (vpnTunnel != null) return true
        val spec = TunSpec(
            address = config.carAddress,
            prefixLength = config.prefixLength,
            network = config.networkAddress,
            mtu = TUN_MTU,
            sessionName = config.vpnSessionName,
            configureIntent = config.vpnConfigureIntent,
        )
        return try {
            vpnTunnel = VpnTunnel.open(app, spec, config.timeouts.vpnEstablishMs, { stopRequested }) {
                // 在主线程上被调用。先置标志：随后数据面会读到 TUN 关闭，收尾时据此报「授权被撤销」。
                vpnRevoked = true
                handler?.post { end(WiredEndReason.VPN_REVOKED) }
            }
            log("TUN 已建好：${config.carAddress.hostAddress}/${config.prefixLength}，只路由这个网段，只对本应用生效")
            true
        } catch (e: IOException) {
            end(if (stopRequested) WiredEndReason.STOPPED else WiredEndReason.VPN_FAILED, e.message)
            false
        }
    }

    private fun openNetwork(device: UsbDevice) {
        val usb = usbManager ?: return
        val tunnel = vpnTunnel ?: return
        setState(WiredState.OpeningNetwork)
        val link = try {
            NcmUsbLink.open(usb, device, Build.VERSION.SDK_INT) { log(it) }
        } catch (e: IOException) {
            return end(WiredEndReason.USB_NETWORK_UNUSABLE, e.message)
        }
        usbLink = link
        val endpoint = EthernetEndpoint(config.carAddress.address, config.carMac)
        val path = NcmDataPath(link, tunnel.tunnel, endpoint, dataPathListener)
        dataPath = path
        val network = WiredNetwork(config.carAddress, config.prefixLength, { endpoint.phoneAddress }, { path.stats() })
        try {
            callback.onNetworkReady(network)
        } catch (e: Exception) {
            return end(WiredEndReason.ERROR, "onNetworkReady 出错: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (stopRequested) return end(WiredEndReason.STOPPED)
        path.start()
        enterPhase(Phase.CONNECTED)
        log("开始收发")
        setState(WiredState.Connected(network))
        scheduleStatsLog()
    }

    // ---------------------------------------------------------------- USB 授权

    /**
     * 拿到 [device] 的 USB 授权后调用 [granted]（总是经工作线程排队调用，不会在本方法里直接调）。
     * 没有授权时先等 [WiredTimeouts.permissionGraceMs] 看系统会不会自动授权，再弹授权框。
     */
    private fun awaitPermission(device: UsbDevice, stage: UsbStage, timeoutMs: Long, granted: (UsbDevice) -> Unit) {
        val usb = usbManager ?: return
        onGranted = granted
        val token = phaseToken
        val graceUntil = SystemClock.uptimeMillis() + config.timeouts.permissionGraceMs
        val check = object : Runnable {
            override fun run() {
                if (phaseToken != token) return
                if (hasPermission(usb, device)) return permissionGranted(device)
                if (SystemClock.uptimeMillis() < graceUntil) {
                    handler?.postDelayed(this, GRACE_POLL_MS)
                    return
                }
                setState(WiredState.WaitingForUsbPermission(stage))
                log("请求 USB 授权（$stage）")
                try {
                    usb.requestPermission(device, UsbCompat.permissionIntent(app, permissionAction))
                } catch (e: RuntimeException) {
                    end(WiredEndReason.ERROR, "请求 USB 授权失败: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        handler?.post(check)
        timeout(timeoutMs) { end(WiredEndReason.USB_PERMISSION_TIMEOUT) }
    }

    private fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        if (onGranted == null || device.deviceName != target) return
        val usb = usbManager ?: return
        // 以系统记录的授权为准：安卓 13 以前动态注册的接收器对别的应用开放，广播里的「已授权」可能是伪造的。
        when {
            hasPermission(usb, device) -> permissionGranted(device)
            !granted -> end(WiredEndReason.USB_PERMISSION_DENIED)
        }
    }

    private fun permissionGranted(device: UsbDevice) {
        val next = onGranted ?: return
        onGranted = null
        next(device)
    }

    // ---------------------------------------------------------------- 事件

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 注册时指定了工作线程的 Handler，这里就在工作线程上。
            if (phase == Phase.ENDED) return
            val device = UsbCompat.deviceExtra(intent) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                permissionAction ->
                    onPermissionResult(device, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
    }

    private fun onDetached(device: UsbDevice) {
        if (device.deviceName != target) {
            if (phase == Phase.WAIT_PHONE) scanDevices()
            return
        }
        when (phase) {
            Phase.PERMISSION_PHONE -> backToWaitPhone("授权前手机被拔掉")
            // 手机切成 USB 网卡时，普通状态的设备会先断开、约 1.5 秒后网卡设备出现（实测），这是预期的。
            Phase.WAIT_NCM -> log("普通状态的手机已断开，等 USB 网卡出现")
            Phase.PERMISSION_NCM, Phase.CONNECTED -> end(WiredEndReason.PHONE_UNPLUGGED)
            Phase.IDLE, Phase.WAIT_PHONE, Phase.ENDED -> Unit
        }
    }

    private val dataPathListener = object : NcmDataPath.Listener {
        override fun onLinkDown(reason: NcmDataPath.LinkDown) {
            // 在搬运线程上被调用，转到工作线程收尾（收尾要等搬运线程退出）。
            handler?.post {
                if (phase != Phase.CONNECTED) return@post
                when (reason) {
                    NcmDataPath.LinkDown.USB_LINK_LOST -> end(WiredEndReason.LINK_LOST)
                    NcmDataPath.LinkDown.TUNNEL_CLOSED ->
                        end(if (vpnRevoked) WiredEndReason.VPN_REVOKED else WiredEndReason.TUNNEL_CLOSED)
                    NcmDataPath.LinkDown.INTERNAL_ERROR -> end(WiredEndReason.ERROR, "搬运线程出错")
                }
            }
        }

        override fun onMilestone(milestone: NcmDataPath.Milestone, detail: String) {
            log("数据面 $milestone $detail")
        }
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (phase == Phase.ENDED) return
            scanDevices()
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- 收尾

    private fun end(reason: WiredEndReason, detail: String? = null) {
        if (phase == Phase.ENDED) return
        enterPhase(Phase.ENDED)
        onGranted = null
        handler?.removeCallbacksAndMessages(null)
        unregisterReceiver()
        // 顺序要紧：先等搬运线程退出，再关 USB 连接和 TUN，最后解除 VPN 服务绑定。
        dataPath?.let { if (!it.stop()) log("搬运线程没有按时退出") }
        dataPath = null
        usbLink?.close()
        usbLink = null
        vpnTunnel?.close()
        vpnTunnel = null
        ACTIVE.compareAndSet(this, null)
        log("会话结束：$reason${detail?.let { "（$it）" } ?: ""}")
        setState(WiredState.Ended(reason, replugRequired = carInfoSent, detail = detail))
        thread?.quitSafely()
    }

    // ---------------------------------------------------------------- 工具

    private fun enterPhase(next: Phase) {
        phase = next
        phaseToken++
    }

    /** 当前阶段内的延迟任务；阶段变了就不执行。 */
    private fun timeout(delayMs: Long, action: () -> Unit) {
        val token = phaseToken
        handler?.postDelayed({ if (phaseToken == token && phase != Phase.ENDED) action() }, delayMs)
    }

    private fun setState(next: WiredState) {
        if (next == state) return
        state = next
        Log.i(TAG, "状态 $next")
        try {
            callback.onStateChanged(next)
        } catch (e: RuntimeException) {
            Log.e(TAG, "onStateChanged 出错", e)
        }
    }

    private fun scheduleStatsLog() {
        val token = phaseToken
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (phaseToken != token) return
                dataPath?.let { Log.i(TAG, "流量 ${it.stats()}") }
                handler?.postDelayed(this, STATS_INTERVAL_MS)
            }
        }, STATS_INTERVAL_MS)
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        val h = handler ?: return
        UsbCompat.register(app, receiver, filter, h)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            app.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // 已经注销过。
        }
    }

    private fun hasPermission(usb: UsbManager, device: UsbDevice): Boolean = try {
        usb.hasPermission(device)
    } catch (e: RuntimeException) {
        false
    }

    private fun isPresent(usb: UsbManager, device: UsbDevice): Boolean = try {
        usb.deviceList.containsKey(device.deviceName)
    } catch (e: RuntimeException) {
        false
    }

    private fun ncmDeviceNames(usb: UsbManager): Set<String> = try {
        usb.deviceList.values.filter { UsbDescriber.phoneMode(it) == PhoneUsbMode.NCM }.map { it.deviceName }.toSet()
    } catch (e: RuntimeException) {
        emptySet()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "OpenHC-Wired"
        const val POLL_INTERVAL_MS = 1000L
        const val GRACE_POLL_MS = 50L
        const val CAR_INFO_ATTEMPTS = 3
        const val CAR_INFO_RETRY_DELAY_MS = 300L
        const val STATS_INTERVAL_MS = 10_000L
        const val TUN_MTU = 1500

        /** 同一进程里正在进行的会话。 */
        val ACTIVE = AtomicReference<WiredSession?>(null)
    }
}
