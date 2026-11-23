package io.github.gcbond.openhc.platform.wifi

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import kotlin.random.asKotlinRandom

/** 建好的直连组，全部原样写进给手机的连接串。toString 不含组名和口令。 */
class DirectGroup(
    /** 组主在直连网卡上的 IPv4（一般是 192.168.49.1），手机连它。 */
    val localIp: String,
    /** 组名（`DIRECT-` 开头）。 */
    val networkName: String,
    /** 组主 MAC，写进连接串 BSSID 的位置。 */
    val ownerMac: String,
    /** WPA2-PSK 口令。 */
    val passphrase: String,
    /** 组实际的频率（系统交回的真实值）。 */
    val frequencyMhz: Int,
    val interfaceName: String,
    val macSource: DirectMacSource,
    /** 按哪个频率申请的；null 表示没指定、由系统定。 */
    val requestedMhz: Int?,
) {
    override fun toString() =
        "DirectGroup($localIp，$frequencyMhz MHz（申请 ${requestedMhz ?: "由系统定"}），网卡 $interfaceName，" +
            "组主 MAC ${MacText.mask(ownerMac)}（${macSource.label}），组名与口令不显示)"
}

/** 直连组的事件，都在 [WifiDirectGroupOwner] 的工作线程上回调。 */
interface DirectGroupEvents {
    /** 组建好了，[group] 是实际信息。 */
    fun onReady(group: DirectGroup)

    /** 没建成。 */
    fun onFailed(message: String)

    /** 建好以后组没了。 */
    fun onLost(message: String)
}

/**
 * 车机建 WiFi 直连组、自己当组主（安卓 10 起才能指定组名、口令和频率）。用法同 [LocalHotspotHandle]：[start] 开始建，
 * [stop] 拆掉；每次连接新建一个，用完即弃。手机作为组成员加入，车机自己连着的 WiFi 不断。
 *
 * - 建之前先拆掉残留的组（进程被杀后系统可能还留着上次的组），并确认拆干净；
 * - 按 [DirectGroupRules.plan] 依次试频率：系统拒绝、或申请后 [GROUP_WAIT_MS] 内组没建好，就拆掉换下一个；
 * - 建好后从系统读回组名、口令、实际频率、网卡名，再读网卡上的 IPv4 和硬件地址（组主 MAC）；
 * - 组在期间按住车机的蜂窝网络请求：有自带 4G 的车机，手机关热点时车机的默认网络会切到 4G，之后 4G 退到后台，
 *   30 秒后系统会清掉当时建的连接，直连上的投屏连接也在内；按住 4G 就不会退到后台（HC 助手在比亚迪车机上实测过）；
 * - 建好后每 [WATCH_MS] 查一次组还在不在，没了报 [DirectGroupEvents.onLost]。
 *
 * 权限：安卓 10～12 要精确位置，13 起要「附近的 WiFi 设备」，由调用方在开始前确认；没有时系统抛异常，按没建成报。
 * 线程：自己的工作线程；系统回调和 [DirectGroupEvents] 都在这条线程上。
 */
