package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.audio.AudioOutput
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.connection.withClarity
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.dmsdp.DmsdpConfig
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.nearby.BleAdvertisement
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SessionOutput
import io.github.gcbond.openhc.core.session.SocketRunner
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.core.session.WirelessReceiverConfig
import io.github.gcbond.openhc.core.session.WirelessReceiverSession
import io.github.gcbond.openhc.core.session.WirelessRunnerBridge
import io.github.gcbond.openhc.input.MediaKeys
import io.github.gcbond.openhc.platform.bluetooth.BleBeacon
import io.github.gcbond.openhc.platform.bluetooth.BluetoothStatus
import io.github.gcbond.openhc.platform.bluetooth.RfcommClient
import io.github.gcbond.openhc.platform.bluetooth.RfcommServer
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.wifi.LocalHotspotOwner
import io.github.gcbond.openhc.platform.wifi.WifiDirectGroupOwner
import io.github.gcbond.openhc.platform.wifi.MacText
import io.github.gcbond.openhc.platform.wifi.WifiStatus
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.WirelessNetworkPreferences
import kotlinx.serialization.json.JsonObject
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.trust.FileTrustStore
import io.github.gcbond.openhc.trust.TrustStoreCorruptException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 无线投屏（扫码 → 蓝牙 → 车机开热点 → 投屏）的总调度。进程内唯一（应用对象持有），有连接期间由前台服务
 * [WirelessService] 保活。和有线的 [io.github.gcbond.openhc.connection.ConnectionController] 互斥：同时只能有一种连接。
 *
 * 一次连接的接线：
 * 1. 检查条件（系统能开本地热点、身份资产、车机真实蓝牙地址、蓝牙开着、运行时权限、信任库、画布），
 *    建 [WirelessReceiverConfig]（声明接受触摸、声音按设置）；
 * 2. 起前台服务；[SocketRunner] 经 [WirelessRunnerBridge] 跑 [WirelessReceiverSession]：TCP 部分由执行器做，蓝牙、WiFi 动作
 *    交给 [WirelessPlatformExecutor] 接到平台层；
 * 3. 会话事件、无线事件归并成 [state]：PairingReady 时显示二维码和连接码；VideoNegotiated 时在协议线程上建 [VideoDecoder]，
 *    之后每帧同步送进解码器；声音沿用有线的 [AudioOutput]。
 *
 * 收尾：会话结束（WirelessEvent.Ended）或用户停止 → 执行器 close（会话按顺序发 TEARDOWN、关蓝牙、关热点）→ 声音、解码器关掉
 * → 平台资源兜底关闭 → 回到空闲，显示原因。和有线不同，没有「拔线重插」：结束后点开始就能再来一次（会出新的二维码）。
 *
 * 自动连接手机（设置「自动连接手机」，默认开；规则在 [ReconnectPolicy]、[ReconnectTriggers]）：只拨用无线连成功过的手机，
 * 由车机主动拨（车机当蓝牙客户端发起，[startReconnect][io.github.gcbond.openhc.core.session.WirelessReceiverSession.startReconnect]），
 * 手机热点关着时手机不弹框直接连。两个触发点：
 * - 手机的经典蓝牙连上车机（[onPhoneBluetooth]，系统广播经 [BluetoothConnectionReceiver] 转来，本应用没在运行时由系统拉起）；
 * - 界面回到前台（[onAppForeground]，含开机自动打开）：拨最近连成功的那台。一次蓝牙连接只拨一次，用户点了停止也不重拨。
 * 界面不在前台时先把它带到前台再拨（[surfaceUi]）：车主要看到投屏；而且安卓 10 起定位权限只在界面在前台时算数，否则开不了热点。
 * 有线在等插线时，认识的手机蓝牙连上就请有线停下、改走无线。拨号没成就回到空闲，界面上一行说明，不再等。
 * 用户点「开始无线连接」是扫码连接（新手机配对，配对过的手机扫码免输码）。被动等手机回连（「等已配对的手机回连」、BLE 广播）
 * 已不再自动进入，相关代码待删。
 *
 * WiFi（规则在 [WirelessNetworkPlan]）：车机开本地热点（[LocalHotspotOwner]，安卓 8.0 起才有，更早的车机用不了无线）。
 * 有连接在进行时就先把热点开好留着（车机主动回连要等车机连上手机的蓝牙控制通道之后才开，手机不在时不碰车机 WiFi），
 * 手机认证通过后直接用；连接结束就关（[syncHotspotStandby]），这期间车机自己的 WiFi 会被热点顶掉。
 * 「退出后恢复车机 WiFi」开着时，热点关掉后由 [wifiRestore] 把车机 WiFi 开回来（安卓 10 起改为提示车主）。
 *
 * 另一个设置：「只用数据线连接」开着时完全不做无线（不拨手机、开始不了，手机还没连上的扫码等待立刻停）。
 *
 * 线程：调度在自己的 [HandlerThread] 上串行执行，公开方法可以在任意线程调用（[sendInput] 在主线程，直接投到协议线程）。
 * 日志标签 `OpenHC-Wireless`。
 */
