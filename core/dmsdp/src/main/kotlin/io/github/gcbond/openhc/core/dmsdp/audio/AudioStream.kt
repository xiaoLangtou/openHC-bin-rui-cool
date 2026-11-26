package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.common.toHex

/**
 * 车机在 SETUP 里提议的传输方式。手机按 `Fillp` 和 `TCP` 两个头部选：
 * `Fillp: 1` → FillP；`Fillp: 0` 且带 `TCP: 1` → TCP；`Fillp: 0` 且没有 `TCP` 头 → UDP。
 */
enum class AudioTransport(val label: String) {
    TCP("TCP"),

    /** 手机侧已知会在建发送端时失败（不回 SETUP 的 200，过几秒拆掉再触发）。 */
    UDP("UDP"),

    /** 本机没有实现 FillP 协议本身，只能用来对照手机的反应。 */
    FILLP("FillP"),
}

/** SETUP 里的 `Codec`：0 是不压缩的 PCM，1 是压缩（按 AAC-LC 解码，每包 1024 个采样）。 */
enum class AudioCodec(val wire: Int, val label: String) {
    PCM(0, "PCM"),
    AAC(1, "AAC"),
}

/** 16 位 PCM 的字节序。判断出来之前是 [UNDECIDED]，播放时按小端处理。 */
enum class PcmByteOrder(val label: String) {
    UNDECIDED("未判断"),
    LITTLE_ENDIAN("小端"),
    BIG_ENDIAN("大端"),
}

/** 一次音频数据会话的参数：前几项来自手机触发消息里的 `ParaAudio`，后两项是车机在 SETUP 里写的。 */
class AudioStreamInfo(
    /** 手机给的 `DataSessionID`，原样文本。 */
    val dataSessionId: String,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    /** 手机在 `ParaAudio` 里写的 Codec；没写或不是数字时为 null。 */
    val requestedCodec: Int?,
    /** 车机要求的编码（扬声器能力和 SETUP）。手机实际发的以载荷判断为准，见 [AudioPacket.codec]。 */
    val codec: AudioCodec,
    val transport: AudioTransport,
    /** `ParaAudio` 原文（只有采样参数，不含标识）。 */
    val parameters: String,
) {
    companion object {
        /** 控制连接上的声音（不走音频数据连接）在播放端用的「数据会话」号（第二路起加后缀）；真正的数据会话号是数字，不会撞。 */
        const val CONTROL_SESSION_ID = "control"
    }

    override fun toString() =
        "数据会话 $dataSessionId（${sampleRate}Hz / $channels 声道 / $bitsPerSample 位，编码 ${codec.label}，${transport.label}）"
}

/**
 * 拆出的一包音频：一个 RTP 包的载荷（已按识别结果解密）。
 *
 * 实例和 [data] 都会复用，只在回调期间有效；要留到回调之后用，自己 [copyPayload]。
 */
class AudioPacket internal constructor(stream: AudioStreamInfo) {
    var stream: AudioStreamInfo = stream
        internal set
    var sequence: Int = 0
        internal set
    var timestamp: Long = 0
        internal set
    var ssrc: Long = 0
        internal set
    var payloadType: Int = 0
        internal set
    var marker: Boolean = false
        internal set
    var data: ByteArray = EMPTY
        internal set
    var offset: Int = 0
        internal set
    var length: Int = 0
        internal set

    /** 只对 PCM 有意义。判断出来之前是 [PcmByteOrder.UNDECIDED]（按小端放）。 */
    var byteOrder: PcmByteOrder = PcmByteOrder.UNDECIDED
        internal set

    /**
     * 载荷能不能放：PCM 的前几包看起来像加密数据那样的噪声时为 false（识别时定下，之后不变），
     * 这时不要播放，免得放出刺耳的噪声。AAC 没法这样判断，一律为 true。
     */
    var playable: Boolean = true
        internal set

    /** 这包载荷实际的编码：识别时按载荷判断（以 ADTS 头开头是 AAC，否则 PCM），不看车机要的是什么。 */
    var codec: AudioCodec = stream.codec
        internal set

