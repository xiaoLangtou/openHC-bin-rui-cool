package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.connection.ConnectionReducer
import io.github.gcbond.openhc.connection.ConnectionSignal
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessEvent

// 无线连接的界面状态：无线会话事件、会话事件和本机操作归并成一份状态。本文件是纯逻辑，不引用安卓类，
// 由 JVM 单元测试覆盖；接线见 WirelessController。

/** 无线连接页显示的阶段。 */
enum class WirelessPhase {
    /** 没在连接：还没开始、用户点了停止，或者出了错（原因见 [WirelessUiState.notice]）。 */
    IDLE,

    /** 正在准备：检查条件、开蓝牙监听。 */
    PREPARING,

    /** 二维码已显示，等手机扫码连上蓝牙。 */
    WAITING_FOR_SCAN,

    /** 手机已经连上，正在一步步往下走（见 [WirelessStep]）。 */
    CONNECTING,

    /** 投屏中。 */
    PROJECTING,

    /** 正在断开、收尾。 */
    STOPPING,
}

/** [WirelessPhase.CONNECTING] 里更细的一步。 */
enum class WirelessStep {
    BLUETOOTH_HANDSHAKE,
    WAITING_AUTH,

    /** 车机主动回连：手机暂时没同意，多半在手机上弹了确认框（例如手机开着热点），等用户在手机上点。 */
    CONFIRM_ON_PHONE,

    /** 首次配对：口令是二维码里带的连接码，手机自动带上。 */
    AUTH_PAIRING,

    /** 免码回连。 */
    AUTH_TRUSTED,
    NEGOTIATING_WIFI,
    SENDING_NETWORK,
    WAITING_PHONE_JOIN,
    WIFI_HANDSHAKE,
    DEVICE_SESSION,
    WAITING_PROJECTION,
    NEGOTIATING_PROJECTION,
}

/** 一次无线连接为什么结束。 */
sealed class WirelessEndCause {
    /** 用户点了停止（或服务被系统结束）。 */
    object UserStop : WirelessEndCause() {
        override fun toString() = "已停止"
    }

    /** 会话结束。[reason] 的 toString 不含密钥和设备标识。 */
    class Session(val reason: WirelessEndReason) : WirelessEndCause() {
        override fun toString() = "Session($reason)"
    }

    /** 程序自己的问题（执行器意外退出等）。 */
    data class Failure(val message: String) : WirelessEndCause()
}

/** 无线连接页要提示给用户的问题或上次结束的原因。 */
sealed class WirelessNotice {
    object IdentityMissing : WirelessNotice() {
        override fun toString() = "IdentityMissing"
    }

    data class IdentityInvalid(val reason: String) : WirelessNotice()

    /** 没有车机真实的蓝牙地址（二维码里必须是真实地址）：要用户去设置里填。 */
    object BluetoothAddressMissing : WirelessNotice() {
        override fun toString() = "BluetoothAddressMissing"
    }

    object BluetoothUnsupported : WirelessNotice() {
        override fun toString() = "BluetoothUnsupported"
    }

    object BluetoothOff : WirelessNotice() {
        override fun toString() = "BluetoothOff"
    }

    /** 车机系统是安卓 8.0 以下，没有本地热点：用不了无线连接。 */
    object HotspotUnsupported : WirelessNotice() {
        override fun toString() = "HotspotUnsupported"
    }

    /**
     * 缺运行时权限。[permanently]：系统不再弹框（用户选了不再询问），只能去系统设置里开；
     * [approximateOnly]：安卓 12、12L 上用户只给了大致位置，开热点要精确位置。
     */
    data class PermissionsDenied(
        val missing: List<String>,
        val permanently: Boolean,
        val approximateOnly: Boolean = false,
    ) : WirelessNotice()

    /** 有线连接正在进行（两种连接同时只能有一种）。 */
    object WiredActive : WirelessNotice() {
        override fun toString() = "WiredActive"
    }

    /** 设置里开着「只用数据线连接」：无线关着。 */
    object WiredOnly : WirelessNotice() {
        override fun toString() = "WiredOnly"
    }

