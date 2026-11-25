package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.session.EndReason
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.platform.usb.WiredEndReason

// 连接状态：会话事件、有线状态和本机操作归并成界面看的一份状态。本文件是纯逻辑，不引用安卓类，
// 由 JVM 单元测试覆盖；接线（谁在什么线程上产生这些输入）见 ConnectionController。

/** 首页显示的连接阶段。 */
enum class Phase {
    /** 没在连接：还没开始、用户点了停止，或者出了错（原因见 [ConnectionUiState.notice]）。 */
    IDLE,

    /** 正在准备：读身份资产、开控制监听。 */
    PREPARING,

    /** 等手机插上。 */
    WAITING_FOR_PHONE,

    /** 插着的手机开着 USB 调试，要用户关掉。 */
    USB_DEBUGGING_ON,

    /** 等用户在车机的 USB 授权框里点允许。 */
    WAITING_USB_PERMISSION,

    /** 已通知手机切换，等手机响应；第一次连这台车机时要在手机上点「连接」。 */
    CONFIRM_ON_PHONE,

    /** 正在连接：建网络、控制通道握手、免码回连、设备会话、投屏协商。 */
    CONNECTING,

    /** 首次配对：显示连接码，用户在手机上输入。 */
    PAIRING,

    /** 投屏中。 */
    PROJECTING,

    /** 正在断开、收尾。 */
    STOPPING,

    /**
     * 这次插入已经用过（发过 carInfo），手机不会再理车机：要拔线等 5 秒以上再插。
     * 手机拔下够久之后自动开始下一次连接（回到 [WAITING_FOR_PHONE]）。
     */
    REPLUG_REQUIRED,
}

/** 阶段里更细的一步，界面上作为一行说明。 */
enum class Step {
    USB_PERMISSION_PHONE,
    USB_PERMISSION_NETWORK,
    SENDING_CAR_INFO,
    PHONE_SWITCH,
    PHONE_SWITCH_SLOW,
    OPENING_NETWORK,
    WAITING_PHONE_LINK,
    HANDSHAKE,
    WAITING_AUTH,
    AUTHENTICATING,
    DEVICE_SESSION,
    WAITING_PROJECTION,
    NEGOTIATING,

    /** [Phase.REPLUG_REQUIRED]：手机还插着。 */
    REPLUG_UNPLUG,

    /** [Phase.REPLUG_REQUIRED]：手机已拔下，等够时间。 */
    REPLUG_WAIT,
}

/** 手机选定的投屏参数。 */
data class VideoInfo(val width: Int, val height: Int, val fps: Int, val touchInput: Boolean) {
    companion object {
        fun of(video: VideoParameters) = VideoInfo(video.width, video.height, video.fps, video.touchInput)
    }
}

/** 一次连接为什么结束。 */
sealed class EndCause {
    /** 用户点了停止（或服务被系统结束）。 */
    object UserStop : EndCause() {
        override fun toString() = "已停止"
    }

    /** 有线链路先结束（手机拔掉、USB 授权被拒等）。 */
    data class Wired(val reason: WiredEndReason, val detail: String?) : EndCause()

    /** 协议会话先结束（手机结束投屏、认证失败等）。[reason] 的 toString 不含密钥和设备标识。 */
    class Session(val reason: EndReason) : EndCause() {
        override fun toString() = "Session($reason)"
    }

    /** 程序自己的问题（执行器意外退出、生成 carInfo 失败等）。 */
    data class Failure(val message: String) : EndCause()
}

/** 首页要提示给用户的问题或上次结束的原因。 */
sealed class Notice {
    object IdentityMissing : Notice() {
        override fun toString() = "IdentityMissing"
    }

    data class IdentityInvalid(val reason: String) : Notice()

    /** 要用户在系统框里同意 VPN（有线连接要一块只给本应用用的虚拟网卡）。 */
    object VpnConsentRequired : Notice() {
        override fun toString() = "VpnConsentRequired"
    }

    data class VpnUnavailable(val reason: String) : Notice()

    /** 配对记录文件损坏或版本不认识：不当成空库，要用户确认清除。 */
    data class TrustStoreCorrupt(val reason: String) : Notice()

    data class TrustStoreUnavailable(val reason: String) : Notice()