    /** 路号：[lane] 减 1000（见 [AudioBus.ofLane]）；不在约定范围时为 [AudioBus.UNKNOWN]。 */
    var bus: Int = AudioBus.UNKNOWN
        internal set

    /**
     * 这一路声音的编号（见 [AudioBus.lane]）：同一个值的包是同一路，放进同一个播放器。SSRC 会在同一路里变（用途、附加
     * 标志位；带包尾路号时连低 12 位也可能变），所以别拿 SSRC 分播放器。
     */
    var lane: Long = 0L
        internal set

    /** 用途号（SSRC 高 16 位，安卓 `USAGE_*` 的取值，例如通话 2、铃声 6）；0 表示没带。 */
    var usage: Int = 0
        internal set

    /** 该放到哪个出声通道（见 [AudioRouter] 的规则）。同一路的通道可能中途改变（例如第 0 路从导航换成语音助手）。 */
    var route: AudioRoute = AudioRoute.MEDIA
        internal set

    fun copyPayload(): ByteArray = data.copyOfRange(offset, offset + length)

    override fun toString() =
        "AudioPacket(${AudioBus.label(bus)}，用途 $usage，${route.label}，序号 $sequence，PT $payloadType，$length 字节)"

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * 分帧识别的结论：TCP 字节流怎么切成帧、每帧怎么解密成 RTP、RTP 载荷还要不要再解一层。
 * toString 是一行中文，可以直接写日志。
 */
class AudioFraming internal constructor(
    /** 帧头格式，例如「4 字节大端长度」。 */
    val frame: String,
    /** 整帧是 `IV16 ‖ AES-128-CTR 密文`，解开才是 RTP。 */
    val frameEncrypted: Boolean,
    /** RTP 载荷本身又是 `IV16 ‖ 密文`。 */
    val payloadEncrypted: Boolean,
    /** 按载荷判断的实际编码（以 ADTS 头开头是 AAC，否则 PCM）。 */
    val payloadCodec: AudioCodec,
    /** 第一个 RTP 包之前跳过的、帧头合法但内容不是 RTP 的帧数。 */
    val leadingFrames: Int,
    val payloadType: Int,
    /** 认出分帧时用的那一路的 SSRC（一条连接上可能有几路，见 [AudioBus]）。 */
    val ssrc: Long,
    /** 第一个 RTP 包头部标志的原值（CSRC 数、扩展头、填充），扩展头长度按 RFC 3550 的 32 位字解释。 */
    val rtpHeader: String,
    /** 识别时用到的前几包的载荷长度。 */
    val payloadLengths: List<Int>,
    /** 见 [AudioPacket.playable]。 */
    val playable: Boolean,
    head: ByteArray,
    payloadHead: ByteArray,
) {
    private val headBytes = head.copyOf()
    private val payloadHeadBytes = payloadHead.copyOf()

    /** 连接上最早的至多 32 个原始字节。 */
    val head: ByteArray get() = headBytes.copyOf()

    /** 第一包载荷（解密后）的前至多 32 字节。 */
    val payloadHead: ByteArray get() = payloadHeadBytes.copyOf()

    override fun toString(): String {
        val layers = (if (frameEncrypted) "整帧 IV16+密文，解开是 RTP" else "帧内直接是 RTP") +
            (if (payloadEncrypted) "，RTP 载荷又是 IV16+密文" else "，RTP 载荷不再加密")
        val leading = if (leadingFrames > 0) "，开头跳过 $leadingFrames 帧非 RTP" else ""
        return "分帧=$frame（$layers$leading），载荷 ${payloadCodec.label}，PT=$payloadType，" +
            "SSRC=$ssrc（${AudioBus.label(AudioBus.of(ssrc))}），RTP 头 $rtpHeader，" +
            "前几包载荷 ${payloadLengths.joinToString("/")} 字节，可播放=${if (playable) "是" else "否（像噪声，静音）"}，" +
            "原始前 ${headBytes.size} 字节=${headBytes.toHex()}，载荷前 ${payloadHeadBytes.size} 字节=${payloadHeadBytes.toHex()}"
    }
}

/** 一次数据会话的统计，在会话结束时随 [AudioEvent.Ended] 给出，toString 是一行汇总。 */
class AudioSummary internal constructor(
    val dataSessionId: String,
    /** 发出 SETUP 时用的数据端口；0 表示没有发出 SETUP。 */
    val setupPort: Int,
    /** 手机对 SETUP 应答的状态行；null 表示没有应答。 */
    val setupAnswer: String?,
    val dataConnected: Boolean,
    val dataBytes: Long,
    /** 数据端口上的分帧结论（一句话）。 */
    val framing: String,
    val packets: Long,
    val rtcpPackets: Long,
    /** 各路的序号跳变之和（每一路各自按序号判断）。 */
    val sequenceGaps: Long,
    val badFrames: Long,
    val byteOrder: PcmByteOrder,
    /** 各路收到的包数，例如「第 8 路 1200 包、第 0 路 56 包」。 */
    val streams: String,
) {
    override fun toString(): String {
        val setup = if (setupPort == 0) "SETUP 没发出" else "SETUP 已发（端口 $setupPort）"
        val answer = setupAnswer ?: "手机没有应答 SETUP"
        return "音频汇总：会话 $dataSessionId｜$setup｜$answer｜手机连入数据端口${yesNo(dataConnected)}｜" +
            "收到 $dataBytes 字节｜$framing｜音频包 $packets（${streams.ifEmpty { "没有分路" }}；RTCP $rtcpPackets，" +
            "序号跳变 $sequenceGaps，坏帧 $badFrames）｜PCM 字节序 ${byteOrder.label}"
    }

    private fun yesNo(value: Boolean) = if (value) "是" else "否"
}

/** 一次数据会话为什么结束。 */
enum class AudioEndReason(val label: String) {
    PEER_TEARDOWN("手机拆除（TEARDOWN）"),
    REPLACED("手机开了新的数据会话"),
    LISTEN_FAILED("车机开不了端口"),
    SESSION_CLOSED("整个连接结束"),
}

/** 音频数据会话的状态变化和诊断。toString 是一行中文，可以直接写日志；不含密钥和设备标识。 */
sealed class AudioEvent {