    data class TrustStoreCorrupt(val reason: String) : WirelessNotice()

    data class TrustStoreUnavailable(val reason: String) : WirelessNotice()

    data class StartFailed(val reason: String) : WirelessNotice()

    /** 上一次无线连接结束的原因；[direct]：那次手机加入的是 WiFi 直连组（WiFi 那几步失败时说法不同）。 */
    data class Ended(val cause: WirelessEndCause, val direct: Boolean = false) : WirelessNotice()
}

/** 状态归并的输入。 */
sealed class WirelessSignal {
    /**
     * [addressMismatch]：用的是手填的蓝牙地址，和系统里读到的不同（见 [WirelessUiState.addressMismatch]）。
     * [autoReconnect]：这次是自动进入的「等已配对的手机回连」，不是用户点的开始。
     */
    data class AttemptStarted(
        val attempt: Int,
        val addressMismatch: Boolean = false,
        val autoReconnect: Boolean = false,
        /** 这次手机加入的是车机建的 WiFi 直连组，不是热点（设置里「无线方式」选了直连）。 */
        val direct: Boolean = false,
    ) : WirelessSignal()

    /** 开始不了（条件不满足），回到空闲。 */
    data class Blocked(val notice: WirelessNotice) : WirelessSignal()

    object StopRequested : WirelessSignal()

    class Wireless(val event: WirelessEvent) : WirelessSignal()

    class Session(val event: SessionEvent) : WirelessSignal()

    data class VideoSize(val width: Int, val height: Int) : WirelessSignal()

    data class DecoderProblem(val detail: String) : WirelessSignal()

    /** 这次连接已彻底收尾。 */
    data class AttemptFinished(val cause: WirelessEndCause) : WirelessSignal()

    /** 这次连接里 BLE 广播的情况变了。 */
    data class Beacon(val status: BeaconStatus) : WirelessSignal()

    /** 自动回连没在等的原因变了（null = 没有要说明的）。 */
    data class AutoHoldChanged(val hold: AutoHold?) : WirelessSignal()
}

/** 无线连接页看的状态。 */
data class WirelessUiState(
    val phase: WirelessPhase = WirelessPhase.IDLE,
    val step: WirelessStep? = null,
    /** 二维码内容；只在等扫码时有值。 */
    val qrCode: String? = null,
    /** 6 位连接码（和二维码里的是同一个）；认证成功或手机从 WiFi 连上后清掉。 */
    val pinCode: String? = null,
    /**
     * 投屏段的状态，由有线的 [ConnectionReducer] 归并（WiFi 段与有线完全相同）：投屏页直接用它
     * （画面尺寸、第一帧、解码器问题、回控是否就绪）。
     */
    val projection: ConnectionUiState = ConnectionUiState(),
    val notice: WirelessNotice? = null,
    /** 第几次无线连接（从 1 开始；0 表示还没开始过）。 */
    val attempt: Int = 0,
    /** 有一次连接在进行（从开始到收尾完）。 */
    val active: Boolean = false,
    /**
     * 这次用的是设置里手填的蓝牙地址（系统里读不到），而且它像是编出来的地址。二维码里的地址手机要拿去
     * 直接连车机蓝牙，不是车机真实地址时扫码后不会有反应，界面上提醒一句。
     */
    val addressMismatch: Boolean = false,
    /** 这次连接是自动进入的「等已配对的手机回连」（同时也显示二维码，新手机照样能扫）。 */
    val autoReconnect: Boolean = false,
    /** 这次连接里 BLE 广播的情况；没发广播为 null。 */
    val beacon: BeaconStatus? = null,
    /** 自动回连没在等的原因（只在空闲时显示）；null = 没有要说明的。 */
    val autoHold: AutoHold? = null,
    /** 这次连接里手机经蓝牙连上过。 */
    val phoneConnected: Boolean = false,
    /** 这次连接手机加入的是车机建的 WiFi 直连组（不是热点）：WiFi 那几步的说法跟着变。只在连接进行中有意义。 */
    val direct: Boolean = false,
) {
    /** 回控已就绪且手机接受触摸。 */
    val inputReady: Boolean get() = projection.inputReady

    /**
     * 自动进入的等待、手机还没经蓝牙连上（含这时的收尾）：有线来了要让给有线，首页入口也不算「进行中」。
     */
    val waitingForReconnect: Boolean
        get() = active && autoReconnect && !phoneConnected

    /** 不输出二维码和连接码。 */
    override fun toString(): String =
        "WirelessUiState(phase=$phase, step=$step, attempt=$attempt, active=$active, notice=$notice, " +
            "二维码=${if (qrCode != null) "有" else "无"}, 连接码=${if (pinCode != null) "有" else "无"}, 投屏=${projection.phase}, " +
            "自动回连=$autoReconnect, 直连=$direct, 广播=$beacon, 不等的原因=$autoHold)"
}