@SuppressLint("MissingPermission")
class WifiDirectGroupOwner(
    context: Context,
    private val events: DirectGroupEvents,
    private val log: (String) -> Unit,
) : LocalHotspotHandle {
    private val app: Context = context.applicationContext ?: context
    private val thread = HandlerThread("OpenHC-Direct").also { it.start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom().asKotlinRandom()

    // ---------------------------------------------------------------- 以下只在工作线程上访问

    private enum class Stage { IDLE, CLEARING, CREATING, WAITING_GROUP, READY, ENDED }

    private var stage = Stage.IDLE
    private var stopped = false
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var plan: List<Int?> = emptyList()
    private var index = 0
    private var requested: Int? = null
    private var name = ""
    private var pass = ""
    private var deadline = 0L
    private var cellular: ConnectivityManager.NetworkCallback? = null
    private val poll = Runnable { pollGroup() }
    private val watch = Runnable { watchGroup() }

    override fun start() {
        handler.post { begin() }
    }

    override fun stop() {
        handler.post { end() }
    }

    private fun begin() {
        if (stopped || stage != Stage.IDLE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return fail("安卓 10 以下不能按指定频率建直连组")
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return fail("这台车机没有 WiFi 直连服务")
        val ch = try {
            m.initialize(app, thread.looper) { if (!stopped) lose("系统的 WiFi 直连服务断开了") }
        } catch (e: RuntimeException) {
            return fail("连不上系统的 WiFi 直连服务：${describe(e)}")
        } ?: return fail("连不上系统的 WiFi 直连服务")
        manager = m
        channel = ch
        deadline = now() + WIFI_ON_WAIT_MS
        waitWifiOn()
    }

    /**
     * 建组要车机 WiFi 开着。这台车开过本地热点后 WiFi 会被关掉、停热点一两秒后才回来，所以关着时先等一会儿；
     * 到点还关着就明说，不让每个频率挨个被拒。
     */
    private fun waitWifiOn() {
        if (stopped || stage != Stage.IDLE) return
        val on = wifiEnabled()
        if (!on && now() < deadline) {
            handler.postDelayed({ waitWifiOn() }, POLL_MS)
            return
        }
        if (!on) return fail("车机 WiFi 关着，建不了直连组（等了 ${WIFI_ON_WAIT_MS / 1000} 秒也没开）")
        val station = stationFrequency()
        plan = DirectGroupRules.plan(station)
        log(
            "开始建直连组：车机 WiFi ${if (station > 0) "连在 $station MHz" else "没连"}，" +
                "依次试 ${plan.joinToString("、") { it?.let { mhz -> "$mhz" } ?: "由系统定" }}",
        )
        stage = Stage.CLEARING
        groupInfo { group ->
            if (group == null) return@groupInfo attempt(0)
            log("先拆掉残留的直连组（${if (group.isGroupOwner) "本机是组主" else "本机是组成员"}）")
            deadline = now() + CLEAR_WAIT_MS
            removeGroup { waitCleared() }
        }
    }

    private fun waitCleared() {
        if (stopped || stage != Stage.CLEARING) return
        groupInfo { group ->
            when {
                group == null -> attempt(0)
                now() < deadline -> handler.postDelayed({ waitCleared() }, POLL_MS)
                else -> {
                    log("残留的直连组 ${CLEAR_WAIT_MS / 1000} 秒内没拆掉，照样试建")
                    attempt(0)
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun attempt(i: Int) {
        if (stopped) return
        if (i >= plan.size) return fail("试过的频率都没建成直连组（经过见日志）")
        val m = manager ?: return fail("WiFi 直连服务没了")
        val ch = channel ?: return fail("WiFi 直连服务没了")
        index = i
        val frequency = plan[i]
        requested = frequency
        name = DirectGroupRules.networkName(random)
        pass = DirectGroupRules.passphrase(random)
        stage = Stage.CREATING
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onCreateAccepted()

            override fun onFailure(reason: Int) = onCreateRejected(reason)
        }
        try {
            if (frequency == null) {
                m.createGroup(ch, listener)
            } else {
                m.createGroup(ch, config(frequency), listener)
            }
        } catch (e: SecurityException) {
            fail("没有建直连组的权限（安卓 10～12 要精确位置，13 起要附近的 WiFi 设备）：${describe(e)}")
        } catch (e: IllegalArgumentException) {
            log("按 ${label(frequency)} 的配置建不起来（${describe(e)}），换下一个")
            next()
        } catch (e: IllegalStateException) {
            log("按 ${label(frequency)} 的配置建不起来（${describe(e)}），换下一个")
            next()
        } catch (e: NoSuchMethodError) {
            // 有的系统少了按配置建组的接口：直接交给系统定。
            log("这台车机的系统不支持按指定频率建组（${describe(e)}），改为交给系统定")
            plan = plan.take(i) + listOf<Int?>(null)
            handler.post { attempt(i) }
        }
    }

    /** 安卓 10 起：指定组名、口令和频率。 */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun config(frequency: Int): WifiP2pConfig = WifiP2pConfig.Builder()
        .setNetworkName(name)
        .setPassphrase(pass)
        .setGroupOperatingFrequency(frequency)
        .build()

    private fun onCreateAccepted() {
        if (stopped || stage != Stage.CREATING) return
        log("按 ${label(requested)} 申请建组：系统收下了，等组建好")
        stage = Stage.WAITING_GROUP
        deadline = now() + GROUP_WAIT_MS
        pollGroup()
    }

    private fun onCreateRejected(reason: Int) {
        if (stopped || stage != Stage.CREATING) return
        log("按 ${label(requested)} 建组被系统拒绝（${reasonText(reason)}），换下一个")
        next()
    }

    private fun next() {
        val nextIndex = index + 1
        handler.postDelayed({ attempt(nextIndex) }, RETRY_GAP_MS)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun pollGroup() {
        if (stopped || stage != Stage.WAITING_GROUP) return
        groupInfo { group ->
            if (stopped || stage != Stage.WAITING_GROUP) return@groupInfo
            val iface = group?.`interface`?.takeIf { it.isNotEmpty() }
            val ip = iface?.let(::ipv4Of)
            val frequency = group?.frequency ?: 0
            if (group != null && group.isGroupOwner && iface != null && ip != null && frequency > 0) {
                return@groupInfo ready(group, iface, ip, frequency)
            }
            if (now() < deadline) {
                handler.postDelayed(poll, POLL_MS)
                return@groupInfo
            }
            log(
                "按 ${label(requested)} 申请后 ${GROUP_WAIT_MS / 1000} 秒内组没建好" +
                    "（组${if (group == null) "没出现" else "缺网卡、地址或频率"}），拆掉换下一个",
            )
            stage = Stage.CREATING
            removeGroup { next() }
        }
    }

    private fun ready(group: WifiP2pGroup, iface: String, ip: String, frequency: Int) {
        val passphrase = group.passphrase?.takeIf { it.isNotEmpty() } ?: pass
        val networkName = group.networkName?.takeIf { it.isNotEmpty() } ?: name
        if (!LocalHotspotRules.isWifiChannelFrequency(frequency)) {
            return fail("直连组建在 $frequency MHz，不是 2.4 / 5 GHz 的信道，手机按这套连接方式连不上")
        }
        val interfaceMac = hardwareAddress(iface)
        val ownerAddress = group.owner?.deviceAddress
        val (mac, source) = DirectGroupRules.pickOwnerMac(interfaceMac, ownerAddress)
        log(
            "组主 MAC 的来源：直连网卡 $iface 的硬件地址 ${MacText.mask(MacText.of(interfaceMac) ?: "读不到")}，" +
                "组信息里的组主地址 ${MacText.mask(MacText.normalize(ownerAddress) ?: ownerAddress ?: "读不到")}；用 ${source.label}",
        )
        if (source == DirectMacSource.PLACEHOLDER) log("读不到直连网卡的真实 MAC，连接串里的组主 MAC 用占位值（手机可能认不出组）")
        stage = Stage.READY
        holdCellular()
        val built = DirectGroup(ip, networkName, mac, passphrase, frequency, iface, source, requested)
        log("直连组建好了：$built" + if (requested != null && requested != frequency) "；实际频率和申请的不一样" else "")
        if (!stopped) events.onReady(built)
        handler.postDelayed(watch, WATCH_MS)
    }

    private fun watchGroup() {
        if (stopped || stage != Stage.READY) return
        groupInfo { group ->
            if (stopped || stage != Stage.READY) return@groupInfo
            if (group == null) lose("直连组没了") else handler.postDelayed(watch, WATCH_MS)
        }
    }

    private fun end() {
        if (stopped) return
        stopped = true
        stage = Stage.ENDED
        handler.removeCallbacksAndMessages(null)
        releaseCellular()
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            thread.quitSafely()
            return
        }
        log("拆掉直连组")
        var finished = false
        // 系统回话和超时谁先到谁收尾，只收一次。
        val finish = Runnable {
            if (finished) return@Runnable
            finished = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    ch.close()
                } catch (e: RuntimeException) {
                    // 关不掉也无妨：进程退出时系统会清掉。
                }
            }
            thread.quitSafely()
        }
        try {
            m.removeGroup(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        handler.post(finish)
                    }

                    override fun onFailure(reason: Int) {
                        handler.post(finish)
                    }
                },
            )
            handler.postDelayed(finish, REMOVE_WAIT_MS)
        } catch (e: RuntimeException) {
            finish.run()
        }
    }

    private fun fail(message: String) {
        if (stage == Stage.ENDED) return
        stage = Stage.ENDED
        handler.removeCallbacks(poll)
        handler.removeCallbacks(watch)
        log("直连组没建成：$message")
        if (!stopped) events.onFailed(message)
    }

    private fun lose(message: String) {
        if (stage != Stage.READY) return fail(message)
        stage = Stage.ENDED
        handler.removeCallbacks(watch)
        log(message)
        if (!stopped) events.onLost(message)
    }

    // ---------------------------------------------------------------- 系统调用的小包装

    private fun groupInfo(then: (WifiP2pGroup?) -> Unit) {
        val m = manager ?: return then(null)
        val ch = channel ?: return then(null)
        try {
            m.requestGroupInfo(ch) { group -> then(group) }
        } catch (e: SecurityException) {
            fail("没有读直连组信息的权限：${describe(e)}")
        } catch (e: RuntimeException) {
            fail("读不到直连组信息：${describe(e)}")
        }
    }

    private fun removeGroup(then: () -> Unit) {
        val m = manager ?: return then()
        val ch = channel ?: return then()
        try {
            m.removeGroup(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = then()

                    override fun onFailure(reason: Int) = then()
                },
            )
        } catch (e: RuntimeException) {
            then()
        }
    }

    /**
     * 按住车机的蜂窝网络请求，直到组拆掉。没有蜂窝网络的车机上请求一直等着，不影响什么。
     * 要「改网络状态」权限（普通权限，清单里声明即可）。
     */
    private fun holdCellular() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
            cellular = callback
            log("按住车机的蜂窝网络（免得 4G 退到后台后系统清掉直连上的连接）")
        } catch (e: SecurityException) {
            log("按不住车机的蜂窝网络（${describe(e)}）：有自带 4G 的车机上，直连可能在手机关热点约 30 秒后断开")
        } catch (e: RuntimeException) {
            log("按不住车机的蜂窝网络（${describe(e)}）")
        }
    }

    private fun releaseCellular() {
        val callback = cellular ?: return
        cellular = null
        try {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // 放不掉也无妨：进程退出时系统会清掉。
        }
    }

    private fun wifiEnabled(): Boolean = try {
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    } catch (e: RuntimeException) {
        false
    }

    /** 车机作为终端正连着的 WiFi 的频率，没连为 0。 */
    @Suppress("DEPRECATION")
    private fun stationFrequency(): Int = try {
        val info = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        if (info == null || info.ipAddress == 0) 0 else info.frequency.coerceAtLeast(0)
    } catch (e: RuntimeException) {
        0
    }

    private fun ipv4Of(name: String): String? = try {
        NetworkInterface.getByName(name)?.inetAddresses?.let { Collections.list(it) }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (e: SocketException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun hardwareAddress(name: String): ByteArray? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
    } catch (e: SocketException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun label(frequency: Int?): String = frequency?.let { "$it MHz" } ?: "系统定的频率"

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "内部错误"
        WifiP2pManager.P2P_UNSUPPORTED -> "不支持 WiFi 直连"
        WifiP2pManager.BUSY -> "忙"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "没有服务请求"
        else -> "原因 $reason"
    }

    private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        /** 车机 WiFi 关着时等它开的上限（停本地热点后一两秒才回来）。 */
        const val WIFI_ON_WAIT_MS = 3_000L

        /** 拆残留组后等它真的没了的上限。 */
        const val CLEAR_WAIT_MS = 3_000L

        /** 申请建组后等组建好（有网卡、地址、频率）的上限。 */
        const val GROUP_WAIT_MS = 5_000L

        const val POLL_MS = 200L

        /** 两次申请之间的间隔。 */
        const val RETRY_GAP_MS = 300L

        /** 建好后查组还在不在的间隔。 */
        const val WATCH_MS = 3_000L

        /** 拆组时等系统回话的上限，到点照样收尾。 */
        const val REMOVE_WAIT_MS = 1_500L
    }
}
