package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.dmsdp.DmsdpMediaData
import io.github.gcbond.openhc.core.dmsdp.KeepAliveMode
import io.github.gcbond.openhc.core.hisight.HiSightEndReason

/**
 * [ReceiverSession] 每个方法的返回：要执行的 I/O 动作（[SessionAction]）和通知性质的事件（[SessionEvent]），
 * 按产生顺序混排在同一个列表里，**按顺序处理**。
 */
sealed interface SessionOutput

/** 一次连接里的各条通道。监听用它所接受的连接的通道表示。 */
enum class SessionChannel(val label: String) {
    /** 控制通道：手机连进来的第一条 TCP（nearby 帧）。 */
    CONTROL("控制通道"),

    /** 认证 TCP（业务 19），手机连车机。 */
    AUTH("认证连接"),

    /** 设备会话 TCP（业务 20），手机连车机，只做首轮协商。 */
    DMSDP_SESSION("设备会话连接"),

    /** 设备会话的控制 TCP，手机连车机另开的监听。 */
    DMSDP_CONTROL("设备控制连接"),

    /** 设备会话的 UDP 保活，车机连手机给的端口。 */
    KEEPALIVE("UDP 保活"),

    /** 投屏控制 TCP（RTSP），车机连手机。 */
    HISIGHT_CONTROL("投屏控制连接"),

    /** 视频 TCP，车机连手机。 */
    VIDEO("视频连接"),

    /** 回控 TCP（触摸、按键），车机连手机。 */
    REMOTE_CONTROL("回控连接"),

    /** 音频数据 TCP：手机连车机为音频数据会话开的数据端口（SETUP 里 client_port=P--1 的 P）。 */
    AUDIO("音频数据连接"),

    /** 麦克风数据 TCP：手机连车机为麦克风 SETUP 开的端口；车机往上写声音。 */
    MIC("麦克风数据连接"),
}

/** 出事件或出错的那一层。 */
enum class SessionLayer { NEARBY, AUTH, DMSDP, HISIGHT, SESSION }

/**
 * 要调用方（例如 [SocketRunner]）去做的 I/O。监听、连接和 UDP socket 都用编排器分配的 [id] 标识，
 * 同一个会话里 id 不重复；执行结果按 [SessionLogic] 里对应的方法交回。
 */
sealed class SessionAction : SessionOutput {
    abstract val id: Int

    /**
     * 在 [host]:[port] 上开 TCP 监听（[port] 为 0 时由系统分配），开好后调 [SessionLogic.onListening]
     * 交回实际端口；开不了调 [SessionLogic.onIoError]。
     *
     * 每个监听**只接受一条连接**，这条连接的 id 是 [acceptId]，接受后调 [SessionLogic.onAccepted]。
     * 之后不再 accept，但监听保持打开，直到 [Close]：手机重复请求同一业务时还会拿到这个端口。
     * [requireLocalHost] 不为 null 时，本端地址不是它的连接（从别的网卡连进来的）直接关掉、不算数，继续等。
     */
    class Listen(
        override val id: Int,
        val channel: SessionChannel,
        val host: String,
        val port: Int,
        val acceptId: Int,
        val requireLocalHost: String? = null,
    ) : SessionAction() {
        override fun toString() = "Listen($id, ${channel.name}, $host:$port, 接受为 $acceptId)"
    }

    /** 主动连接 [host]:[port] 作为连接 [id]：连上调 [SessionLogic.onConnected]，连不上调 [SessionLogic.onIoError]。 */
    class Connect(override val id: Int, val channel: SessionChannel, val host: String, val port: Int) : SessionAction() {
        override fun toString() = "Connect($id, ${channel.name}, $host:$port)"
    }

    /** 把 [bytes] 原样写到连接 [id]（已是完整的线上字节，不要修改数组）。写失败调 [SessionLogic.onIoError]。 */
    class Write(override val id: Int, val bytes: ByteArray) : SessionAction() {
        override fun toString() = "Write($id, ${bytes.size} 字节)"
    }

    /** 关闭 [id] 对应的监听、连接或 UDP socket。之后它的数据和错误都不用再交回。 */
    class Close(override val id: Int) : SessionAction() {
        override fun toString() = "Close($id)"
    }

