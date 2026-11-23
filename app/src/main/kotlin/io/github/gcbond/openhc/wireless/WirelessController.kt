package io.github.gcbond.openhc.wireless

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
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.audio.AudioOutput
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.connection.withClarity
import io.github.gcbond.openhc.core.common.identity.HostIdentity
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
import io.github.gcbond.openhc.platform.bluetooth.RfcommServer
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.platform.wifi.LocalHotspotOwner
import io.github.gcbond.openhc.platform.wifi.MacText
import io.github.gcbond.openhc.platform.wifi.WifiStatus
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.WirelessNetworkPreferences
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
 * 无线自动回连（设置里的开关，默认开，规则在 [AutoReconnectPolicy]）：有配对过的手机、有线和无线都没在进行时，
 * 自动开一次同样的连接（「等已配对的手机回连」）：蓝牙控制监听开好后再挂 GATT 服务、发 BLE 广播（[ReconnectBeacon]），
 * 手机靠广播发现车机、自己经蓝牙连上来，之后与扫码完全相同（认证按手机报的 has_pin 自动走免码回连）。
 * 手机经蓝牙连上、连接结束、收尾时都停广播。连接结束（不是用户停的）回到等待；条件不满足只记日志、界面上一行说明，
 * 不弹框不报错。等待时手机还没连上就不挡有线：有线开始时让出（[yieldToWired]、[onWiredChanged]），有线结束后再回来。
 * 两次等待之间留在前台，不先释放再申请（安卓 12 起后台不能再起前台服务）。开关开着时，用户点开始的连接也发广播。
 *
 * WiFi（规则在 [WirelessNetworkPlan]）：车机开本地热点（[LocalHotspotOwner]，安卓 8.0 起才有，更早的车机用不了无线）。
 * 无线一开始（有连接、或在等下一次自动回连）就先把热点开好留着，几次连接之间不关，手机认证通过后直接用；
 * 无线停了（用户停止、有线接手、切到仅有线、不再等回连）才关（[syncHotspotStandby]）。所以无线开着时车机自己的 WiFi 会被热点顶掉。
 * 「退出后恢复车机 WiFi」开着时，热点关掉后由 [wifiRestore] 把车机 WiFi 开回来（安卓 10 起改为提示车主）。
 *
 * 另外两个设置：「只用数据线连接」开着时完全不做无线（不发广播、不等回连、开始不了，手机还没连上的等待立刻停）；
 * 「蓝牙连上自动连接」开着时，手机的经典蓝牙连上车机（[onPhoneBluetooth]，系统广播经 [BluetoothConnectionReceiver] 转来，
 * 本应用没在运行时由系统拉起）就恢复自动回连、空闲就进入等待，已经在等的马上换序列号重新发布。车机主动去连手机
 * （车机当蓝牙客户端发起）没有实现：回连靠手机看到广播后自己连上来。
 *
 * 线程：调度在自己的 [HandlerThread] 上串行执行，公开方法可以在任意线程调用（[sendInput] 在主线程，直接投到协议线程）。
 * 日志标签 `OpenHC-Wireless`。
 */
class WirelessController(context: Context, private val wiredActive: () -> Boolean) {

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

    /** 有线要不要因为无线而不开始：等已配对的手机回连、手机还没连上时不挡（有线来了就让出）。 */
    val blocksWired: Boolean get() = AutoReconnectPolicy.blocksWired(state.value)

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
    private val autoPolicy = AutoReconnectPolicy()
    private var autoRetry: Runnable? = null
    private var lastWiredActive = false

    /**
     * 热点先开好留着：有连接、或在等下一次自动回连时就开着，几次连接之间不关；都没有了才关。
     * 认证通过才申请时正赶上手机关掉自己的热点、车机 WiFi 刚断，实测安卓 10 车机上系统一直不回话。
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
                if (on) post { evaluateAuto("蓝牙打开了") }
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

    /** 「开始无线连接」。运行时权限由界面先申请好（见 [WirelessStartFlow]）。用户自己开始后，自动回连也恢复。 */
    fun start() = post {
        if (attempt != null) return@post
        autoPolicy.onUserStart()
        cancelAutoRetry()
        begin(Kind.MANUAL)
    }