    /**
     * 手机的音频焦点消息（设备消息 module 4，格式见 [PhoneAudioFocus]）。手机要出声前先发「要焦点」
     * （例如 type 1、数据 `"1"`：要长期焦点放媒体），放完发「放弃焦点」；分流时导航那一路另有 type 14 的消息。
     */
    class PhoneFocus(val message: PhoneAudioFocus) : AudioEvent() {
        override fun toString() = message.toString()
    }

    /** 这次连接向手机声明的扬声器能力定下来了（2102 的形状由它决定）。 */
    class SpeakerDeclared(val plan: SpeakerPlan) : AudioEvent() {
        override fun toString() = plan.toString()
    }

    /** 已经把车机这边的焦点变化告诉手机（module 4，type 见 [CarAudioFocus]）。 */
    class CarFocusSent(val change: CarAudioFocus) : AudioEvent() {
        override fun toString() = "已告诉手机：$change"
    }

    /**
     * 声明 PCM 时这台手机的表现（见 [PcmVerdict]）。应用按 [phone] 记下来：失败的手机下次连接改声明 AAC。
     * [phone] 为 null 表示没收到手机的设备信息、认不出是哪台。
     */
    /**
     * 手机不认 TCP 方式的数据会话：这次连接之后的数据会话改用 UDP，应用应记下这台手机（下次连接直接用 UDP）。
     * [phone] 认不出时为 null；[reason] 是看出来的依据（[NO_ANSWER]、[ACCEPTED_NO_CONNECT] 或 [CONTROL_INSTEAD]）。
     */
    class TransportFallback(val phone: PhoneRef?, val reason: String = NO_ANSWER) : AudioEvent() {
        override fun toString() = "手机（${phone ?: "认不出是哪台手机"}）$reason，之后的数据会话改用 UDP"

