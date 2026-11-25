package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.common.toHex
import kotlinx.serialization.json.JsonObject

/** DMSDP 层涉及的 TCP 连接。UDP 保活单独走 [DmsdpSession.onDatagram]。 */
enum class DmsdpChannel {
    /** 业务 20 的 TCP（手机连车机）：只承载首轮 16 字节协商，换来控制连接的端口。 */
    SESSION,

    /** 控制连接（手机连车机的控制监听）：DMSDP 文本控制消息和 `$` 设备消息，协商后全程加密。 */
    CONTROL,

    /** 文件分享控制 TCP（车机连手机在管理消息 cmdId 7 里给的端口），不加密。 */
    FILE_CONTROL,

    /** 文件分享数据 TCP（车机连手机在管理消息 cmdId 8 里给的端口），不加密。 */
    FILE_DATA,

    /** 音频数据 TCP：手机连车机在 SETUP 里给的 `client_port`（`P--1` 的 P，第二个口是 RTCP，写 -1 表示不建）。 */
    AUDIO,

    /** 麦克风数据 TCP：手机连车机在麦克风 SETUP 的 200 里给的 server_port；车机发、手机收。 */
    MIC,
}

/** 保活方式：由手机在加密选择请求里最终决定。 */
enum class KeepAliveMode {
    /** 控制连接上的加密 `GET_PARAMETER`，每秒一轮。 */
    TCP,

    /** 独立 UDP 上的加密 16 字节保活包。 */
    UDP,
}

/**
 * 手机通过 type 515 推送的结构化音频元数据（全量包或每秒进度包）。
 * [totalTimeSec] 与 [elapsedTimeSec] 单位均为秒（华为手机端 MediaMetaDataClient 已做 /1000）。
 */
class DmsdpMediaData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val appName: String? = null,
    val appPackage: String? = null,
    val totalTimeSec: Int? = null,
    val elapsedTimeSec: Int? = null,
    val status: Int? = null,
    val albumArtBytes: ByteArray? = null,
    val albumArtUrl: String? = null,
    val lyricContent: String? = null,
    val currentLyric: String? = null,
) {
    override fun toString() =
        "DmsdpMediaData(title=$title, artist=$artist, status=$status, elapsed=$elapsedTimeSec/$totalTimeSec)"
}

/** [DmsdpSession] 各方法的输出：按产生顺序排列，事件和动作混排。 */
sealed interface DmsdpOutput

/** 需要调用方去做的 I/O。 */
sealed class DmsdpAction : DmsdpOutput {

    /** 把 [bytes] 原样写到 [channel] 对应的 TCP 连接（已是完整的线上字节）。不要修改数组内容。 */
    class Send(val channel: DmsdpChannel, val bytes: ByteArray) : DmsdpAction() {
        override fun toString() = "Send($channel, ${bytes.size} 字节)"
    }

    /** 经 UDP 保活 socket 发一个数据报（已是完整的线上字节）。 */
    class SendDatagram(val bytes: ByteArray) : DmsdpAction() {
        override fun toString() = "SendDatagram(${bytes.size} 字节)"
    }

    /**
     * 在本机地址上开一个控制连接的 TCP 监听（端口由系统分配），然后调用
     * [DmsdpSession.controlListenerReady]；手机连上后调用 [DmsdpSession.onConnected]。
     */
    object OpenControlListener : DmsdpAction() {
        override fun toString() = "OpenControlListener"
    }

    /**
     * 建 UDP 保活 socket：绑本机地址的临时端口，connect 到「手机 IP : [remotePort]」。
     * 本机端口不用告诉手机。收到的数据报交给 [DmsdpSession.onDatagram]。
     */
    class OpenKeepAliveSocket(val remotePort: Int) : DmsdpAction() {
        override fun toString() = "OpenKeepAliveSocket($remotePort)"
    }

    /** 主动连接「手机 IP : [port]」作为 [channel]，连上后调用 [DmsdpSession.onConnected]。 */
    class Connect(val channel: DmsdpChannel, val port: Int) : DmsdpAction() {
        override fun toString() = "Connect($channel, $port)"
    }