    /** 「停止」：按顺序收尾。停的是自动回连的等待时，本次运行不再自动等待（点开始恢复）。 */
    fun stop() = post {
        val current = attempt
        apply(WirelessSignal.StopRequested)
        if (current == null) {
            if (autoRetry != null) {
                Log.i(TAG, "用户停止：取消排着的自动回连")
                lastEndByUser = true
                cancelAutoRetry()
                autoPolicy.afterAttempt(AutoReconnectPolicy.Outcome.USER_STOP)
                setAutoHold(AutoHold.PAUSED_BY_USER)
            }
            releaseForeground()
            return@post
        }
        Log.i(TAG, "用户停止第 ${current.id} 次无线连接")
        current.stopReason = StopReason.USER
        current.causes.onUserStop()
        teardown(current)
    }

    /** 界面回到前台：条件满足就进入等已配对的手机回连（排着的退避不等了）。 */
    fun onAppForeground() = post {
        cancelAutoRetry()
        evaluateAuto("界面回到前台")
        // 在后台申请热点会被系统拒绝：回到前台时补一次（已经开着或在申请时什么都不做）。
        if (hotspotStandby) warmHotspot()
    }

    /**
     * 开机静默连接（开机广播接收器转来，没有界面）：和界面回到前台一样，条件满足就在后台进入等已配对的手机回连。
     * 不满足的原因照常记在 [WirelessUiState.autoHold]，开机那边据此决定待命还是改为打开界面。
     */
    fun onSilentBoot() = post {
        cancelAutoRetry()
        evaluateAuto("开机静默连接")
    }

    /** 有线的状态变了（主线程回调转来）：有线开始时让出等待，有线结束后回到等待。 */
    fun onWiredChanged() = post {
        val active = wiredActive()
        if (active == lastWiredActive) return@post
        lastWiredActive = active
        if (active) {
            cancelAutoRetry()
            yieldWaiting("有线连接开始了")
            if (attempt == null) {
                setAutoHold(AutoHold.WIRED_ACTIVE)
                releaseForeground()
            }
        } else {
            evaluateAuto("有线连接结束了")
        }
    }

    /** 有线马上要开始：手机还没连上的等待先让出；有线没真的开始（例如没给授权）时过一会儿回到等待。 */
    fun yieldToWired() = post {
        if (yieldWaiting("有线要开始")) return@post
        if (attempt == null && autoRetry != null) {
            cancelAutoRetry()
            scheduleAutoRetry(AutoReconnectPolicy.YIELD_RECHECK_MILLIS, "让给有线")
        }
    }