/** 状态归并：纯函数。 */
object WirelessReducer {

    private val ATTEMPT_PHASES = setOf(
        WirelessPhase.PREPARING, WirelessPhase.WAITING_FOR_SCAN, WirelessPhase.CONNECTING, WirelessPhase.PROJECTING,
        WirelessPhase.STOPPING,
    )

    fun reduce(state: WirelessUiState, signal: WirelessSignal): WirelessUiState = when (signal) {
        is WirelessSignal.AttemptStarted -> WirelessUiState(
            phase = WirelessPhase.PREPARING,
            attempt = signal.attempt,
            active = true,
            addressMismatch = signal.addressMismatch,
            projection = ConnectionReducer.reduce(ConnectionUiState(), ConnectionSignal.AttemptStarted(signal.attempt)),
            autoReconnect = signal.autoReconnect,
            direct = signal.direct,
            // 自动回到等待时留着上一次的结束原因，看得出刚才为什么断了。
            notice = if (signal.autoReconnect) state.notice else null,
        )

        is WirelessSignal.Blocked -> WirelessUiState(notice = signal.notice, attempt = state.attempt, autoHold = state.autoHold)

        WirelessSignal.StopRequested -> if (state.phase == WirelessPhase.IDLE) state else stopping(state)

        is WirelessSignal.Wireless -> onWireless(state, signal.event)

        is WirelessSignal.Session -> onSession(state, signal.event)

        is WirelessSignal.VideoSize -> if (state.phase in ATTEMPT_PHASES) {
            state.copy(projection = ConnectionReducer.reduce(state.projection, ConnectionSignal.VideoSize(signal.width, signal.height)))
        } else {
            state
        }

        is WirelessSignal.DecoderProblem -> if (state.phase in ATTEMPT_PHASES) {
            state.copy(projection = ConnectionReducer.reduce(state.projection, ConnectionSignal.DecoderProblem(signal.detail)))
        } else {
            state
        }

        is WirelessSignal.AttemptFinished -> WirelessUiState(
            notice = if (signal.cause is WirelessEndCause.UserStop) null else WirelessNotice.Ended(signal.cause, state.direct),
            attempt = state.attempt,
            autoHold = state.autoHold,
        )

        is WirelessSignal.Beacon -> if (state.phase in ATTEMPT_PHASES) state.copy(beacon = signal.status) else state

        is WirelessSignal.AutoHoldChanged -> state.copy(autoHold = signal.hold)
    }

    /** 正在连接、没在收尾：只有这时会话事件才改阶段（收尾时和空闲时迟到的事件不理）。 */
    private fun live(state: WirelessUiState) = state.phase in ATTEMPT_PHASES && state.phase != WirelessPhase.STOPPING