    /** 关闭 [channel] 对应的连接（之后不要再喂它的数据）。 */
    class Close(val channel: DmsdpChannel) : DmsdpAction() {
        override fun toString() = "Close($channel)"
    }

    /**
     * 为手机触发的音频数据会话在本机地址上开数据端口 P（SETUP 里写成 `client_port=P--1`）：[transport] 是 TCP 时开 TCP
     * 监听，手机连上来的连接是 [DmsdpChannel.AUDIO]；是 UDP 时在 P 上收 UDP 数据报，手机直接往 P 发，每个数据报是
     * 完整的一帧（外壳和 TCP 时相同），第一个数据报到达时调 [DmsdpSession.onConnected]，之后按到达顺序 [DmsdpSession.feed]。
     * 开好后调 [DmsdpSession.audioListenersReady]，开不了调 [DmsdpSession.audioListenersFailed]。
     * 之前的音频监听和连接已经先要求关掉了。
     */
    class OpenAudioListeners(val transport: AudioTransport) : DmsdpAction() {
        override fun toString() = "OpenAudioListeners(${transport.label})"
    }

    /** 关掉当前音频数据会话的全部监听和连接（数据会话已拆除或被替换）。 */
    object CloseAudioTransport : DmsdpAction() {
        override fun toString() = "CloseAudioTransport"
    }

    /**
     * 为手机的麦克风 SETUP 在本机地址上开一个 TCP 监听（端口由系统分配）。开好后调 [DmsdpSession.micListenerReady]
     * （本类随即回 SETUP 的 200），开不了调 [DmsdpSession.micListenerFailed]。手机连上的连接是 [DmsdpChannel.MIC]，
     * 之后经 [DmsdpSession.sendMicAudio] 往上写声音。
     */
    object OpenMicListener : DmsdpAction() {
        override fun toString() = "OpenMicListener"
    }

    /** 关掉当前麦克风数据会话的监听和连接（会话已拆除、被替换或连接已断）。 */
    object CloseMicTransport : DmsdpAction() {
        override fun toString() = "CloseMicTransport"
    }
}

/** 协议事件。toString 不输出设备标识、JSON 正文或文件内容。 */
sealed class DmsdpEvent : DmsdpOutput {

    // ---------- 控制连接 ----------

    /** 加密选择已明文应答，控制连接此后全程 AES-128-CTR；[keepAliveMode] 是手机最终选的保活方式。 */
    class EncryptionEnabled(val keepAliveMode: KeepAliveMode) : DmsdpEvent() {
        override fun toString() = "EncryptionEnabled($keepAliveMode)"
    }

    /**
     * 手机发来时间同步请求，已回 200。三个值是原样字符串，本层不解释。
     * 时间服务本身（UDP）没有实现。
     */
    class TimeSyncRequested(val timeServerPort: String, val timeUsHigh: String, val timeUsLow: String) : DmsdpEvent() {
        override fun toString() = "TimeSyncRequested"
    }

    /**
     * 本层没有实现的文本控制消息，原样交出，没有应答。包括加密后的未知请求
     * （例如数据会话的 `SET_PARAMETER`）、手机的响应，以及格式不符合保活/时间同步要求的同名请求。
     */
    class UnhandledControlMessage(val message: DmsdpMessage) : DmsdpEvent() {
        override fun toString() = "UnhandledControlMessage(${message.startLine})"
    }