class WirelessController(
    context: Context,
    private val wiredActive: () -> Boolean,
    /** 有线只是在等插线（手机还没插上）。 */
    private val wiredWaitingForPhone: () -> Boolean = { false },
    /** 请有线停下等插线（认识的手机蓝牙连上、要改走无线时）。 */
    private val stopWiredWaiting: () -> Unit = {},
    /** 让有线回到等插线（为无线让路之后，无线没拨成或连接结束时）。 */
    private val resumeWiredWaiting: () -> Unit = {},
) {

    /** 前台服务监听：要不要留在前台、通知内容要不要更新。在主线程上回调。 */
    fun interface HostListener {
        fun onControllerChanged()
    }

    private val app: Context = context.applicationContext ?: context
    private val settings = AppSettings(app)
    private val worker = HandlerThread("OpenHC-Wireless").also { it.start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trustDir by lazy { File(app.noBackupFilesDir, TRUST_DIR) }

    private val mutableState = MutableStateFlow(WirelessUiState())

    /** 无线连接页看的状态。 */
    val state: StateFlow<WirelessUiState> = mutableState.asStateFlow()

    private val mutableDecoder = MutableStateFlow<VideoDecoder?>(null)

    /** 当前连接的解码器（协商出投屏参数后才有），投屏页把画面接到它上面。 */
    val decoder: StateFlow<VideoDecoder?> = mutableDecoder.asStateFlow()

    private val mutableTouchResets = MutableStateFlow(0)

    /** 触摸被丢弃的原因只警告一次，之后同样的丢弃不再刷日志。 */
    private val inputDropNoted = AtomicBoolean(false)

    /** 第一次触摸成功写出时记一次。 */
    private val inputSentNoted = AtomicBoolean(false)

    /** 触摸采样：前 [TOUCH_SAMPLES] 个输入逐个记（动作、点位、画布尺寸），用来核对发给手机的触摸内容。 */
    private val touchSamples = AtomicLong(0)

    /** 记录前几次发给手机的触摸，看动作顺序和坐标映射对不对。 */
    private fun noteTouchSample(input: HiSightInput) {
        val touch = input as? HiSightInput.Touch ?: return
        if (touchSamples.getAndIncrement() >= TOUCH_SAMPLES) return
        val points = touch.points.joinToString { "${it.id}:(${it.x},${it.y})" }
        Log.i(TAG, "触摸采样 #${touchSamples.get()}：${touch.action} $points，画布 ${touch.width}x${touch.height}")
    }

    /** 每次加一，投屏视图就清一次本地触摸状态（回控刚就绪、或发送端报告状态对不上时）。 */
    val touchResets: StateFlow<Int> = mutableTouchResets.asStateFlow()

    /** 前台服务应该在前台：有连接期间为 true。 */
    @Volatile
    var wantsForeground: Boolean = false
        private set

    /** 前台服务真的进了前台（服务自己报）。开机静默连接据此判断后台等待建立起来没有。 */
    @Volatile
    var serviceInForeground: Boolean = false
        internal set

    /** 有一次无线连接在进行（含等已配对的手机回连）。 */
    val isActive: Boolean get() = state.value.active

    /** 有线要不要因为无线而不开始：扫码等待里手机还没连上时不挡（有线来了就让出）。 */
    val blocksWired: Boolean get() = ReconnectPolicy.blocksWired(state.value)

    /** 自动回连排着下一次等待（两次等待之间）：前台服务的通知据此显示「正在等已配对的手机回连」。 */
    @Volatile
    var autoRetryPending: Boolean = false
        private set

    private val hostListeners = CopyOnWriteArrayList<HostListener>()
    private val wirelessPrefs = WirelessPreferences(app)
    private val networkPrefs = WirelessNetworkPreferences(app)

    /** 「退出后恢复车机 WiFi」：连接结束后复查车机 WiFi，安卓 10 起打不开时 [WifiRestorer.needsUser] 给无线页显示提示。 */
    internal val wifiRestore = WifiRestorer(app, handler, { networkPrefs.restoreWifiOnExit }) { Log.i(TAG, it) }

    // ---------------------------------------------------------------- 以下只在调度线程上访问

    private var attempt: Attempt? = null
    private var attemptCounter = 0
    /** 车机主动回连什么时候拨、拨谁（冷却、一次蓝牙连接只拨一次）。 */
    private val triggers = ReconnectTriggers(RECONNECT_COOLDOWN_MS)

    /** 有线在等插线时来了要拨的手机：先请有线停下，等它停好再拨（[onWiredChanged]）。 */
    private var pendingAfterWired: PendingReconnect? = null

    /** 用户点了停止：空闲说明显示「手机蓝牙重新连上时再自动连」，下次开始连接时清掉。 */
    private var userStopped = false

    /** 正在等界面带到前台再拨（那 1.5 秒里别的入口不拨，免得拨成另一台）。 */
    private var waitingForUi = false
    private var autoRetry: Runnable? = null

    /** 连续「投屏没成就自己断掉」的次数（见 [scheduleAutoRetry]），投屏成功清零。 */
    private var autoRetryCount = 0
    private var lastWiredActive = false

    /**
     * 热点提前开好：认证通过才申请时正赶上手机关掉自己的热点、车机 WiFi 刚断，实测安卓 10 车机上系统一直不回话。
     * 什么时候开、什么时候关见 [syncHotspotStandby]。
     */
    private var hotspotStandby = false

    /** 最近一次无线停下是不是用户点了停止（给「退出后恢复车机 WiFi」）。 */
    private var lastEndByUser = false

    /** 广播序列号：进程起来时随机取初值，每发布一次加 1。 */
    private var beaconSequence = SecureRandom().nextInt(SEQUENCE_RANGE)

    init {
        // 蓝牙打开时看看要不要进入等待（车机开机时蓝牙常常比应用晚就绪）。这是只有系统能发的广播，导出也无妨。
        val bluetoothState = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val on = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
                if (on) post { refreshIdleStatus("蓝牙打开了") }
            }
        }
        try {
            ContextCompat.registerReceiver(
                app, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "注册不了蓝牙开关的广播：${e.message}")
        }
        // 清单里「手机蓝牙连上」的入口按设置启停（安装后第一次启动、设置在别处被改过时对齐）。
        handler.post { syncBluetoothReceiver() }
    }

    /** 发触摸用，主线程读。 */
    @Volatile
    private var inputTarget: Attempt? = null

    // ---------------------------------------------------------------- 对外操作（任意线程）

    /** 「开始无线连接」（扫码）。运行时权限由界面先申请好（见 [WirelessStartFlow]）。 */
    fun start() = post {
        if (attempt != null) return@post
        cancelAutoRetry()
        begin(Kind.MANUAL)
    }

    /** 「停止」：按顺序收尾。之后这次蓝牙连接里不再自动拨（手机蓝牙重新连上车机时再拨）。 */
    fun stop() = post {
        val current = attempt
        apply(WirelessSignal.StopRequested)
        if (current == null) {
            releaseForeground()
            return@post
        }
        Log.i(TAG, "用户停止第 ${current.id} 次无线连接")
        userStopped = true
        current.stopReason = StopReason.USER
        current.causes.onUserStop()
        teardown(current)
    }

    /** 界面回到前台（含开机自动打开）：「自动连接手机」开着就拨最近连成功的那台（一次蓝牙连接只拨一次）。 */
    fun onAppForeground() = post {
        dialFromUi("界面回到前台")
        // 在后台申请热点会被系统拒绝：回到前台时补一次（已经开着或在申请时什么都不做）。
        if (hotspotStandby) warmHotspot()
    }

    /**
     * 开机静默连接（开机广播接收器转来，没有界面）：不拨手机（没有界面开不了热点），只把「自动连接手机」现在能不能起作用
     * 记在 [WirelessUiState.autoHold]，开机那边据此决定待命（等手机蓝牙连上时系统把本应用叫起来）还是改为打开界面。
     */
    fun onSilentBoot() = post {
        refreshIdleStatus("开机静默连接")
    }

    /** 有线的状态变了（主线程回调转来）：有线开始时让出扫码等待；有线停下后接着拨等着的手机，或更新空闲说明。 */
    fun onWiredChanged() = post {
        val active = wiredActive()
        if (active == lastWiredActive) return@post
        lastWiredActive = active
        if (active) {
            yieldWaiting("有线连接开始了")
            if (attempt == null) {
                setAutoHold(AutoHold.WIRED_ACTIVE)
                releaseForeground()
            }
        } else {
            val pending = pendingAfterWired
            if (pending != null) {
                pendingAfterWired = null
                Log.i(TAG, "有线已经停下，接着拨手机（${pending.why}）")
                beginReconnect(pending.address, pending.why, preemptWired = false, resumeWiredAfter = true)
                return@post
            }
            refreshIdleStatus("有线连接结束了")
        }
    }

    /** 有线马上要开始：手机还没连上的等待先让出；有线没真的开始（例如没给授权）时过一会儿回到等待。 */
    fun yieldToWired() = post {
        yieldWaiting("有线要开始")
    }

    /** 从设置页回来：设置可能改了（例如刚打开「自动连接手机」），照界面回到前台一样看要不要拨。 */
    fun onSettingsClosed() = post {
        if (attempt == null) dialFromUi("从设置页回来")
    }

    /** 设置里切了「只用数据线连接」：开 → 手机还没连上的无线（等扫码、在拨）立刻停，之后不做无线；关 → 刷新空闲说明。 */
    fun onWiredOnlyChanged() = post {
        syncBluetoothReceiver()
        cancelAutoRetry()
        if (!wirelessPrefs.wiredOnly) {
            refreshIdleStatus("关掉了只用数据线连接")
            return@post
        }
        val current = attempt
        if (current != null && isWaiting(current)) {
            Log.i(TAG, "打开了只用数据线连接，停止第 ${current.id} 次无线连接（手机还没连上）")
            current.stopReason = StopReason.AUTO_OFF
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else if (current == null) {
            setAutoHold(AutoHold.WIRED_ONLY)
            releaseForeground()
        }
        // 手机已经连上的无线连接照常进行到结束，之后不再做无线。
    }

    /** 设置里切了「自动连接手机」。 */
    fun onConnectOnBluetoothChanged() = post {
        syncBluetoothReceiver()
        refreshIdleStatus("切了自动连接手机")
    }

    /**
     * 手机的经典蓝牙连上 / 断开了车机（任意线程，[BluetoothConnectionReceiver] 转来，只报蓝牙设备类是手机的）。
     * 只在「自动连接手机」开着、没开「只用数据线连接」时有用：连上是一次新的蓝牙连接，认识的手机就拨（[ReconnectTriggers]）；
     * 断开清掉「这次蓝牙连接里拨过」。
     */
    fun onPhoneBluetooth(connected: Boolean, address: String) = post {
        if (!BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly)) return@post
        if (connected) onPhoneBluetoothConnected(address) else onPhoneBluetoothDisconnected(address)
    }

    /** 界面那边就开始不了（例如权限被拒）。 */
    fun reportBlocked(notice: WirelessNotice) = post {
        if (attempt == null) block(notice)
    }

    /** 用户确认清除损坏的配对记录：把文件改名留存，然后重新开始。 */
    fun resetTrustStoreAndStart() = post {
        if (attempt != null) return@post
        try {
            FileTrustStore.setAside(trustDir, System.currentTimeMillis())?.let {
                Log.w(TAG, "损坏的配对记录已改名留存为 ${it.name}，之后手机要重新配对")
            }
        } catch (e: IOException) {
            block(WirelessNotice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName))
            return@post
        }
        cancelAutoRetry()
        begin(Kind.MANUAL)
    }

    /** 投屏视图换算好的触摸（主线程）。回控就绪前不发；发送在协议线程上做，异常就地接住。 */
    fun sendInput(input: HiSightInput) {
        val current = inputTarget ?: run {
            if (inputDropNoted.compareAndSet(false, true)) {
                Log.w(TAG, "收到触摸但还没有可回控的连接，丢弃（协商结果见 VideoNegotiated）")
            }
            return
        }
        if (!current.inputReady) {
            if (inputDropNoted.compareAndSet(false, true)) {
                Log.w(TAG, "收到触摸但这条连接的回控还没就绪（inputReady=false，多半是手机没选触摸事件），丢弃")
            }
            return
        }
        current.runner.submit {
            val result: List<SessionOutput> = try {
                val outputs = current.bridge.sendInput(input)
                if (outputs.isNotEmpty() && inputSentNoted.compareAndSet(false, true)) {
                    Log.i(TAG, "第一次触摸已写出（${outputs.size} 个包），之后触摸不再逐次记日志")
                }
                noteTouchSample(input)
                outputs
            } catch (e: IllegalStateException) {
                Log.d(TAG, "触摸状态对不上，清掉重来：${e.message}")
                mutableTouchResets.update { it + 1 }
                emptyList()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "触摸不合法，丢弃：${e.message}")
                emptyList()
            }
            result
        }
    }

    fun addHostListener(listener: HostListener) {
        hostListeners += listener
    }

    fun removeHostListener(listener: HostListener) {
        hostListeners -= listener
    }

    /** 前台服务起不来。自动进入的等待安静放弃，等界面回到前台再试。 */
    fun onServiceStartFailed(message: String) = post {
        wantsForeground = false
        // 两次等待之间、或自动进入的等待：都是自动回连这边的，安静放弃。
        val automatic = autoRetry != null || state.value.autoReconnect
        cancelAutoRetry()
        val current = attempt
        if (current == null) {
            if (automatic) setAutoHold(AutoHold.FOREGROUND_UNAVAILABLE) else block(WirelessNotice.StartFailed(message))
        } else if (current.kind == Kind.AUTO) {
            Log.w(TAG, "前台服务起不来（$message），放弃这次等待，界面回到前台时再试")
            current.stopReason = StopReason.SERVICE_GONE
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else {
            current.causes.onFailure(message)
            apply(WirelessSignal.StopRequested)
            teardown(current)
        }
    }

    /** 前台服务不是自己要退出、却被销毁了：没有保活，停掉连接（自动回连等界面回到前台再试）。 */
    fun onServiceDestroyed() = post {
        if (!wantsForeground) return@post
        Log.w(TAG, "前台服务被系统结束，停止无线连接")
        wantsForeground = false
        cancelAutoRetry()
        val current = attempt ?: return@post
        apply(WirelessSignal.StopRequested)
        current.stopReason = StopReason.SERVICE_GONE
        current.causes.onUserStop()
        teardown(current)
    }

    // ---------------------------------------------------------------- 开始

    /**
     * 开一次连接。[kind] 是 [Kind.AUTO]（自动进入的等待）时开始不了只记日志、界面上一行说明（[AutoHold]），不报错。
     */
    private fun begin(kind: Kind, bluetoothTrigger: String? = null, reconnect: ReconnectTarget? = null, resumeWiredAfter: Boolean = false) {
        if (attempt != null) return
        userStopped = false
        if (wirelessPrefs.wiredOnly) return blocked(kind, WirelessNotice.WiredOnly, AutoHold.WIRED_ONLY)
        if (wiredActive()) return blocked(kind, WirelessNotice.WiredActive, AutoHold.WIRED_ACTIVE)
        // 本地热点安卓 8.0 起才有，更早的车机用不了无线。不要求车机 WiFi 开着：开热点时单射频车机自己的 WiFi 本来就会断。
        if (!WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT)) {
            return blocked(kind, WirelessNotice.HotspotUnsupported, AutoHold.HOTSPOT_UNSUPPORTED)
        }
        val identity = when (val loaded = IdentityLoader.load(app)) {
            is IdentityLoader.Result.Loaded -> loaded.assets
            IdentityLoader.Result.Missing -> return blocked(kind, WirelessNotice.IdentityMissing, AutoHold.IDENTITY_MISSING)
            is IdentityLoader.Result.Invalid ->
                return blocked(kind, WirelessNotice.IdentityInvalid(loaded.reason), AutoHold.IDENTITY_MISSING)
        }
        // 二维码、握手、认证里的车机身份都由它推出，必须是真实地址：读不到就只能让车主填。
        val systemAddress = DeviceInfo.systemBluetoothAddress()
        val bluetooth = BluetoothAddressPolicy.chooseForWireless(systemAddress, settings.manualBluetoothAddress)
        val address = bluetooth.address
            ?: return blocked(kind, WirelessNotice.BluetoothAddressMissing, AutoHold.BLUETOOTH_ADDRESS_MISSING)
        // 无线用系统里读到的真实地址；手填的只在读不到时用。手填的像编出来的地址时，界面提醒一句。
        val manual = BluetoothAddressPolicy.normalize(settings.manualBluetoothAddress)
        if (bluetooth.source == BluetoothSource.SYSTEM_PROPERTY && manual != null && manual != address) {
            Log.i(TAG, "手填的蓝牙地址和系统里读到的不同：无线用系统里的真实地址，手填的只用于有线")
        }
        val addressMismatch = bluetooth.source == BluetoothSource.MANUAL && BluetoothAddressPolicy.isLocallyAdministered(address)
        if (addressMismatch) {
            Log.w(TAG, "读不到系统里的蓝牙地址，用的是手填的；它像是编出来的地址，不是车机真实地址时扫码后不会有反应")
        }
        val adapter = BluetoothStatus.adapter(app)
            ?: return blocked(kind, WirelessNotice.BluetoothUnsupported, AutoHold.BLUETOOTH_OFF)
        if (BluetoothStatus.state(adapter) != BluetoothStatus.State.ON) {
            return blocked(kind, WirelessNotice.BluetoothOff, AutoHold.BLUETOOTH_OFF)
        }
        val wifi = WifiStatus.read(app)
        val missing = WirelessPermissions.missing(Build.VERSION.SDK_INT) { granted(it) }
        if (missing.isNotEmpty()) {
            return blocked(kind, WirelessNotice.PermissionsDenied(missing, permanently = false), AutoHold.PERMISSIONS_MISSING)
        }
        val trustStore = try {
            FileTrustStore.open(trustDir)
        } catch (e: TrustStoreCorruptException) {
            return blocked(kind, WirelessNotice.TrustStoreCorrupt(e.message ?: ""), AutoHold.START_FAILED)
        } catch (e: IOException) {
            return blocked(kind, WirelessNotice.TrustStoreUnavailable(e.message ?: e.javaClass.simpleName), AutoHold.START_FAILED)
        }
        if (kind != Kind.MANUAL && trustStore.peerCount == 0) {
            return blocked(kind, WirelessNotice.StartFailed("没有配对过的手机"), AutoHold.NO_PAIRED_PHONE)
        }
        val fullScreen = settings.fullScreen
        val display = try {
            DisplayPlan.resolve(
                io.github.gcbond.openhc.connection.DisplayArea.screenFor(app, fullScreen),
                io.github.gcbond.openhc.connection.ResolutionChoice.Auto,
                settings.fps,
                settings.uiScalePercent,
            )
        } catch (e: IllegalArgumentException) {
            return blocked(kind, WirelessNotice.StartFailed("屏幕参数不对：${e.message}"), AutoHold.START_FAILED)
        }
        io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen = fullScreen
        Log.i(TAG, "画布 ${display.width}x${display.height}（全屏${if (fullScreen) "开" else "关"}）")
        val clarity = ClarityPreferences(app).clarity
        val audio = AudioPreferences(app).toSettings()
        val beacon = if (kind == Kind.RECONNECT) null else beaconPlan(kind, adapter)
        if (kind == Kind.AUTO && beacon == null) {
            return blocked(kind, WirelessNotice.StartFailed("发不了 BLE 广播"), AutoHold.ADVERTISING_UNSUPPORTED)
        }
        val config = try {
            // extra 服务实验结论（v16/v17）：0004CC 下手机静默收下但无任何后续；000489 下疑似导致手机
            // 在投影协商阶段反复断连（8/9/11 三个会话同点 abort）。磁贴修复实验回归 v14 稳定形状：
            // 不声明 extra 服务，只保留 514 主动请求与 RawRecord 日志。
            WirelessReceiverConfig(
                identity = identity,
                bluetoothAddress = address,
                display = display.withClarity(clarity),
                trustStore = trustStore,
                supportsUsb = true,
                touchInput = true,
                mediaKeyInput = MediaKeyPreferences(app).enabled,
                timeouts = WirelessNetworkPlan.timeouts(),
                audio = audio,
            )
        } catch (e: IllegalArgumentException) {
            return blocked(kind, WirelessNotice.StartFailed("配置不合法：${e.message}"), AutoHold.START_FAILED)
        }
        if (!requestForeground()) {
            return blocked(kind, WirelessNotice.StartFailed(FOREGROUND_FAILED), AutoHold.FOREGROUND_UNAVAILABLE)
        }

        cancelAutoRetry()
        val directGroup = wirelessPrefs.usesWifiDirect
        val current = Attempt(
            ++attemptCounter, kind, config, adapter, identity.host, beacon, bluetoothTrigger, reconnect, resumeWiredAfter, directGroup,
        )
        attempt = current
        if (reconnect != null) triggers.commitDial(reconnect.address, SystemClock.elapsedRealtime())
        inputTarget = current
        Log.i(
            TAG,
            "第 ${current.id} 次无线连接（${when (kind) { Kind.AUTO -> "等已配对的手机回连"; Kind.RECONNECT -> "车机主动回连"; Kind.MANUAL -> "用户开始" }}）：" +
                "蓝牙地址来源 ${bluetooth.source}，deviceId ${config.deviceId.take(4)}…，" +
                "画布 ${display.width}x${display.height}@${display.fps} dpi ${display.dpi}（界面大小 ${settings.uiScalePercent}%），系统 ${Build.VERSION.SDK_INT}，$wifi，" +
                "已配对手机 ${trustStore.peerCount} 台，声音 ${audio ?: "关"}，手机加入的网络 ${if (directGroup) "WiFi 直连组（试验）" else "本地热点"}，" +
                "广播 ${beacon?.let { "开（重新发布 ${it.republishMillis / 1000} 秒，GATT ${if (it.gatt) "开" else "关"}${if (it.declareRandomAddress) "，声明随机地址" else ""}）" } ?: "不发"}",
        )
        Log.i(TAG, "第 ${current.id} 次无线连接的清晰度：${clarity.key}，声明码率 ${VideoClarity.describe(config.display.bitrate)}")
        setAutoHold(null)
        apply(WirelessSignal.AttemptStarted(current.id, addressMismatch, autoReconnect = kind == Kind.AUTO, direct = directGroup))
        current.runner.start()
        watchTermination(current)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** 开始不了：回到空闲，显示原因。 */
    private fun block(notice: WirelessNotice) {
        Log.w(TAG, "无线连接不能开始：$notice")
        apply(WirelessSignal.Blocked(notice))
        releaseForeground()
    }

    /** 开始不了：用户开始的显示原因；自动进入的只记日志、界面上一行说明。 */
    private fun blocked(kind: Kind, notice: WirelessNotice, hold: AutoHold) {
        if (kind == Kind.MANUAL) return block(notice)
        Log.i(TAG, "自动回连这次不进入等待：$hold（$notice）")
        setAutoHold(hold)
        releaseForeground()
    }

    /**
     * 这次连接发不发广播、怎么发：只有被动等手机回连（已不再自动进入）才发；扫码连接不用（手机按二维码直接连车机），
     * 车机主动回连也不用。还要有广播权限、车机支持。不发返回 null。
     */
    private fun beaconPlan(kind: Kind, adapter: BluetoothAdapter): ReconnectBeacon.Options? {
        if (kind != Kind.AUTO) return null
        val missing = WirelessPermissions.advertise(Build.VERSION.SDK_INT).filterNot { granted(it) }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "没有蓝牙广播权限，这次不发广播（${if (kind == Kind.AUTO) "不进入等待" else "只能扫码"}）")
            return null
        }
        val support = BleBeacon.support(adapter)
        if (support !is BleBeacon.Support.Available) {
            Log.w(TAG, "这台车机发不了 BLE 广播：$support，已配对的手机不能自动回连，只能扫码")
            return null
        }
        return ReconnectBeacon.Options(
            gatt = wirelessPrefs.gattServer,
            republishMillis = wirelessPrefs.republishSeconds * 1000L,
            declareRandomAddress = wirelessPrefs.declareRandomAddress,
        )
    }

    // ---------------------------------------------------------------- 自动连接手机

    /** 更新空闲时的一行说明（「自动连接手机」能不能起作用）；没有连接时退出前台。 */
    private fun refreshIdleStatus(why: String) {
        if (attempt != null) return
        lastWiredActive = wiredActive()
        var hold = ReconnectPolicy.hold(reconnectConditions())
        if (hold == AutoHold.READY && userStopped) hold = AutoHold.PAUSED_BY_USER
        if (hold != state.value.autoHold) Log.i(TAG, "自动连接手机（$why）：$hold")
        setAutoHold(hold)
        releaseForeground()
    }

    /**
     * 界面在前台时（回到前台、开机自动打开、从设置页回来）拨最近连成功的那台。能查到手机蓝牙连没连着车机时只在连着时拨，
     * 有线在等插线也请它让出；查不到时照拨（手机不在就连不上，热点要等连上手机的蓝牙才开，不碰车机 WiFi），但不打断有线。
     */
    private fun dialFromUi(why: String) {
        refreshIdleStatus(why)
        if (attempt != null || waitingForUi || !wirelessPrefs.connectOnBluetooth || wirelessPrefs.wiredOnly) return
        if (userStopped) {
            Log.i(TAG, "点过停止，回到界面不自动拨；手机蓝牙重新连上车机时再拨（$why）")
            return
        }
        val last = wirelessPrefs.lastPhone ?: return
        val connected = BluetoothStatus.adapter(app)?.let { phoneConnected(it, last) }
        if (connected == false) {
            Log.i(TAG, "最近那台手机的蓝牙没连着车机，不拨（$why）")
            return
        }
        if (connected == null && wiredActive()) {
            Log.i(TAG, "查不到手机蓝牙连没连着、有线在进行：不拨（$why）")
            return
        }
        when (val decision = triggers.onUiShown(last, wirelessPrefs.knownPhones, SystemClock.elapsedRealtime())) {
            is ReconnectTriggers.Decision.Dial ->
                beginReconnect(decision.address, "$why（手机蓝牙${if (connected == true) "连着" else "连没连着查不到"}）", preemptWired = connected == true)
            is ReconnectTriggers.Decision.Skip -> Log.i(TAG, "不自动拨（$why）：${decision.reason}")
        }
    }

    private fun onPhoneBluetoothConnected(address: String) {
        Log.i(TAG, "手机蓝牙连上了车机（${MacText.mask(address)}）")
        when (val decision = triggers.onBluetoothConnected(address, wirelessPrefs.knownPhones, SystemClock.elapsedRealtime())) {
            is ReconnectTriggers.Decision.Dial -> beginReconnect(decision.address, "手机蓝牙连上了", preemptWired = true)
            is ReconnectTriggers.Decision.Skip -> Log.i(TAG, "手机蓝牙连上了，不拨：${decision.reason}")
        }
    }

    private fun onPhoneBluetoothDisconnected(address: String) {
        triggers.onBluetoothDisconnected(address)
        // 认识的手机蓝牙断开：点过的停止只管到这次蓝牙连接为止。
        if (userStopped && wirelessPrefs.knownPhones.any { it.equals(address, ignoreCase = true) }) {
            userStopped = false
            refreshIdleStatus("手机蓝牙断开了")
        }
    }

    /**
     * 车机主动回连（任意线程）：马上拨蓝牙地址为 [address] 的手机，不看「自动连接手机」开关和记录。给调试入口用；
     * 真的拨出去了同样计入冷却和这次蓝牙连接（[ReconnectTriggers.commitDial]，在 [begin] 里记）。
     */
    fun reconnectPhone(address: String, why: String) = post {
        beginReconnect(address, why, preemptWired = true)
    }

    /**
     * 拨 [address]：先判前置条件（不满足只在界面上一行说明，不弹界面），有线在等插线时按 [preemptWired] 请它让出，
     * 界面不在前台先带到前台、等 [SURFACE_WAIT_MS] 后确认界面真出来了再拨（出不来就不拨：开不了热点，拨了也白拨、手机还会记一次失败）。
     * [resumeWiredAfter]：有线是为这次让出来的，这次没拨成、或连接结束后让有线回到等插线。
     * 真的开始拨了才记进拨号规则（[begin] 里 [ReconnectTriggers.commitDial]），没拨成不占名额。
     */
    private fun beginReconnect(address: String, why: String, preemptWired: Boolean, resumeWiredAfter: Boolean = false) {
        fun giveUp() {
            if (resumeWiredAfter) {
                Log.i(TAG, "这次没拨成，让有线回到等插线")
                resumeWiredWaiting()
            }
        }
        if (attempt != null || waitingForUi) {
            Log.i(TAG, "已经有连接在进行或在等界面，不拨手机（$why）")
            return
        }
        val hold = ReconnectPolicy.hold(reconnectConditions(forDial = true)).takeUnless { it == AutoHold.READY }
        if (hold != null) {
            Log.i(TAG, "不拨手机（$why）：$hold")
            setAutoHold(hold)
            return giveUp()
        }
        if (wiredActive()) {
            if (preemptWired && wiredWaitingForPhone()) {
                Log.i(TAG, "有线在等插线：请它先停下，改为车机主动拨手机（$why）")
                pendingAfterWired = PendingReconnect(address, why)
                stopWiredWaiting()
            } else {
                Log.i(TAG, "有线连接在进行，不拨手机（$why）")
                setAutoHold(AutoHold.WIRED_ACTIVE)
            }
            return
        }
        val adapter = BluetoothStatus.adapter(app)
        val name = adapter?.let { phoneName(it, address) }
        if (name == null) {
            Log.i(TAG, "${if (adapter == null) "车机没有蓝牙" else "读不到这台手机的蓝牙名"}，不拨（$why）")
            return giveUp()
        }
        cancelAutoRetry()
        val target = ReconnectTarget(address, name)
        if (!surfaceUi(why)) {
            Log.i(TAG, "车机主动拨手机（${MacText.mask(address)}）：$why")
            begin(Kind.RECONNECT, reconnect = target, resumeWiredAfter = resumeWiredAfter)
            if (attempt == null) giveUp()
            return
        }
        Log.i(TAG, "车机主动拨手机（${MacText.mask(address)}）：$why；界面带到前台就拨（最多等 $SURFACE_WAIT_MS 毫秒）")
        waitingForUi = true
        // 等界面带到前台：每 150ms 查一次，界面一出来马上拨（活动恢复一般 100~500ms，原来固定等满 1.5 秒白耗）；
        // 到上限还查一次，行为与原来一致——没出来就这次不拨（开热点要定位权限，界面不在前台不算数）。
        val deadline = SystemClock.elapsedRealtime() + SURFACE_WAIT_MS
        fun probe() {
            waitingForUi = false
            when {
                attempt != null -> Log.i(TAG, "等界面时已经有连接在进行，不拨了")
                !uiVisible() -> {
                    Log.i(TAG, "界面没能打开（多半缺悬浮窗权限或被系统拦了），这次不拨：开不了热点")
                    setAutoHold(AutoHold.UI_NOT_SHOWN)
                    giveUp()
                }
                else -> {
                    begin(Kind.RECONNECT, reconnect = target, resumeWiredAfter = resumeWiredAfter)
                    if (attempt == null) giveUp()
                }
            }
            syncHotspotStandby()
        }
        fun tick() {
            if (uiVisible()) return probe()
            if (SystemClock.elapsedRealtime() >= deadline) return probe()
            handler.postDelayed(::tick, SURFACE_POLL_MS)
        }
        tick()
    }

    private fun uiVisible(): Boolean = (app as? OpenHcApp)?.uiVisibility?.visible != false

    /**
     * 车机主动回连前把界面带到前台；界面已经在前台返回 false，否则发出打开请求后返回 true。
     *
     * 一是车主要看到投屏；二是安卓 10 起定位权限是「使用期间允许」，只有界面在前台才算数（前台服务不算），
     * 界面不在前台时系统不让开热点（2026-11-22 实车：手机蓝牙连上触发、界面在后台，认证通过后开热点被拒
     * 「does not have Coarse/Fine Location permission」）。安卓 10 起后台打开界面要悬浮窗权限，没有时请求不报错、界面不出来。
     */
    private fun surfaceUi(why: String): Boolean {
        if (uiVisible()) return false
        Log.i(
            TAG,
            "车机主动回连：界面不在前台，先带到前台（$why）" + if (overlayGranted()) "" else "；没有悬浮窗权限，多半被系统拦下",
        )
        try {
            app.startActivity(BootStart.surfaceIntent(app))
        } catch (e: RuntimeException) {
            Log.w(TAG, "带不到前台：${e.javaClass.simpleName}：${e.message}")
        }
        return true
    }

    private fun overlayGranted(): Boolean = !BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT) || BootStart.canDrawOverlays(app)

    /** 车机蓝牙记下的这台手机的名字（请手机开认证子通道时当业务标签；实车只验证过和手机蓝牙名一致的情况）。 */
    @SuppressLint("MissingPermission")
    private fun phoneName(adapter: BluetoothAdapter, address: String): String? = try {
        adapter.getRemoteDevice(address).name?.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * 这台手机的经典蓝牙现在连没连着车机。用的是系统的隐藏接口（BluetoothDevice.isConnected），拿不到时返回 null（不知道）。
     */
    private fun phoneConnected(adapter: BluetoothAdapter, address: String): Boolean? = try {
        val device = adapter.getRemoteDevice(address)
        (android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean).also {
            Log.i(TAG, "手机蓝牙连着车机：$it（${MacText.mask(address)}）")
        }
    } catch (e: ReflectiveOperationException) {
        Log.i(TAG, "查不到手机蓝牙连没连着（${e.javaClass.simpleName}）")
        null
    } catch (e: RuntimeException) {
        Log.i(TAG, "查不到手机蓝牙连没连着（${e.javaClass.simpleName}）")
        null
    }

    /** 清单里「手机蓝牙连上」的入口：开关开着、没开只用数据线连接时启用。 */
    private fun syncBluetoothReceiver() {
        BluetoothConnectionReceiver.sync(app, BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly))
    }

    /**
     * 判断「自动连接手机」时的条件快照。[forDial]：马上要拨（触发规则已经定了拨谁），不再看开关、记录和悬浮窗（悬浮窗另看界面出没出来），
     * 有线在进行另外处理。
     */
    private fun reconnectConditions(forDial: Boolean = false): ReconnectPolicy.Conditions {
        val adapter = BluetoothStatus.adapter(app)
        val sdk = Build.VERSION.SDK_INT
        val address = BluetoothAddressPolicy.chooseForWireless(DeviceInfo.systemBluetoothAddress(), settings.manualBluetoothAddress).address
        return ReconnectPolicy.Conditions(
            enabled = forDial || wirelessPrefs.connectOnBluetooth,
            wiredOnly = wirelessPrefs.wiredOnly,
            wiredActive = !forDial && wiredActive(),
            hotspotSupported = WirelessNetworkPlan.hotspotSupported(sdk),
            identityLoaded = IdentityLoader.load(app) is IdentityLoader.Result.Loaded,
            bluetoothAddressKnown = address != null,
            bluetoothOn = BluetoothStatus.state(adapter) == BluetoothStatus.State.ON,
            permissionsGranted = WirelessPermissions.missing(sdk) { granted(it) }.isEmpty(),
            knownPhones = if (forDial) 1 else wirelessPrefs.knownPhones.size,
            overlayGranted = forDial || overlayGranted(),
        )
    }

    private fun cancelAutoRetry() {
        val retry = autoRetry ?: return
        handler.removeCallbacks(retry)
        autoRetry = null
        autoRetryPending = false
    }

    private fun setAutoHold(hold: AutoHold?) {
        apply(WirelessSignal.AutoHoldChanged(hold))
    }

    /** 这次连接还在等手机经蓝牙连上（没在收尾）。 */
    private fun isWaiting(current: Attempt): Boolean = !current.phoneSeen && !current.tearingDown

    /** 让出还在等手机的自动等待；让了返回 true。 */
    private fun yieldWaiting(why: String): Boolean {
        val current = attempt ?: return false
        if (current.kind != Kind.AUTO || !isWaiting(current)) return false
        Log.i(TAG, "$why，停止等已配对的手机回连，让给有线")
        current.stopReason = StopReason.YIELD
        current.causes.onUserStop()
        apply(WirelessSignal.StopRequested)
        teardown(current)
        return true
    }

    /** 下一个广播序列号。 */
    private fun nextBeaconSequence(): Int {
        beaconSequence = BleAdvertisement.nextSequence(beaconSequence)
        return beaconSequence
    }

    // ---------------------------------------------------------------- 一次连接

    /** 这次连接是怎么开始的。 */
    private enum class Kind {
        /** 用户点了开始（或清除配对记录后重新开始）。 */
        MANUAL,

        /** 自动进入的「等已配对的手机回连」。 */
        AUTO,

        /** 车机主动回连：车机去拨已配对的手机（手机蓝牙连上车机时、或调试入口），不发广播、不出二维码。 */
        RECONNECT,
    }

    /** 车机主动回连要拨的手机：蓝牙地址和设备名（请手机开认证子通道时的业务标签）。 */
    private class ReconnectTarget(val address: String, val name: String)

    /** 有线在等插线时来了要拨的手机：等有线停好后再拨。 */
    private class PendingReconnect(val address: String, val why: String)

    /** 本机为什么停掉这次连接（会话自己结束的为 null）。决定之后自动回连多久再来。 */
    private enum class StopReason {
        USER,

        /** 让给有线。 */
        YIELD,

        /** 按新设置重开等待。 */
        RESTART,

        /** 关掉了自动回连。 */
        AUTO_OFF,

        /** 前台服务起不来或被系统结束：等界面回到前台再试。 */
        SERVICE_GONE,
    }

    private inner class Attempt(
        val id: Int,
        val kind: Kind,
        config: WirelessReceiverConfig,
        adapter: BluetoothAdapter,
        host: HostIdentity,
        beaconOptions: ReconnectBeacon.Options?,
        /** 这次等待是这台手机的经典蓝牙连上才开始的（地址），其他为 null。 */
        val bluetoothTrigger: String?,
        /** 车机主动回连的目标（[Kind.RECONNECT]），其他为 null。 */
        reconnect: ReconnectTarget?,
        /** 有线是为这次让出来的：结束后让有线回到等插线。 */
        val resumeWiredAfter: Boolean = false,
        /** 这次不开本地热点，改建 WiFi 直连组（设置里「无线方式」选了直连）。 */
        val directGroup: Boolean = false,
    ) {
        /** 投屏开始过。 */
        var projected = false

        /** 这次连的手机的蓝牙地址：主动回连时是拨的那台，扫码时手机经蓝牙连上来才知道；投屏开始后记进「连成功过的手机」。 */
        var phoneAddress: String? = reconnect?.address

        val sink = ForwardingSink()
        val startedAt = SystemClock.elapsedRealtime()

        /** 车机媒体键的去处（[MediaKeys]）：按键在主线程上转进来，投到协议线程上经桥接发给手机。 */
        val keys = MediaKeys.Target("无线 #$id") { key ->
            if (!tearingDown) runner.submit { MediaKeys.sendNow("无线 #$id", key, bridge::sendInput) }
        }
        val mediaKeysDeclared = config.mediaKeyInput

        /**
         * 声音出口：音频回调在协议线程上，收尾时在执行器停下之后关。日志里的连接编号加上偏移，和有线的区分开。
         * 车机这边的焦点变化投到协议线程上经桥接发给手机。
         */
        val audio: AudioOutput = AudioOutput(
            app, AUDIO_ID_BASE + id,
            wireless = true,
            mediaFocusChanged = { held -> MediaKeys.mediaFocus(keys, held) },
        ) { change -> runner.submit { bridge.sendAudioFocus(change) } }
        val session: WirelessReceiverSession = WirelessReceiverSession(config, sink, audio)
        private val results = RunnerResults()
        val executor = WirelessPlatformExecutor(
            openRfcomm = { name, uuid, events -> RfcommServer(adapter, name, uuid, events, "OpenHC-Rfcomm-$id") },
            dialRfcomm = { address, uuid, channel, events ->
                val byChannel = channel.takeUnless { DebugOverrides.forceRfcommSdp }
                if (channel != null && byChannel == null) Log.i(TAG, "[$id] 调试开关：认证子通道改按 SDP 查")
                RfcommClient(adapter, address, uuid, events, "OpenHC-Wireless-$id-dial", byChannel)
            },
            // 会话在手机认证通过后才要热点；无线开着时热点已经先开好留着，这里直接接手。之前排的「恢复车机 WiFi」作废。
            startHotspot = { events ->
                wifiRestore.onHotspotRequested()
                LocalHotspotOwner.get(app).create(events) { Log.i(TAG, "[$id] 热点：$it") }
            },
            // 直连：不开热点、不动车机自己的 WiFi，所以也不排「恢复车机 WiFi」。
            startDirect = if (directGroup) {
                { events -> WifiDirectGroupOwner(app, events) { Log.i(TAG, "[$id] 直连：$it") } }
            } else {
                null
            },
            results = results,
            onEvent = { event -> onWirelessEvent(this, event) },
            log = { Log.i(TAG, "[$id] $it") },
        )
        val bridge: WirelessRunnerBridge = WirelessRunnerBridge(
            session,
            reconnect?.let { target -> { now: Long -> session.startReconnect(target.address, target.name, now) } } ?: session::start,
        ) { output -> executor.execute(output) }
        val runner: SocketRunner = SocketRunner(
            bridge,
            { event -> onProtocolEvent(this, event) },
            SocketRunner.Options(threadName = "OpenHC-Wireless-$id", log = { Log.i(TAG, "[$id] 执行器：$it") }),
        )
        val causes = WirelessEndTracker()
        var touchNegotiated = false
        var keyNegotiated = false
        @Volatile
        var tearingDown = false
        var finished = false

        /** 手机经蓝牙连上过。 */
        var phoneSeen = false
        var stopReason: StopReason? = null

        /** BLE 广播和 GATT 服务端；这次不发广播为 null。只在调度线程上用。 */
        val beacon: ReconnectBeacon? = beaconOptions?.let { options ->
            ReconnectBeacon(
                context = app,
                adapter = adapter,
                host = host,
                options = options,
                sequence = { nextBeaconSequence() },
                handler = handler,
                onStatus = { status -> onBeaconStatus(this, status) },
                log = { Log.i(TAG, "[$id] 广播：$it") },
            )
        }

        /** 协议线程建、调度线程收尾时关。 */
        @Volatile
        var videoDecoder: VideoDecoder? = null

        @Volatile
        var inputReady = false

        init {
            results.runner = runner
            results.bridge = bridge
            bridge.attach(runner)
        }
    }

    /** 视频回调的转接：解码器要等协商出参数才建，建好前到的帧丢掉（第一帧总在 PLAY 之后）。 */
    private class ForwardingSink : AccessUnitSink {
        @Volatile
        var target: AccessUnitSink? = null

        override fun onAccessUnit(unit: AccessUnit) {
            target?.onAccessUnit(unit)
        }
    }

    /** 会话事件（TCP 段与认证），在协议线程上。 */
    private fun onProtocolEvent(current: Attempt, event: SessionEvent) {
        if (event is SessionEvent.Diagnostic) Log.d(TAG, "[${current.id}] $event") else Log.i(TAG, "[${current.id}] $event")
        // 在协议线程上同步建好解码器：之后的帧也在这个线程上送，保证第一帧到时解码器已经接上。
        if (event is SessionEvent.VideoNegotiated) prepareDecoder(current, event.video)
        post { onSessionEvent(current, event) }
    }

    /** 无线事件，在协议线程上（经执行器转来）。 */
    private fun onWirelessEvent(current: Attempt, event: WirelessEvent) {
        Log.i(TAG, "[${current.id}] $event")
        // 结束原因按发生的先后记，不等调度线程排到。
        if (event is WirelessEvent.Ended) current.causes.onSessionEnded(event.reason)
        post {
            if (current !== attempt || current.finished) return@post
            when (event) {
                // 蓝牙控制监听开好了，手机连上来有地方接：这时才挂 GATT、发广播。
                is WirelessEvent.PairingReady -> current.beacon?.onListenerReady()
                is WirelessEvent.PhoneBluetoothConnected -> {
                    current.phoneSeen = true
                    current.phoneAddress = event.address
                    current.beacon?.stop("手机经蓝牙连上了")
                }
                is WirelessEvent.Ended -> current.beacon?.stop("连接结束")
                else -> Unit
            }
            apply(WirelessSignal.Wireless(event))
            if (event is WirelessEvent.Ended) {
                if (current.tearingDown) maybeFinish(current) else teardown(current)
            }
        }
    }

    /** 广播的情况（调度线程上，ReconnectBeacon 回调）。 */
    private fun onBeaconStatus(current: Attempt, status: BeaconStatus) {
        if (current !== attempt || current.finished) return
        if (status is BeaconStatus.Failed) Log.w(TAG, "[${current.id}] 广播发不了：${status.reason}，已配对的手机不能自动回连，只能扫码")
        apply(WirelessSignal.Beacon(status))
    }

    private fun onSessionEvent(current: Attempt, event: SessionEvent) {
        if (current !== attempt || current.finished) return
        apply(WirelessSignal.Session(event))
        when (event) {
            // 投屏开始了才算连成功：记下这台手机，之后「自动连接手机」就会拨它。
            is SessionEvent.VideoStarted -> {
                current.projected = true
                autoRetryCount = 0
                current.phoneAddress?.let { address ->
                    if (wirelessPrefs.rememberPhone(address)) Log.i(TAG, "记下连成功的手机（${MacText.mask(address)}），之后会自动连它")
                }
            }
            is SessionEvent.VideoNegotiated -> {
                current.touchNegotiated = event.video.touchInput
                current.keyNegotiated = event.video.keyInput
                MediaKeys.negotiated(current.keys, current.mediaKeysDeclared, event.video.keyInput)
            }
            // PLAY 只在回控连上后才发：手机接受了 PLAY，回控就绪。
            SessionEvent.PlayAccepted -> {
                current.inputReady = current.touchNegotiated && !current.tearingDown
                if (current.keyNegotiated && !current.tearingDown) MediaKeys.ready(app, current.keys)
            }
            else -> Unit
        }
    }

    private fun prepareDecoder(current: Attempt, video: VideoParameters) {
        val decoder = current.videoDecoder ?: VideoDecoder(DecoderListener(current.id)).also {
            current.videoDecoder = it
            current.sink.target = it
            mutableDecoder.value = it
        }
        decoder.prepare(video.width, video.height, video.fps)
    }

    private inner class DecoderListener(private val attemptId: Int) : VideoDecoder.Listener {
        override fun onVideoSizeChanged(width: Int, height: Int) {
            post { if (attempt?.id == attemptId) apply(WirelessSignal.VideoSize(width, height)) }
        }

        override fun onDecoderUnavailable(detail: String) {
            post { if (attempt?.id == attemptId) apply(WirelessSignal.DecoderProblem(detail)) }
        }
    }

    /** 执行器意外退出（不会有 Ended 事件）时也要能收尾：另开一个线程等它结束。 */
    private fun watchTermination(current: Attempt) {
        thread(name = "OpenHC-WirelessWatch-${current.id}", isDaemon = true) {
            try {
                while (!current.runner.awaitTermination(WATCH_SLICE_MS)) {
                    // 一直等到执行器结束。
                }
            } catch (e: InterruptedException) {
                return@thread
            }
            post { onRunnerTerminated(current) }
        }
    }

    private fun onRunnerTerminated(current: Attempt) {
        if (current.finished) return
        current.runner.failure?.let { failure ->
            Log.e(TAG, "第 ${current.id} 次无线连接的执行器意外结束", failure)
            current.causes.onFailure("执行器意外结束：${failure.javaClass.simpleName}：${failure.message}")
        }
        if (current.tearingDown) {
            maybeFinish(current)
        } else {
            apply(WirelessSignal.StopRequested)
            teardown(current)
        }
    }

    /** 按顺序收尾，见类注释。可重复调用。 */
    private fun teardown(current: Attempt) {
        if (current.tearingDown) return
        current.tearingDown = true
        current.inputReady = false
        MediaKeys.gone(current.keys)
        // 0. 先停广播：收尾可能要几秒，别让手机这时再按广播连过来。
        current.beacon?.stop("连接收尾")
        // 1. 协议层：会话按顺序收尾（TEARDOWN、关蓝牙、拆 WiFi 网络）；已经结束时立即返回，否则最多阻塞几秒。
        current.runner.close()
        // 2. 声音、解码器：执行器已经停了，不会再有回调。
        current.audio.close()
        current.sink.target = null
        current.videoDecoder?.close()
        current.videoDecoder = null
        mutableDecoder.value = null
        // 3. 平台资源兜底：正常收尾时会话已经逐个关过，这里什么都不剩；执行器意外退出时靠这一步撤掉服务记录、拆 WiFi 网络。
        current.executor.closeAll()
        maybeFinish(current)
    }

    private fun maybeFinish(current: Attempt) {
        if (current.finished || !current.tearingDown || !current.runner.isTerminated) return
        current.finished = true
        // 兜底：广播、GATT、定时一样不留。
        current.beacon?.stop("兜底")
        if (current.beacon?.isClean == false) Log.e(TAG, "[${current.id}] 广播资源没有收干净")
        if (attempt === current) {
            attempt = null
            inputTarget = null
        }
        val cause = current.causes.result()
        Log.i(TAG, "第 ${current.id} 次无线连接已收尾：$cause")
        apply(WirelessSignal.AttemptFinished(cause))
        afterAttempt(current)
    }

    /** 一次连接收尾之后：回到空闲（更新空闲说明、退出前台）。 */
    private fun afterAttempt(current: Attempt) {
        // 热点跟着连接走：连接结束就关、复查车机 WiFi（见 [syncHotspotStandby]）。不再自动回到等待。
        lastEndByUser = current.stopReason == StopReason.USER
        current.phoneAddress?.let { triggers.onSessionEnded(it, current.projected, stoppedByUser = lastEndByUser) }
        if (current.resumeWiredAfter && !wirelessPrefs.wiredOnly && !wiredActive()) {
            Log.i(TAG, "无线连接结束，让有线回到等插线（之前为无线让出来的）")
            resumeWiredWaiting()
        }
        if (current.stopReason == StopReason.SERVICE_GONE) {
            setAutoHold(AutoHold.FOREGROUND_UNAVAILABLE)
            releaseForeground()
            return
        }
        scheduleAutoRetry(current)
        refreshIdleStatus("上一次连接结束")
    }

    /**
     * 手机侧抽风自愈：投屏没成、会话自己断掉（手机 abort，2026-11-23 实车连着 3 次：第 3 次死在设备会话、
     * 第 4/5 次死在投屏协商，都是 SocketException abort，第 6 次手动重拨才成）→ 3 秒后自动重拨同一台手机，
     * 最多连试 [AUTO_RETRY_MAX] 次，投屏成功即清零。用户停的、投屏过的结束、手机蓝牙已断开都不重试——
     * 投屏过的结束是有意义的（手机主动断开或车主收工），自动回拨会惹人烦；没投过屏的 abort 才是「手机没准备好」。
     */
    private fun scheduleAutoRetry(current: Attempt) {
        if (current.stopReason != null || current.projected) {
            if (current.projected) autoRetryCount = 0
            return
        }
        if (current.kind == Kind.AUTO) return
        val address = current.phoneAddress ?: return
        if (autoRetryCount >= AUTO_RETRY_MAX) {
            Log.i(TAG, "已经自动重试 $autoRetryCount 次没成，不再重试（手机蓝牙重新连上车机时再拨）")
            return
        }
        val connected = BluetoothStatus.adapter(app)?.let { phoneConnected(it, address) }
        if (connected == false) return
        autoRetryCount++
        Log.i(TAG, "投屏没成就结束了，$AUTO_RETRY_DELAY_MS 毫秒后自动重拨（第 $autoRetryCount/$AUTO_RETRY_MAX 次）")
        val retry = Runnable {
            autoRetry = null
            autoRetryPending = false
            beginReconnect(address, "上一次投屏没成，自动重试", preemptWired = false)
        }
        autoRetry = retry
        autoRetryPending = true
        handler.postDelayed(retry, AUTO_RETRY_DELAY_MS)
    }

    // ---------------------------------------------------------------- 前台服务

    private fun requestForeground(): Boolean {
        if (wantsForeground) return true
        wantsForeground = true
        return try {
            ContextCompat.startForegroundService(app, Intent(app, WirelessService::class.java))
            true
        } catch (e: RuntimeException) {
            // 例如安卓 12 起应用在后台时不能起前台服务。
            Log.e(TAG, "起不了前台服务", e)
            wantsForeground = false
            false
        }
    }

    private fun releaseForeground() {
        if (!wantsForeground) return
        wantsForeground = false
        notifyHost()
    }

    private fun notifyHost() {
        mainHandler.post { for (listener in hostListeners) listener.onControllerChanged() }
    }

    // ---------------------------------------------------------------- 工具

    private fun apply(signal: WirelessSignal) {
        val before = mutableState.value
        val after = WirelessReducer.reduce(before, signal)
        if (after == before) return
        mutableState.value = after
        // 回控刚就绪：清一次本地触摸状态，就绪前按下的手指不会接着发 MOVE。
        if (after.inputReady && !before.inputReady) mutableTouchResets.update { it + 1 }
        if (after.phase != before.phase || after.step != before.step) {
            Log.i(TAG, "状态 ${before.phase}/${before.step} → ${after.phase}/${after.step}")
            notifyHost()
        }
    }

    private fun post(block: () -> Unit) {
        handler.post {
            block()
            syncHotspotStandby()
        }
    }

    /**
     * 按现在的情况开着或关掉留着的热点。有连接在进行就开着（车机主动回连要等连上手机的蓝牙控制通道）；连接结束
     * （或切到仅有线、有线接手）就关掉，「退出后恢复车机 WiFi」开着时复查车机 WiFi（下一次又开热点时作废）。
     */
    private fun syncHotspotStandby() {
        val current = attempt
        // 车机主动回连要等车机连上手机的蓝牙控制通道才开：手机不在旁边时不顶掉车机自己的 WiFi。
        // 建直连组的连接不开热点（开热点会停掉车机 WiFi，组就建不起来了）。
        val wanted = current != null && !current.directGroup && (current.kind != Kind.RECONNECT || current.phoneSeen) &&
            !wirelessPrefs.wiredOnly && !wiredActive()
        if (wanted == hotspotStandby) return
        hotspotStandby = wanted
        if (wanted) {
            warmHotspot()
        } else {
            LocalHotspotOwner.existing()?.coolDown()
            wifiRestore.onConnectionOver(userStopped = lastEndByUser)
        }
    }

    /** 申请把热点开好留着；之前排的「恢复车机 WiFi」复查作废（WiFi 马上又会被热点顶掉）。 */
    private fun warmHotspot() {
        wifiRestore.onHotspotRequested()
        LocalHotspotOwner.get(app).keepWarm { Log.i(TAG, "热点：$it") }
    }

    private companion object {
        /** 同一台手机两次车机主动回连之间至少隔这么久。 */
        const val RECONNECT_COOLDOWN_MS = 60_000L

        /** 车机主动回连时界面不在前台：带到前台后等这么久再拨（界面一般 1 秒内出来；出不来也照拨）。 */
        const val SURFACE_WAIT_MS = 1_500L

        /** 等界面带到前台的轮询间隔（界面一出来就拨，不用等满 [SURFACE_WAIT_MS]）。 */
        const val SURFACE_POLL_MS = 150L

        /** 投屏没成就自己断掉后的自动重拨：间隔与连续次数上限（实测手机要抽风 2~3 次才肯连）。 */
        const val AUTO_RETRY_DELAY_MS = 3_000L
        const val AUTO_RETRY_MAX = 3

        const val TAG = "OpenHC-Wireless"

        /** 触摸采样最多记几个输入。 */
        const val TOUCH_SAMPLES = 10L

        /** 与有线共用同一份配对记录：同一台手机有线、无线配一次就行。 */
        const val TRUST_DIR = "trust"

        /** 声音日志里的连接编号：无线的从 1001 起，和有线的 1、2、3… 分开。 */
        const val AUDIO_ID_BASE = 1000

        /** 广播序列号的取值个数（1 字节）。 */
        const val SEQUENCE_RANGE = 256
        const val WATCH_SLICE_MS = 60_000L
        const val FOREGROUND_FAILED = "前台服务启动失败"
    }
}
