package io.github.gcbond.openhc.core.hisight

/** HiSight 用到的三条 TCP 连接，都由车机主动连到手机。 */
enum class HiSightChannel {
    /** 控制连接（RTSP），端口由设备会话层（DMSDP）告知。 */
    CONTROL,

    /** 视频连接，端口来自 SETUP 应答。 */
    VIDEO,

    /** 回控连接（触摸、按键），端口来自 SETUP 应答；手机不给时没有这条连接。 */
    REMOTE_CONTROL,
}

/** 会话结束的原因。 */
enum class HiSightEndReason {
    /** 本机调用了 [HiSightReceiver.shutdown]。 */
    LOCAL_SHUTDOWN,

    /** 手机用 SET_PARAMETER 要求结束投屏。 */
    PEER_TEARDOWN,

    /** 手机关闭了控制连接。 */
    CONTROL_CLOSED,

    /** 视频连接结束。 */
    VIDEO_CLOSED,

    /** 回控连接被关闭。 */
    REMOTE_CONTROL_CLOSED,
}

/**
 * 会话方法的返回：要调用方执行的动作，以及通知性质的事件。列表里的顺序就是执行顺序。
 */
sealed class HiSightOutput {
    /** 把 [bytes] 原样写到 [channel] 连接（分帧和加密都已做好）。 */
    class Send(val channel: HiSightChannel, val bytes: ByteArray) : HiSightOutput()

    /**
     * 主动连接手机的 [port]（[HiSightChannel.VIDEO] 或 [HiSightChannel.REMOTE_CONTROL]），
     * 主机与控制连接相同。连上后调用 [HiSightReceiver.onVideoConnected] 或
     * [HiSightReceiver.onRemoteControlConnected]；连不上就调用 [HiSightReceiver.shutdown]。
     */
    class Connect(val channel: HiSightChannel, val port: Int) : HiSightOutput()

    /** 关闭全部 HiSight 连接。之后会话不再产生任何动作。 */
    object CloseAll : HiSightOutput()

    /**
     * 能力协商完成、手机已给出视频端口：[width]×[height]、[fps] 是手机选定的编码参数，可用来预先配置解码器；
     * 实际尺寸以码流里的 SPS 为准（镜像模式下可能变化）。[touchInput]、[keyInput]、[textInput]
     * 表示本次可以发送的输入类别。
     */
    class StreamNegotiated(
        val width: Int,
        val height: Int,
        val fps: Int,
        val touchInput: Boolean,
        val keyInput: Boolean,
        val textInput: Boolean,
    ) : HiSightOutput()

    /** 手机接受了 PLAY，随后会在视频连接上推流。 */
    object PlayAccepted : HiSightOutput()

    /** 会话已结束。总是跟在 [CloseAll] 后面，只出现一次。 */
    class Ended(val reason: HiSightEndReason) : HiSightOutput()

    /** 只用于诊断的说明（例如手机发来的格式和常见的不一样、已按宽松规则接受），写日志即可，不含密钥和地址。 */
    class Note(val text: String) : HiSightOutput() {
        override fun toString() = "Note($text)"
    }
}