    /**
     * 加密控制连接上认不出的载荷，已跳过：既不是 `$` 设备记录，也不像文字消息。
     * 实测（2026-11-22 Mate 70 无线）：手机的声音数据连接还没连上时，会先经控制连接送声音
     * （手机日志「send binary data … cmd=3」），连上数据端口后就改走数据连接，前后只有一两个声音包（每包约 21 毫秒），
     * 跳过对听感没影响。看到的格式（一次连接里 3 段，结构是推测）：
     * `'#'(0x23) ‖ module(BE32，等于声音数据会话号) ‖ type(BE32，0xFEDCBA03) ‖ 1 字节（3 段依次 1、2、3，推测是开头/中间/结尾）
     * ‖ 序号(BE32) ‖ 分片号(BE16，从 0 起) ‖ 分片长度(BE16) ‖ 数据`；3 段拼起来 4110 字节，开头 14 字节是包头，其后 4096 字节 PCM。
     * [count] 是本连接里第几个，[size] 是载荷字节数，[head] 是开头最多 [HEAD_BYTES] 字节（十六进制写进日志）。
     * 只交出前几个和之后每第 100 个。
     */
    class UnrecognizedControlPayload(val count: Int, val size: Int, head: ByteArray) : DmsdpEvent() {
        private val headBytes = head.copyOf()

        val head: ByteArray get() = headBytes.copyOf()

        override fun toString() = "UnrecognizedControlPayload(第 $count 个，$size 字节，开头 ${headBytes.toHex()})"

        companion object {
            const val HEAD_BYTES = 48
        }
    }

    // ---------- 保活 ----------

    /** UDP 保活周期切换：[extended] 为 true 时每轮 15 秒（周期参数 30），否则每轮 1 秒（周期参数 2）。 */
    class KeepAlivePeriodChanged(val extended: Boolean) : DmsdpEvent() {
        override fun toString() = "KeepAlivePeriodChanged(extended=$extended)"
    }

    /** 保活超时，会话应当结束：TCP 连续 10 轮没收到手机的 GET；UDP 整个检测窗口没收到保活包。 */
    class KeepAliveTimeout(val mode: KeepAliveMode) : DmsdpEvent() {
        override fun toString() = "KeepAliveTimeout($mode)"
    }

    // ---------- 设备与服务（module 3）----------

    /** 手机的设备信息（命令 101）。第一次收到时已回本机设备信息。 */
    class PeerDeviceInfo(val deviceId: String, val deviceType: Long, val device: JsonObject) : DmsdpEvent() {
        override fun toString() = "PeerDeviceInfo(deviceType=$deviceType)"
    }

    /** 手机的服务信息（命令 102）。 */
    class PeerServiceInfo(val serviceType: Long, val serviceId: String, val service: JsonObject) : DmsdpEvent() {
        override fun toString() = "PeerServiceInfo(serviceType=$serviceType)"
    }

    /** 手机请求服务列表（命令 104），[answeredServiceIds] 是这次回了 102 的本机服务。 */
    class ServicesRequested(val mask: Long, val answeredServiceIds: List<String>) : DmsdpEvent() {
        override fun toString() = "ServicesRequested(mask=0x${java.lang.Long.toHexString(mask)}, 回应 ${answeredServiceIds.size} 个)"
    }

    /** 服务状态通知（命令 103），不需要应答。[action] 的取值含义未确认，原样交出。 */
    class ServiceStatusChanged(
        val action: Int,
        val serviceType: Long,
        val serviceId: String,
        val service: JsonObject,
    ) : DmsdpEvent() {
        override fun toString() = "ServiceStatusChanged(action=$action, serviceType=$serviceType)"
    }

    /** 断开类型通知（命令 105），不需要应答，也不代表连接已断。取值含义未确认。 */
    class DisconnectTypeNotified(val disconnectType: Int) : DmsdpEvent() {
        override fun toString() = "DisconnectTypeNotified($disconnectType)"
    }

    /** 手机给出 HiSight 投屏控制端口（module 5 的命令 101）：车机去连「手机 IP : [port]」。 */
    class DisplayPortAnnounced(val port: Int) : DmsdpEvent() {
        override fun toString() = "DisplayPortAnnounced($port)"
    }

    /**
     * 设备消息里本层不认识的命令号（module 3 的未知命令、module 5 除 101 以外的命令）。
     * 没有应答，也不代表对应服务已启动；会话继续。
     */
    class UnsupportedDeviceCommand(val module: Long, val command: Int, val body: ByteArray) : DmsdpEvent() {
        override fun toString() = "UnsupportedDeviceCommand(module=$module, command=$command, ${body.size} 字节)"
    }