    data class StartFailed(val reason: String) : Notice()

    /** 上一次连接结束的原因。 */
    data class Ended(val cause: EndCause) : Notice()
}

/**
 * 有线状态（platform 的 WiredState）在应用里的对应。先在接线处映射过来再归并：
 * 平台层「已连通」状态的构造不对外，测试里造不出来。
 */
sealed class WiredProgress {
    object WaitingForPhone : WiredProgress()
    object UsbDebuggingOn : WiredProgress()
    data class WaitingForUsbPermission(val forNetwork: Boolean) : WiredProgress()
    object SendingCarInfo : WiredProgress()
    data class WaitingForPhoneSwitch(val slow: Boolean) : WiredProgress()
    object OpeningNetwork : WiredProgress()
    object NetworkReady : WiredProgress()
    data class Ended(val reason: WiredEndReason, val replugRequired: Boolean, val detail: String?) : WiredProgress()
}

/** 状态归并的输入。 */
sealed class ConnectionSignal {
    /** 开始第 [attempt] 次连接（按钮、插线，或拔线重插后自动）。 */
    data class AttemptStarted(val attempt: Int) : ConnectionSignal()

    /** 开始不了（缺身份资产、没有 VPN 授权等），回到空闲。 */
    data class Blocked(val notice: Notice) : ConnectionSignal()

    /** 等拔线重插：[unplugged] 表示手机已经拔下、在等够时间。 */
    data class ReplugWaiting(val unplugged: Boolean) : ConnectionSignal()

    /** 用户要停止。 */
    object StopRequested : ConnectionSignal()

    data class Wired(val progress: WiredProgress) : ConnectionSignal()

    class Session(val event: SessionEvent) : ConnectionSignal()

    /** 解码器报的画面尺寸（以码流为准，可能和协商值不同）。 */
    data class VideoSize(val width: Int, val height: Int) : ConnectionSignal()

    /** 解码器建不起来（没有能用的 H.264 解码器等），画面会一直黑着。 */
    data class DecoderProblem(val detail: String) : ConnectionSignal()

    /**
     * 这次连接已彻底收尾。[replugRequired]：这次插入发过 carInfo；[keepConnecting]：用户还要连接，
     * 拔线重插后自动再来。
     */
    data class AttemptFinished(
        val cause: EndCause,
        val replugRequired: Boolean,
        val keepConnecting: Boolean,
    ) : ConnectionSignal()
}

/** 界面看的连接状态。 */
data class ConnectionUiState(
    val phase: Phase = Phase.IDLE,
    val step: Step? = null,
    /** 首次配对的 6 位连接码；只在 [Phase.PAIRING] 收到之后有值。 */
    val pinCode: String? = null,
    /** 手机已经连上控制通道：之后有线状态的中间变化不再影响阶段。 */
    val phoneConnected: Boolean = false,
    val video: VideoInfo? = null,
    /** 已收到第一帧。 */
    val firstFrame: Boolean = false,
    /** 回控已就绪（手机接受了 PLAY）且手机接受触摸：可以把触摸发给手机。 */
    val inputReady: Boolean = false,
    /** 解码器报的画面尺寸，0 表示还不知道。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val decoderProblem: String? = null,
    val notice: Notice? = null,
    /** 第几次连接（从 1 开始；0 表示还没开始过）。 */
    val attempt: Int = 0,
    /** 用户要求保持连接：从开始到停止（含等拔线重插）都是 true。 */
    val active: Boolean = false,
) {
    /** 不输出连接码。 */
    override fun toString(): String =
        "ConnectionUiState(phase=$phase, step=$step, attempt=$attempt, active=$active, video=$video, " +
            "firstFrame=$firstFrame, inputReady=$inputReady, notice=$notice, 连接码=${if (pinCode != null) "有" else "无"})"
}

/** 状态归并：纯函数。 */
object ConnectionReducer {

    /** 有正在进行的一次连接的阶段（[Phase.STOPPING] 也算，它在等收尾）。 */
    private val ATTEMPT_PHASES = setOf(
        Phase.PREPARING, Phase.WAITING_FOR_PHONE, Phase.USB_DEBUGGING_ON, Phase.WAITING_USB_PERMISSION,
        Phase.CONFIRM_ON_PHONE, Phase.CONNECTING, Phase.PAIRING, Phase.PROJECTING, Phase.STOPPING,
    )