    /**
     * 开 UDP socket [id]：绑 [localHost] 的临时端口，再 connect 到 [remoteHost]:[remotePort]（本机端口不用告诉手机）。
     * 收到的数据报交 [SessionLogic.onDatagram]；开不了或收发出错调 [SessionLogic.onIoError]。
     */
    class OpenUdp(
        override val id: Int,
        val channel: SessionChannel,
        val localHost: String,
        val remoteHost: String,
        val remotePort: Int,
    ) : SessionAction() {
        override fun toString() = "OpenUdp($id, ${channel.name}, $localHost → $remoteHost:$remotePort)"
    }

    /**
     * 开 UDP socket [id]：绑 [localHost]:[localPort]，不 connect，收任意来源的数据报（音频数据会话走 UDP 时手机往这里发）。
     * 绑好后调 [SessionLogic.onListening]（端口是实际绑上的）；收到的数据报交 [SessionLogic.onDatagram]；
     * 绑不上或收发出错调 [SessionLogic.onIoError]。
     */
    class BindUdp(
        override val id: Int,
        val channel: SessionChannel,
        val localHost: String,
        val localPort: Int,
    ) : SessionAction() {
        override fun toString() = "BindUdp($id, ${channel.name}, $localHost:$localPort)"
    }

    /** 经 UDP socket [id] 发一个数据报（已是完整的线上字节）。 */
    class SendDatagram(override val id: Int, val bytes: ByteArray) : SessionAction() {
        override fun toString() = "SendDatagram($id, ${bytes.size} 字节)"
    }
}

/** 手机选定的投屏参数。实际画面尺寸以码流里的 SPS 为准。 */
class VideoParameters(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** 本次可以发触摸。 */
    val touchInput: Boolean,
    /** 本次可以发物理按键。 */
    val keyInput: Boolean,
    /** 本次可以发成文输入（实验）。 */
    val textInput: Boolean,
) {
    override fun toString() = "${width}x$height@$fps，触摸${if (touchInput) "开" else "关"}，按键${if (keyInput) "开" else "关"}${if (textInput) "，成文输入开" else ""}"
}

/**
 * 会话事件，界面和日志用。toString 不含密钥、连接码和设备标识，可以直接打印。
 * 大致按出现的先后排列。
 */
sealed class SessionEvent : SessionOutput {

    /** 控制监听已经开好：现在可以发 carInfo，让手机切成 USB 网卡来连。 */
    class ControlListening(val port: Int) : SessionEvent() {
        override fun toString() = "ControlListening($port)"
    }

    /** 手机连上了控制通道，一次连接正式开始。[phoneIp] 是手机在 USB 网卡上的地址。 */
    class PhoneConnected(val phoneIp: String) : SessionEvent() {
        override fun toString() = "PhoneConnected($phoneIp)"
    }

    /** 控制通道握手完成；[encrypted] 表示之后的控制帧是否加密。 */
    class ControlHandshakeCompleted(val encrypted: Boolean) : SessionEvent() {
        override fun toString() = "ControlHandshakeCompleted(加密=$encrypted)"
    }

    /** 已回应手机的认证握手，开始认证：[mode] 是首次配对还是免码回连。 */
    class AuthStarted(val mode: AuthMode) : SessionEvent() {
        override fun toString() = "AuthStarted($mode)"
    }

    /** 首次配对：把 6 位连接码显示给用户，用户在手机上输入。 */
    class ShowPinCode(val pinCode: String) : SessionEvent() {
        override fun toString() = "ShowPinCode(内容不显示)"
    }

    /** 认证成功，会话密钥已交给后面几层（不经过事件）。 */
    class Authenticated(val mode: AuthMode) : SessionEvent() {
        override fun toString() = "Authenticated($mode)"
    }

    /** 设备会话的控制连接已切到加密，开始保活；[keepAlive] 是手机选的保活方式。 */
    class DeviceSessionReady(val keepAlive: KeepAliveMode) : SessionEvent() {
        override fun toString() = "DeviceSessionReady($keepAlive)"
    }

    /** 手机通告了投屏控制端口，开始连投屏。 */
    class ProjectionRequested(val port: Int) : SessionEvent() {
        override fun toString() = "ProjectionRequested($port)"
    }