    /**
     * 本层不处理的设备消息（module/type 组合），原样交出，没有应答。例如镜像相关的 module 1 / type 546，
     * 以及没开文件分享时的 module 1 / type 17。要回复时用 [DmsdpSession.sendDeviceData]。
     */
    class UnhandledDeviceData(val module: Long, val type: Int, val data: ByteArray) : DmsdpEvent() {
        override fun toString() = "UnhandledDeviceData(module=$module, type=$type, ${data.size} 字节：${preview(data)})"

        private companion object {
            const val PREVIEW_BYTES = 160
            val LONG_TOKEN = Regex("[0-9A-Za-z+/=_-]{24,}")

            /** 内容开头（多半是 JSON 文本）：不可打印字节换成点，长串隐去，免得把标识写进日志。 */
            fun preview(data: ByteArray): String {
                val text = buildString {
                    for (i in 0 until minOf(data.size, PREVIEW_BYTES)) {
                        val c = data[i].toInt() and 0xFF
                        append(if (c in 0x20..0x7E) c.toChar() else '.')
                    }
                }.replace(LONG_TOKEN) { "<隐去 ${it.value.length} 字符>" }
                return if (data.size > PREVIEW_BYTES) "$text…" else text
            }
        }
    }

    /**
     * 设备记录（`$` 记录）原始全量转储（调试用）：收到的每条记录在解析/分发之前、发出的每条记录在编码之后
     * 都原样交出，不经过任何解析分支的过滤——防止数据漏在没实现的 module/type 组合里看不见。
     * 十六进制永远全量输出，可打印内容额外给 UTF-8 预览。
     */
    class RawRecord(val sent: Boolean, val module: Long, val type: Int, val data: ByteArray) : DmsdpEvent() {
        override fun toString(): String {
            val text = runCatching { String(data, Charsets.UTF_8) }.getOrDefault("")
            val printable = text.isNotEmpty() && text.all { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
            return "RawRecord(" + (if (sent) "发出" else "收到") + " module=$module type=$type ${data.size} 字节" +
                (if (printable) "，UTF-8：${text.take(400).replace("\n", " ")}）" else "）hex：${data.toHex()}）")
        }
    }

    /**
     * 管理通道（module 1）JSON 消息的解析结果（见 [ManagementMessages]）：只是通知，会话继续。
     */
    class ManagementParsed(val type: Int, val summary: String) : DmsdpEvent() {
        override fun toString() = "ManagementParsed(type=$type, $summary)"
    }

    /**
     * 手机推送的音频元数据（管理通道 type 515 的 MediaData，见 [ManagementMessages]）：
     * 歌名/歌手/专辑/播放状态/进度，HarmonyOS 6.0+ 华为音乐与 QQ 音乐带 Lyric（标准 LRC 文本）。
     * 上层（桌面 HMI / EAS MediaCenter 喂数）从这里取数据。
     */
    class MediaDataReceived(
        val media: JsonObject,
        val info: DmsdpMediaData = ManagementMessages.extractMediaData(media),
    ) : DmsdpEvent() {
        override fun toString() = "MediaDataReceived(${media.keys.joinToString()})"
    }

    /**
     * 手机要求退出投屏、回车机桌面（HiCar 应用列表的车辆磁贴）。主识别是 type 529 的
     * `{"RequestApp":{"Action":"start","AppPackage":"com.huawei.hicar"}}`（逆向官方车机 APK 确认），
     * 未知形态的兜底规则见 [ManagementMessages.CAR_DESKTOP_KEYS]；
     * 收到即结束整个连接，走回车机桌面链路。
     */
    class CarDesktopRequested(val detail: String) : DmsdpEvent() {
        override fun toString() = "CarDesktopRequested($detail)"
    }

    // ---------- 音频数据会话（详情经 [io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener] 交出）----------

    /** 手机对音频 SETUP 回了 200：数据会话已建立，手机随后应当连车机的数据端口。 */
    class AudioSessionEstablished(val dataSessionId: String) : DmsdpEvent() {
        override fun toString() = "AudioSessionEstablished($dataSessionId)"
    }