    fun reduce(state: ConnectionUiState, signal: ConnectionSignal): ConnectionUiState = when (signal) {
        is ConnectionSignal.AttemptStarted ->
            ConnectionUiState(phase = Phase.PREPARING, attempt = signal.attempt, active = true)

        is ConnectionSignal.Blocked ->
            ConnectionUiState(phase = Phase.IDLE, notice = signal.notice, attempt = state.attempt, active = false)

        is ConnectionSignal.ReplugWaiting -> ConnectionUiState(
            phase = Phase.REPLUG_REQUIRED,
            step = if (signal.unplugged) Step.REPLUG_WAIT else Step.REPLUG_UNPLUG,
            notice = state.notice,
            attempt = state.attempt,
            active = true,
        )

        ConnectionSignal.StopRequested -> when (state.phase) {
            Phase.IDLE -> state
            Phase.REPLUG_REQUIRED -> ConnectionUiState(phase = Phase.IDLE, attempt = state.attempt, active = false)
            else -> state.copy(phase = Phase.STOPPING, step = null, pinCode = null, inputReady = false)
        }

        is ConnectionSignal.Wired -> onWired(state, signal.progress)

        is ConnectionSignal.Session -> onSession(state, signal.event)

        is ConnectionSignal.VideoSize ->
            if (state.phase in ATTEMPT_PHASES) state.copy(videoWidth = signal.width, videoHeight = signal.height) else state

        is ConnectionSignal.DecoderProblem ->
            if (state.phase in ATTEMPT_PHASES) state.copy(decoderProblem = signal.detail) else state

        is ConnectionSignal.AttemptFinished -> onFinished(state, signal)
    }

    private fun onWired(state: ConnectionUiState, progress: WiredProgress): ConnectionUiState {
        // 正在断开时只等收尾；空闲、等重插时没有有线会话，迟到的状态不理。
        if (state.phase !in ATTEMPT_PHASES || state.phase == Phase.STOPPING) return state
        if (progress is WiredProgress.Ended) return stopping(state)
        // 手机连上控制通道之后，进度由会话事件决定。
        if (state.phoneConnected) return state
        return when (progress) {
            WiredProgress.WaitingForPhone -> state.copy(phase = Phase.WAITING_FOR_PHONE, step = null)
            WiredProgress.UsbDebuggingOn -> state.copy(phase = Phase.USB_DEBUGGING_ON, step = null)
            is WiredProgress.WaitingForUsbPermission -> state.copy(
                phase = Phase.WAITING_USB_PERMISSION,
                step = if (progress.forNetwork) Step.USB_PERMISSION_NETWORK else Step.USB_PERMISSION_PHONE,
            )
            WiredProgress.SendingCarInfo -> state.copy(phase = Phase.CONNECTING, step = Step.SENDING_CAR_INFO)
            is WiredProgress.WaitingForPhoneSwitch -> state.copy(
                phase = Phase.CONFIRM_ON_PHONE,
                step = if (progress.slow) Step.PHONE_SWITCH_SLOW else Step.PHONE_SWITCH,
            )
            WiredProgress.OpeningNetwork -> state.copy(phase = Phase.CONNECTING, step = Step.OPENING_NETWORK)
            WiredProgress.NetworkReady -> state.copy(phase = Phase.CONNECTING, step = Step.WAITING_PHONE_LINK)
            is WiredProgress.Ended -> stopping(state)
        }
    }