        companion object {
            const val NO_ANSWER = "不应答 TCP 方式的 SETUP"
            const val ACCEPTED_NO_CONNECT = "应答了 TCP 方式的 SETUP、却好几次都不连数据端口"
            const val CONTROL_INSTEAD = "应答了 TCP 方式的 SETUP、却不连数据端口，把声音改从控制连接送"
        }
    }

    /**
     * 手机在 UDP 方式下同样不应答 SETUP（例如曾因偶发超时被误记入 UDP 名单）：这次连接之后的数据会话恢复使用 TCP，
     * 应用应忘掉这台手机的 UDP 记录。
     */
    class TransportRecovered(val phone: PhoneRef?, val reason: String = UDP_NO_ANSWER) : AudioEvent() {
        override fun toString() = "手机（${phone ?: "认不出是哪台手机"}）$reason，之后的数据会话恢复使用 TCP"

        companion object {
            const val UDP_NO_ANSWER = "不应答 UDP 方式的 SETUP"
        }
    }

    class CodecVerdict(val phone: PhoneRef?, val verdict: PcmVerdict) : AudioEvent() {
        override fun toString() = "PCM 判定（${phone ?: "认不出是哪台手机"}）：${verdict.label}" +
            if (verdict.failure) "，下次连接这台手机可能改用 AAC" else ""
    }

    /** 手机触发了数据会话（OPTIONS），已回 200，接着开端口、发 SETUP。 */
    class Requested(val stream: AudioStreamInfo) : AudioEvent() {
        override fun toString() = "手机要开声音：$stream，ParaAudio=${stream.parameters}"
    }

    /** 数据端口开好了，SETUP 已发。 */
    class SetupSent(val stream: AudioStreamInfo, val port: Int, val cseq: Int, val codec: AudioCodec) : AudioEvent() {
        override fun toString() =
            "已发 SETUP（CSeq $cseq，${stream.transport.label}，Codec=${codec.wire}，client_port=$port--1），等手机应答和连入"
    }

    /** 开不了端口，没有发 SETUP。 */
    class ListenFailed(val stream: AudioStreamInfo, val reason: String) : AudioEvent() {
        override fun toString() = "开不了音频端口，没发 SETUP：$reason"
    }

    /** 手机对 SETUP 的应答（按 CSeq 对应）。状态行和全部头部原样列出。 */
    class SetupAnswered(
        val stream: AudioStreamInfo,
        val statusLine: String,
        val statusCode: Int?,
        val headers: List<Pair<String, String>>,
        /** 从发出 SETUP 到收到应答的毫秒数。 */
        val afterMillis: Long,
    ) : AudioEvent() {
        val accepted: Boolean get() = statusCode == 200

        override fun toString() = "手机应答 SETUP（${afterMillis}ms 后）：$statusLine；头部 " +
            headers.joinToString("；") { (name, value) -> "$name: ${value.take(MAX_VALUE_CHARS)}" }
    }

    /** 手机连上了车机开的数据端口。 */
    class PeerConnected(val stream: AudioStreamInfo, val afterSetupMillis: Long?) : AudioEvent() {
        override fun toString() = "手机连上了数据端口" + (afterSetupMillis?.let { "（发 SETUP 后 ${it}ms）" } ?: "")
    }

    /** 手机关掉了数据连接，或者连接出错断开。 */
    class PeerClosed(val stream: AudioStreamInfo, val bytes: Long) : AudioEvent() {
        override fun toString() = "数据端口的连接断开（共收到 $bytes 字节）"
    }

    /** 分帧识别成功，之后按它拆包。 */
    class FramingDetected(val stream: AudioStreamInfo, val framing: AudioFraming) : AudioEvent() {
        override fun toString() = "分帧识别成功：$framing"
    }