    /** 投屏参数协商完成（SETUP 应答），可以预先配置解码器。 */
    class VideoNegotiated(val video: VideoParameters) : SessionEvent() {
        override fun toString() = "VideoNegotiated($video)"
    }

    /** 手机接受了 PLAY，随后会在视频连接上推流。 */
    object PlayAccepted : SessionEvent() {
        override fun toString() = "PlayAccepted"
    }

    /** 收到第一帧视频：开始出画面。帧本身经 [io.github.gcbond.openhc.core.hisight.AccessUnitSink] 交出。 */
    class VideoStarted(val video: VideoParameters?) : SessionEvent() {
        override fun toString() = "VideoStarted($video)"
    }

    /**
     * 手机暂停了投屏显示服务（设备服务状态通知里显示服务的「暂停」）：用户在投屏界面上点了「车机」图标，要回车机自己的桌面。
     * 连接照常保持；车机界面在前台时应退回桌面。刚连上时手机可能也发一次（启动竞态），由界面层按时间过滤。
     */
    object ProjectionPaused : SessionEvent() {
        override fun toString() = "ProjectionPaused"
    }

    /** 手机推送了 515 音频元数据（全量曲目信息或每秒播放进度）。 */
    class MediaMetadataUpdated(val metadata: DmsdpMediaData) : SessionEvent() {
        override fun toString() = "MediaMetadataUpdated($metadata)"
    }

    /** 不影响会话继续的信息：各层没处理的消息、被丢弃的数据、被忽略的请求等。[message] 可以直接写日志。 */
    class Diagnostic(val layer: SessionLayer, val message: String) : SessionEvent() {
        override fun toString() = "Diagnostic($layer: $message)"
    }

    /** 会话结束。只出现一次，总在最后；之前已给出关闭全部资源的 [SessionAction.Close]。 */
    class Ended(val reason: EndReason) : SessionEvent() {
        override fun toString() = "Ended($reason)"
    }
}

/** 会话结束的原因。toString 不含密钥和设备标识。 */
sealed class EndReason {
    /** 本机调用了 [ReceiverSession.stop]。 */
    object LocalStop : EndReason() {
        override fun toString() = "本机停止"
    }

    /** 控制通道连上后 5 秒内握手没完成。 */
    object ControlHandshakeTimeout : EndReason() {
        override fun toString() = "控制通道握手超时"
    }

    class AuthFailed(val failure: AuthFailure) : EndReason() {
        override fun toString() = "认证失败（$failure）"
    }

    /** 某一层收到不合法的数据，或手机发来的消息顺序不对。 */
    class ProtocolError(val layer: SessionLayer, val message: String) : EndReason() {
        override fun toString() = "协议错误（$layer：$message）"
    }

    /** 手机在会话还需要这条连接时把它关了。 */
    class PeerClosed(val channel: SessionChannel, val detail: String) : EndReason() {
        override fun toString() = "手机关闭了${channel.label}（$detail）"
    }

    /** 监听、连接或收发失败。 */
    class NetworkError(val channel: SessionChannel, val message: String) : EndReason() {
        override fun toString() = "${channel.label}网络错误（$message）"
    }

    class KeepAliveTimeout(val mode: KeepAliveMode) : EndReason() {
        override fun toString() = "设备会话保活超时（$mode）"
    }

    /** 手机要求退出投屏回车机桌面（HiCar 应用列表的车辆磁贴，识别规则见 dmsdp 的 ManagementMessages）。 */
    class CarDesktopRequested(val detail: String) : EndReason() {
        override fun toString() = "手机请求回车机桌面（$detail）"
    }

    /** 投屏层结束了会话（手机要求结束、视频或回控连接被关等），整个连接随之结束。 */
    class ProjectionEnded(val reason: HiSightEndReason) : EndReason() {
        override fun toString() = "投屏结束（$reason）"
    }

    /** 视频回调抛了异常。 */
    class VideoSinkFailed(val message: String) : EndReason() {
        override fun toString() = "视频输出失败（$message）"
    }

    /** 编排器自己出了不该出的错（程序缺陷），已尽量按正常顺序收尾。 */
    class InternalError(val message: String) : EndReason() {
        override fun toString() = "内部错误（$message）"
    }
}