    private fun onSession(state: ConnectionUiState, event: SessionEvent): ConnectionUiState {
        if (state.phase !in ATTEMPT_PHASES || state.phase == Phase.STOPPING) return state
        return when (event) {
            is SessionEvent.ControlListening -> state
            is SessionEvent.PhoneConnected ->
                state.copy(phase = Phase.CONNECTING, step = Step.HANDSHAKE, phoneConnected = true)
            is SessionEvent.ControlHandshakeCompleted -> state.copy(phase = Phase.CONNECTING, step = Step.WAITING_AUTH)
            is SessionEvent.AuthStarted -> if (event.mode == AuthMode.PIN_PAIRING) {
                state.copy(phase = Phase.PAIRING, step = null)
            } else {
                state.copy(phase = Phase.CONNECTING, step = Step.AUTHENTICATING)
            }
            is SessionEvent.ShowPinCode -> state.copy(phase = Phase.PAIRING, step = null, pinCode = event.pinCode)
            is SessionEvent.Authenticated ->
                state.copy(phase = Phase.CONNECTING, step = Step.DEVICE_SESSION, pinCode = null)
            is SessionEvent.DeviceSessionReady -> state.copy(phase = Phase.CONNECTING, step = Step.WAITING_PROJECTION)
            is SessionEvent.ProjectionRequested -> state.copy(phase = Phase.CONNECTING, step = Step.NEGOTIATING)
            is SessionEvent.VideoNegotiated ->
                state.copy(phase = Phase.PROJECTING, step = null, pinCode = null, video = VideoInfo.of(event.video))
            // PLAY 只在视频和回控两条连接都连上后才发，所以手机接受了 PLAY 就说明回控已就绪。
            SessionEvent.PlayAccepted -> state.copy(inputReady = state.video?.touchInput == true)
            is SessionEvent.VideoStarted -> state.copy(
                phase = Phase.PROJECTING,
                step = null,
                firstFrame = true,
                video = event.video?.let(VideoInfo::of) ?: state.video,
            )
            is SessionEvent.Diagnostic -> state
            is SessionEvent.MediaMetadataUpdated -> state
            // 原厂吉利对齐的新事件（拉回前台 / 导航焦点 / 通话与语音状态）由控制器与桥接层处理，连接状态不变。
            is SessionEvent.ForegroundLaunchRequested -> state
            is SessionEvent.NaviFocusRequested -> state
            is SessionEvent.PhoneCallStateChanged -> state
            is SessionEvent.PhoneVoiceStateChanged -> state
            // 回车机桌面由界面层处理（CarHome），连接状态不变。
            SessionEvent.ProjectionPaused -> state
            is SessionEvent.Ended -> stopping(state)
        }
    }

    private fun stopping(state: ConnectionUiState) =
        state.copy(phase = Phase.STOPPING, step = null, pinCode = null, inputReady = false)

    private fun onFinished(state: ConnectionUiState, signal: ConnectionSignal.AttemptFinished): ConnectionUiState {
        val cause = signal.cause
        val userStopped = cause is EndCause.UserStop
        val next = if (!userStopped && signal.replugRequired && signal.keepConnecting) Phase.REPLUG_REQUIRED else Phase.IDLE
        val notice = when {
            userStopped -> null
            cause is EndCause.Wired && cause.reason == WiredEndReason.VPN_CONSENT_REQUIRED -> Notice.VpnConsentRequired
            else -> Notice.Ended(cause)
        }
        return ConnectionUiState(
            phase = next,
            step = if (next == Phase.REPLUG_REQUIRED) Step.REPLUG_UNPLUG else null,
            notice = notice,
            attempt = state.attempt,
            active = next == Phase.REPLUG_REQUIRED,
        )
    }
}

/**
 * 记下一次连接**最先**结束的原因。两头（协议会话、有线链路）谁先结束都会带走另一头，
 * 被带走的那一头报的是「本机停止」或 STOPPED，不能盖掉真正的原因。线程安全。
 */
class EndCauseTracker {
    private var first: EndCause? = null

    @Synchronized
    fun onUserStop() {
        if (first == null) first = EndCause.UserStop
    }

    @Synchronized
    fun onSessionEnded(reason: EndReason) {
        if (first == null && reason !is EndReason.LocalStop) first = EndCause.Session(reason)
    }

    @Synchronized
    fun onWiredEnded(reason: WiredEndReason, detail: String?) {
        if (first == null && reason != WiredEndReason.STOPPED) first = EndCause.Wired(reason, detail)
    }

    @Synchronized
    fun onFailure(message: String) {
        if (first == null) first = EndCause.Failure(message)
    }

    /** 最先的原因；两头都是被本机停掉的（没有别的原因）时算用户停止。 */
    @Synchronized
    fun result(): EndCause = first ?: EndCause.UserStop
}