    /** 分帧识别不出来。之后这条连接的字节只经原始字节回调交出，不再拆包。[head] 是最早的至多 32 个字节。 */
    class FramingUnknown(val stream: AudioStreamInfo, val reason: String, head: ByteArray) : AudioEvent() {
        private val headBytes = head.copyOf()
        val head: ByteArray get() = headBytes.copyOf()
        override fun toString() = "分帧识别不出来：$reason；前 ${headBytes.size} 字节=${headBytes.toHex()}"
    }

    /** 第一包音频已经交出。 */
    class FirstPacket(val stream: AudioStreamInfo, val payloadType: Int, val payloadLength: Int) : AudioEvent() {
        override fun toString() = "收到第一包音频（PT $payloadType，载荷 $payloadLength 字节）"
    }

    /**
     * 某一路（[ssrc]）的包开始放到 [route] 通道：每一路第一次出现、或者同一路换了通道时各出一次。
     * [bus] 是路号（不在约定范围时为 [AudioBus.UNKNOWN]），[usage] 是 SSRC 带的用途号（0 表示没带）；
     * 用途不认识时按媒体放，[unknownUsage] 为 true。
     */
    class StreamRouted(
        val stream: AudioStreamInfo,
        val ssrc: Long,
        val bus: Int,
        val usage: Int,
        val route: AudioRoute,
    ) : AudioEvent() {
        val unknownUsage: Boolean get() = usage != 0 && AudioRoute.ofUsage(usage) == null

        override fun toString() = "${AudioBus.label(bus)}（SSRC $ssrc，用途 $usage）的声音放到${route.label}通道" +
            if (unknownUsage) "（用途 $usage 不认识，按媒体放）" else ""
    }

    /**
     * 手机的通话状态（type 504 的 callState，见 [PhoneStateMessages]）。[phase] 为 null 表示取值不认识。
     * [module] 是这条消息实际走的 module。
     */
    class PhoneCallState(val state: Int, val module: Long) : AudioEvent() {
        val phase: PhoneCallPhase? get() = PhoneCallPhase.of(state)

        override fun toString() = "手机通话状态：${phase?.label ?: "不认识的取值"}（callState $state，module $module）"
    }

    /** 手机的语音助手状态（type 505 的 voiceState，0 是空闲、别的值当在用）。[module] 是这条消息实际走的 module。 */
    class PhoneVoiceState(val state: Int, val module: Long) : AudioEvent() {
        val active: Boolean get() = state != 0

        override fun toString() = "手机语音助手状态：${if (active) "在用" else "空闲"}（voiceState $state，module $module）"
    }

    /** 不影响流程的信息：PCM 字节序、载荷不像声音、序号跳变、失步转原始模式、没实现的请求等。 */
    class Note(val stream: AudioStreamInfo?, val message: String) : AudioEvent() {
        override fun toString() = message
    }

    /** 数据会话结束。[summary] 是一行汇总：SETUP 有没有被应答、手机连没连、收到多少字节、分帧结论。 */
    class Ended(val stream: AudioStreamInfo, val reason: AudioEndReason, val summary: AudioSummary) : AudioEvent() {
        override fun toString() = "数据会话结束（${reason.label}）；$summary"
    }

    private companion object {
        const val MAX_VALUE_CHARS = 200
    }
}

/**
 * 音频数据会话交给上层的出口。三个方法都在协议线程上同步调用，回调里不要阻塞；抛出的异常会被接住、
 * 记成诊断，不影响投屏。
 */
interface AudioStreamListener {
    /** 状态变化与诊断。 */
    fun onAudioEvent(event: AudioEvent) {}

    /** 音频连接上收到的原始字节（任何解析之前，识别成功与否都会交出）。[data] 只在回调期间有效。 */
    fun onAudioRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) {}

    /** 拆出的一包音频，带着路号和该放的通道（[AudioPacket.route]）。实例与载荷只在回调期间有效。 */
    fun onAudioPacket(packet: AudioPacket) {}

    companion object {
        /** 什么都不做。 */
        @JvmField
        val NONE: AudioStreamListener = object : AudioStreamListener {}
    }
}