    /** 音频数据会话已拆除（[reason] 是一句话原因），对应的监听和连接已要求关闭。 */
    class AudioSessionTornDown(val dataSessionId: String, val reason: String) : DmsdpEvent() {
        override fun toString() = "AudioSessionTornDown($dataSessionId, $reason)"
    }

    /** 手机连上了麦克风数据端口：车机开始往上送声音。 */
    class MicSessionEstablished(val dataSessionId: String) : DmsdpEvent() {
        override fun toString() = "MicSessionEstablished($dataSessionId)"
    }

    /** 麦克风数据会话已结束（[reason] 是一句话原因），对应的监听和连接已要求关闭。 */
    class MicSessionTornDown(val dataSessionId: String, val reason: String) : DmsdpEvent() {
        override fun toString() = "MicSessionTornDown($dataSessionId, $reason)"
    }

    /** 音频部分的诊断（例如音频回调抛了异常），不影响设备会话。 */
    class AudioNote(val message: String) : DmsdpEvent() {
        override fun toString() = "AudioNote($message)"
    }

    // ---------- 文件分享 ----------

    /** 手机确认支持文件分享（管理消息 cmdId 9）。 */
    object FileShareSupported : DmsdpEvent() {
        override fun toString() = "FileShareSupported"
    }

    /**
     * 手机要发文件过来（文件控制命令 101），等用户决定：调用 [DmsdpSession.acceptFileOffer]、
     * [DmsdpSession.rejectFileOffer]。[offerId] 只在本机有意义。
     */
    class FileOffered(val offerId: Long, val files: List<Entry>, val totalBytes: Long) : DmsdpEvent() {
        class Entry(val name: String, val size: Long) {
            override fun toString() = "Entry($size 字节)"
        }

        override fun toString() = "FileOffered($offerId, ${files.size} 个文件, $totalBytes 字节)"
    }

    /** 开始接收一个文件。之后是若干 [FileChunk]，最后 [FileFinished]（空文件没有 [FileChunk]）。 */
    class FileStarted(val offerId: Long, val name: String, val size: Long) : DmsdpEvent() {
        override fun toString() = "FileStarted($offerId, $size 字节)"
    }

    /** 文件内容的一段，按顺序追加即可。 */
    class FileChunk(val offerId: Long, val name: String, val data: ByteArray) : DmsdpEvent() {
        override fun toString() = "FileChunk($offerId, ${data.size} 字节)"
    }

    class FileFinished(val offerId: Long, val name: String) : DmsdpEvent() {
        override fun toString() = "FileFinished($offerId)"
    }

    /**
     * 清单里的文件全部收齐。调用方保存好之后调用 [DmsdpSession.confirmFileOfferSaved]
     * 通知手机；保存失败就调用 [DmsdpSession.cancelFileOffer]。
     */
    class FileOfferReceived(val offerId: Long) : DmsdpEvent() {
        override fun toString() = "FileOfferReceived($offerId)"
    }

    /** 一次文件请求结束（没有收完），调用方应丢弃已收到的部分。 */
    class FileOfferEnded(val offerId: Long, val reason: Reason) : DmsdpEvent() {
        enum class Reason {
            /** 本机拒绝（含空间不足）。 */
            REJECTED,

            /** 本机取消。 */
            CANCELLED,

            /** 本机等用户确认或等数据超时。 */
            LOCAL_TIMEOUT,

            /** 手机报告请求超时（105）。 */
            PEER_TIMEOUT,

            /** 手机取消（107）。 */
            PEER_CANCELLED,

            /** 手机报告发送失败（112）。 */
            PEER_FAILED,
        }

        override fun toString() = "FileOfferEnded($offerId, $reason)"
    }

    /**
     * 文件分享出错（格式不对、连接断开等），两条文件连接已要求关闭，进行中的请求（[offerId]）作废。
     * 只影响文件分享，DMSDP 会话继续。
     */
    class FileShareFailed(val offerId: Long?, val reason: String) : DmsdpEvent() {
        override fun toString() = "FileShareFailed($offerId, $reason)"
    }
}