    private fun onWireless(state: WirelessUiState, event: WirelessEvent): WirelessUiState {
        if (!live(state)) return state
        return when (event) {
            is WirelessEvent.PairingReady ->
                state.copy(phase = WirelessPhase.WAITING_FOR_SCAN, step = null, qrCode = event.qrCode, pinCode = event.pinCode)
            // 手机连上了，二维码没用了；连接码留着，手机万一要求手输时还看得到。
            is WirelessEvent.PhoneBluetoothConnected ->
                connecting(state, WirelessStep.BLUETOOTH_HANDSHAKE).copy(qrCode = null, phoneConnected = true)
            is WirelessEvent.BluetoothHandshakeCompleted -> connecting(state, WirelessStep.WAITING_AUTH)
            is WirelessEvent.ReconnectAwaitingPhone -> connecting(state, WirelessStep.CONFIRM_ON_PHONE)
            // 热点开好了才会回协商应答：协商完直接发网络信息。
            is WirelessEvent.DataChannelNegotiated -> connecting(state, WirelessStep.SENDING_NETWORK)
            // 热点在握手、认证途中或协商之前开好，不打断当前这一步。
            is WirelessEvent.WifiNetworkReady -> state
            is WirelessEvent.ConnectionInfoSent -> connecting(state, WirelessStep.WAITING_PHONE_JOIN)
            is WirelessEvent.Ended -> stopping(state)
        }
    }

    private fun onSession(state: WirelessUiState, event: SessionEvent): WirelessUiState {
        if (!live(state)) return state
        val projection = if (feedsProjection(event)) {
            ConnectionReducer.reduce(state.projection, ConnectionSignal.Session(event))
        } else {
            state.projection
        }
        val next = state.copy(projection = projection)
        return when (event) {
            is SessionEvent.AuthStarted -> connecting(
                next,
                if (event.mode == AuthMode.PIN_PAIRING) WirelessStep.AUTH_PAIRING else WirelessStep.AUTH_TRUSTED,
            )
            is SessionEvent.Authenticated -> connecting(next, WirelessStep.NEGOTIATING_WIFI).copy(qrCode = null, pinCode = null)
            is SessionEvent.PhoneConnected -> connecting(next, WirelessStep.WIFI_HANDSHAKE).copy(qrCode = null, pinCode = null)
            // WiFi 控制通道上握手完，认证早已在蓝牙上做完，手机接着就要设备会话。
            is SessionEvent.ControlHandshakeCompleted -> connecting(next, WirelessStep.DEVICE_SESSION)
            is SessionEvent.DeviceSessionReady -> connecting(next, WirelessStep.WAITING_PROJECTION)
            is SessionEvent.ProjectionRequested -> connecting(next, WirelessStep.NEGOTIATING_PROJECTION)
            is SessionEvent.VideoNegotiated, is SessionEvent.VideoStarted -> next.copy(phase = WirelessPhase.PROJECTING, step = null)
            // 回控就绪等只影响投屏段；诊断不改阶段；监听开好、显示连接码、有线的结束在无线里不会出现。
            else -> next
        }
    }

    /**
     * 投屏段只从 WiFi 段起归并：蓝牙段的认证事件在有线归并里会被当成「请在手机上输入连接码」，对扫码不适用。
     */
    private fun feedsProjection(event: SessionEvent): Boolean = when (event) {
        is SessionEvent.AuthStarted, is SessionEvent.Authenticated, is SessionEvent.ShowPinCode,
        is SessionEvent.ControlListening, is SessionEvent.Ended, is SessionEvent.MediaMetadataUpdated,
        -> false
        else -> true
    }

    private fun connecting(state: WirelessUiState, step: WirelessStep) = state.copy(phase = WirelessPhase.CONNECTING, step = step)

    private fun stopping(state: WirelessUiState) = state.copy(
        phase = WirelessPhase.STOPPING,
        step = null,
        qrCode = null,
        pinCode = null,
        projection = ConnectionReducer.reduce(state.projection, ConnectionSignal.StopRequested),
    )
}

/** 记下一次无线连接**最先**结束的原因；之后的「本机停止」盖不掉它。线程安全。 */
class WirelessEndTracker {
    private var first: WirelessEndCause? = null

    @Synchronized
    fun onUserStop() {
        if (first == null) first = WirelessEndCause.UserStop
    }

    @Synchronized
    fun onSessionEnded(reason: WirelessEndReason) {
        if (first == null && reason !is WirelessEndReason.LocalStop) first = WirelessEndCause.Session(reason)
    }

    @Synchronized
    fun onFailure(message: String) {
        if (first == null) first = WirelessEndCause.Failure(message)
    }

    @Synchronized
    fun result(): WirelessEndCause = first ?: WirelessEndCause.UserStop
}