    /** 设置里切了「无线自动回连」开关。 */
    fun onAutoReconnectSettingChanged() = post {
        autoPolicy.onSettingChanged()
        if (wirelessPrefs.autoReconnect) {
            cancelAutoRetry()
            evaluateAuto("打开了无线自动回连")
            return@post
        }
        cancelAutoRetry()
        val current = attempt
        if (current != null && current.kind == Kind.AUTO && isWaiting(current)) {
            Log.i(TAG, "关掉了无线自动回连，停止等待")
            current.stopReason = StopReason.AUTO_OFF
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else if (current == null) {
            setAutoHold(AutoHold.DISABLED)
            releaseForeground()
        }
    }

    /** 从设置页回来：设置可能改了，还没连上手机的等待按新设置重开一次（下次连接生效的设置才真的生效）。 */
    fun onSettingsClosed() = post {
        val current = attempt
        if (current != null && current.kind == Kind.AUTO && isWaiting(current)) {
            Log.i(TAG, "从设置页回来，按新设置重开等待")
            current.stopReason = StopReason.RESTART
            current.causes.onUserStop()
            apply(WirelessSignal.StopRequested)
            teardown(current)
        } else if (current == null) {
            evaluateAuto("从设置页回来")
        }
    }

    /** 设置里切了「只用数据线连接」：开 → 手机还没连上的无线（等回连、等扫码）立刻停，之后不做无线；关 → 回到等待。 */
    fun onWiredOnlyChanged() = post {
        syncBluetoothReceiver()
        autoPolicy.onSettingChanged()
        cancelAutoRetry()
        if (!wirelessPrefs.wiredOnly) {
            evaluateAuto("关掉了只用数据线连接")
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

    /** 设置里切了「蓝牙连上自动连接」。 */
    fun onConnectOnBluetoothChanged() = post { syncBluetoothReceiver() }

    /**
     * 手机的经典蓝牙连上 / 断开了车机（任意线程，[BluetoothConnectionReceiver] 转来，只报蓝牙设备类是手机的）。
     * 只在「蓝牙连上自动连接」开着、没开「只用数据线连接」时有用：
     * - 连上：当作用户回到车上（恢复自动回连）；空闲就进入等待（「无线自动回连」关着也进），已经在等就马上换序列号重新发布。
     * - 断开：「无线自动回连」关着、这次等待是这台手机蓝牙连上才开始的、手机还没连上来时，跟着停掉。
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
        autoPolicy.onUserStart()
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
    private fun begin(kind: Kind, bluetoothTrigger: String? = null) {
        if (attempt != null) return
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
        if (kind == Kind.AUTO && trustStore.peerCount == 0) {
            return blocked(kind, WirelessNotice.StartFailed("没有配对过的手机"), AutoHold.NO_PAIRED_PHONE)
        }
        val fullScreen = settings.fullScreen
        val display = try {
            DisplayPlan.resolve(
                io.github.gcbond.openhc.connection.DisplayArea.screenFor(app, fullScreen),
                io.github.gcbond.openhc.connection.ResolutionChoice.Auto,
                settings.fps,
            )
        } catch (e: IllegalArgumentException) {
            return blocked(kind, WirelessNotice.StartFailed("屏幕参数不对：${e.message}"), AutoHold.START_FAILED)
        }
        io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen = fullScreen
        Log.i(TAG, "画布 ${display.width}x${display.height}（全屏${if (fullScreen) "开" else "关"}）")
        val clarity = ClarityPreferences(app).clarity
        val audio = AudioPreferences(app).toSettings()
        val beacon = beaconPlan(kind, adapter)
        if (kind == Kind.AUTO && beacon == null) {
            return blocked(kind, WirelessNotice.StartFailed("发不了 BLE 广播"), AutoHold.ADVERTISING_UNSUPPORTED)
        }
        val config = try {
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
        val current = Attempt(++attemptCounter, kind, config, adapter, identity.host, beacon, bluetoothTrigger)
        attempt = current
        inputTarget = current
        Log.i(
            TAG,
            "第 ${current.id} 次无线连接（${if (kind == Kind.AUTO) "等已配对的手机回连" else "用户开始"}）：" +
                "蓝牙地址来源 ${bluetooth.source}，deviceId ${config.deviceId.take(4)}…，" +
                "画布 ${display.width}x${display.height}@${display.fps} dpi ${display.dpi}，系统 ${Build.VERSION.SDK_INT}，$wifi，" +
                "已配对手机 ${trustStore.peerCount} 台，声音 ${audio ?: "关"}，" +
                "广播 ${beacon?.let { "开（重新发布 ${it.republishMillis / 1000} 秒，GATT ${if (it.gatt) "开" else "关"}${if (it.declareRandomAddress) "，声明随机地址" else ""}）" } ?: "不发"}",
        )
        Log.i(TAG, "第 ${current.id} 次无线连接的清晰度：${clarity.key}，声明码率 ${VideoClarity.describe(config.display.bitrate)}")
        setAutoHold(null)
        apply(WirelessSignal.AttemptStarted(current.id, addressMismatch, autoReconnect = kind == Kind.AUTO))
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
     * 这次连接发不发广播、怎么发：自动进入的等待一定发；用户开始的连接在「无线自动回连」开着时也发（手机能靠它发现车机）。
     * 还要有广播权限、车机支持。不发返回 null。
     */
    private fun beaconPlan(kind: Kind, adapter: BluetoothAdapter): ReconnectBeacon.Options? {
        if (kind == Kind.MANUAL && !wirelessPrefs.autoReconnect) return null
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

    // ---------------------------------------------------------------- 自动回连

    /**
     * 条件满足就进入等已配对的手机回连；不满足记下原因（界面上一行说明），没有连接时退出前台。
     * [bluetoothTrigger] 是触发这次的手机蓝牙地址（手机蓝牙连上时），这时「无线自动回连」开关关着也进入。
     */
    private fun evaluateAuto(why: String, bluetoothTrigger: String? = null) {
        if (attempt != null) return
        if (autoRetry != null) return
        lastWiredActive = wiredActive()
        val hold = autoPolicy.hold(autoConditions(), bluetoothTrigger = bluetoothTrigger != null)
        if (hold != null) {
            if (hold != state.value.autoHold) Log.i(TAG, "自动回连不进入等待（$why）：$hold")
            setAutoHold(hold)
            releaseForeground()
            return
        }
        Log.i(TAG, "进入等已配对的手机回连（$why）")
        begin(Kind.AUTO, bluetoothTrigger)
    }

    private fun onPhoneBluetoothConnected(address: String) {
        Log.i(TAG, "手机蓝牙连上了车机（${MacText.mask(address)}）：蓝牙连上自动连接")
        autoPolicy.onBluetoothTrigger()
        val current = attempt
        if (current != null) {
            if (current.kind == Kind.AUTO && isWaiting(current)) current.beacon?.republishNow("手机蓝牙连上了")
            return
        }
        cancelAutoRetry()
        evaluateAuto("手机蓝牙连上了", bluetoothTrigger = address)
    }

    private fun onPhoneBluetoothDisconnected(address: String) {
        val current = attempt ?: return
        val trigger = current.bluetoothTrigger ?: return
        if (current.kind != Kind.AUTO || !isWaiting(current) || wirelessPrefs.autoReconnect) return
        if (!trigger.equals(address, ignoreCase = true)) return
        Log.i(TAG, "触发这次等待的手机蓝牙断开了（${MacText.mask(address)}），停止等待")
        current.stopReason = StopReason.AUTO_OFF
        current.causes.onUserStop()
        apply(WirelessSignal.StopRequested)
        teardown(current)
    }

    /** 清单里「手机蓝牙连上」的入口：开关开着、没开只用数据线连接时启用。 */
    private fun syncBluetoothReceiver() {
        BluetoothConnectionReceiver.sync(app, BluetoothTrigger.listens(wirelessPrefs.connectOnBluetooth, wirelessPrefs.wiredOnly))
    }

    /**
     * 判断自动回连时的条件快照。只有读信任库有副作用（打开时会清掉上次写到一半的临时文件）：有线在进行时它可能正在
     * 首次配对落盘，所以有线在进行（或只用数据线）时不读，按 0 台算——这两种情况本来就排在前面挡住了。
     */
    private fun autoConditions(): AutoReconnectPolicy.Conditions {
        val adapter = BluetoothStatus.adapter(app)
        val sdk = Build.VERSION.SDK_INT
        val address = BluetoothAddressPolicy.chooseForWireless(DeviceInfo.systemBluetoothAddress(), settings.manualBluetoothAddress).address
        val wiredOnly = wirelessPrefs.wiredOnly
        val wiredActive = wiredActive()
        return AutoReconnectPolicy.Conditions(
            enabled = wirelessPrefs.autoReconnect,
            wiredOnly = wiredOnly,
            wiredActive = wiredActive,
            identityLoaded = IdentityLoader.load(app) is IdentityLoader.Result.Loaded,
            pairedPhones = if (wiredOnly || wiredActive) 0 else pairedPhones(),
            bluetoothOn = BluetoothStatus.state(adapter) == BluetoothStatus.State.ON,
            hotspotSupported = WirelessNetworkPlan.hotspotSupported(sdk),
            permissionsGranted = WirelessPermissions.missing(sdk) { granted(it) }.isEmpty() &&
                WirelessPermissions.advertise(sdk).all { granted(it) },
            advertisingSupported = BleBeacon.support(adapter) is BleBeacon.Support.Available,
            bluetoothAddressKnown = address != null,
        )
    }

    /** 配对过的手机数；信任库读不了按 0 算（开始时会报出来）。 */
    private fun pairedPhones(): Int = try {
        FileTrustStore.open(trustDir).peerCount
    } catch (e: IOException) {
        0
    }

    /** [delayMillis] 后再判断一次要不要进入等待；期间留在前台，通知显示正在等回连。 */
    private fun scheduleAutoRetry(delayMillis: Long, why: String) {
        cancelAutoRetry()
        val retry = Runnable {
            autoRetry = null
            autoRetryPending = false
            evaluateAuto(why)
            syncHotspotStandby()
        }
        autoRetry = retry
        autoRetryPending = true
        handler.postDelayed(retry, delayMillis)
        notifyHost()
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
    }

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
    ) {
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
            mediaFocusChanged = { held -> MediaKeys.mediaFocus(keys, held) },
        ) { change -> runner.submit { bridge.sendAudioFocus(change) } }
        val session: WirelessReceiverSession = WirelessReceiverSession(config, sink, audio)
        private val results = RunnerResults()
        val executor = WirelessPlatformExecutor(
            openRfcomm = { name, uuid, events -> RfcommServer(adapter, name, uuid, events, "OpenHC-Rfcomm-$id") },
            // 会话在手机认证通过后才要热点；无线开着时热点已经先开好留着，这里直接接手。之前排的「恢复车机 WiFi」作废。
            startHotspot = { events ->
                wifiRestore.onHotspotRequested()
                LocalHotspotOwner.get(app).create(events) { Log.i(TAG, "[$id] 热点：$it") }
            },
            results = results,
            onEvent = { event -> onWirelessEvent(this, event) },
            log = { Log.i(TAG, "[$id] $it") },
        )
        val bridge: WirelessRunnerBridge = WirelessRunnerBridge(session) { output -> executor.execute(output) }
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

    /** 一次连接收尾之后：按结束方式决定自动回连多久再来；不再来就退出前台。 */
    private fun afterAttempt(current: Attempt) {
        // 热点留着等下一次连接；不再等了才关掉、复查车机 WiFi（见 [syncHotspotStandby]）。
        lastEndByUser = current.stopReason == StopReason.USER
        val duration = SystemClock.elapsedRealtime() - current.startedAt
        val delay = when (current.stopReason) {
            StopReason.USER -> autoPolicy.afterAttempt(AutoReconnectPolicy.Outcome.USER_STOP)
            StopReason.YIELD -> autoPolicy.afterAttempt(AutoReconnectPolicy.Outcome.YIELDED)
            StopReason.RESTART -> autoPolicy.afterAttempt(AutoReconnectPolicy.Outcome.RESTART)
            StopReason.AUTO_OFF, StopReason.SERVICE_GONE -> null
            null -> autoPolicy.afterAttempt(
                if (current.phoneSeen) AutoReconnectPolicy.Outcome.ENDED_AFTER_PHONE else AutoReconnectPolicy.Outcome.ENDED_WITHOUT_PHONE,
                duration,
            )
        }
        when {
            current.stopReason == StopReason.USER && wirelessPrefs.autoReconnect -> setAutoHold(AutoHold.PAUSED_BY_USER)
            current.stopReason == StopReason.SERVICE_GONE -> setAutoHold(AutoHold.FOREGROUND_UNAVAILABLE)
        }
        if (delay == null) {
            releaseForeground()
            return
        }
        // 现在就不满足条件的（开关关了、没有配对过的手机、有线在进行……）不必留在前台等。
        val hold = autoPolicy.hold(autoConditions())
        if (hold != null) {
            Log.i(TAG, "自动回连不再等待：$hold")
            setAutoHold(hold)
            releaseForeground()
            return
        }
        Log.i(TAG, "${delay} 毫秒后回到等已配对的手机回连（连续失败 ${autoPolicy.consecutiveFailures} 次）")
        scheduleAutoRetry(delay, "上一次连接结束")
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
     * 按现在的情况开着或关掉留着的热点。有连接、或在等下一次自动回连就开着；没有了（用户停止、有线接手、
     * 切到仅有线、条件不满足不再等）就关掉，「退出后恢复车机 WiFi」开着时复查车机 WiFi（下一次又开热点时作废）。
     */
    private fun syncHotspotStandby() {
        val wanted = (attempt != null || autoRetry != null) && !wirelessPrefs.wiredOnly && !wiredActive()
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
