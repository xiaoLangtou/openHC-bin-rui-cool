package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.codec.Ipv4Text
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.MicProfile
import kotlinx.serialization.json.JsonObject

/**
 * 一次 DMSDP 会话的本机配置。
 *
 * 设备名称与车型编号取自身份资产 [host]：名称同时用作设备名和蓝牙名，完整车型编号（8 位十六进制）
 * 作为设备属性 2003。设备 ID、蓝牙地址和两端 IP 是连接时才知道的值，由调用方填入。
 */
class DmsdpConfig(
    val host: HostIdentity,
    /** 本机设备 ID：与 carInfo 里同一个 32 字节值，写成大写十六进制（64 个字符）。 */
    val deviceId: String,
    /** 本机蓝牙地址 `XX:XX:XX:XX:XX:XX`，统一转成大写。 */
    bluetoothMac: String,
    /** 本机在这次连接上的 IPv4 地址（USB 网卡或 WiFi）。 */
    val localIp: String,
    /** 手机的 IPv4 地址（控制连接的对端）。 */
    val phoneIp: String,
    /** 显示服务；null（默认）表示不声明，手机不会来投屏。 */
    val display: Display? = null,
    /**
     * 扬声器服务（手机的媒体声音传到车机）；null（默认）表示不声明，声音留在手机上。
     * 声明了手机就会把声音交给车机，车机这边接不住时两边都没声音，所以默认关、由调用方显式打开。
     */
    val speaker: Speaker? = null,
    /**
     * 麦克风服务（车机的麦克风声音交给手机：打电话、语音助手）；null（默认）表示不声明，手机用自己的麦克风。
     * 声明了手机录音时就改用车机麦克风，车机这边采不到声音时对方听不到，所以默认关、由调用方显式打开。
     */
    val mic: Mic? = null,
    /**
     * 显示以外要声明的服务（扩展点，例如以后的扬声器）。手机发 104 时，掩码与服务类型号
     * 有交集就回这条 102，按「显示在前、这里的顺序在后」逐条回。
     */
    val additionalServices: List<Service> = emptyList(),
    /** 文件分享；null（默认）表示不处理，管理消息原样以 [DmsdpEvent.UnhandledDeviceData] 交出。 */
    val fileShare: FileShare? = null,
    /** OPTIONS 里是否声明支持 UDP 保活（`UdpKA: 1`）。最终用哪种由手机选。 */
    val advertiseUdpKeepAlive: Boolean = true,
    /**
     * 服务协商完成（104）后是否主动向手机请求应用元数据（514 RequestAAData，515 MediaData 回推）。
     * 手机不会主动推音频元数据/歌词，必须车机先请求。默认关：这是手机可见的行为，golden 向量按
     * 「不发请求」的形状断言输出帧序列（2026-11-24）；app 层显式打开。
     */
    val requestMetadata: Boolean = false,
    /**
     * OPTIONS 的 `KaRetry`，同时是 UDP 保活检测窗口的系数（窗口 = 周期参数 × 该值 ÷ 2 轮）。
     * 有线、无线的 HiCar 连接都是 160。
     */
    val keepAliveRetry: Int = 160,
    /** 加密选择应答里的 `Platform`。0 为普通平台。 */
    val platform: Int = 0,
) {
    val bluetoothMac: String = bluetoothMac.uppercase()

    init {
        require(deviceId.isNotEmpty() && deviceId.length <= 64) { "deviceId 必须是 1～64 个字符" }
        require(MAC.matches(bluetoothMac)) { "bluetoothMac 格式必须是 XX:XX:XX:XX:XX:XX" }
        require(Ipv4Text.isCanonical(localIp)) { "localIp 必须是 IPv4 地址" }
        require(Ipv4Text.isCanonical(phoneIp)) { "phoneIp 必须是 IPv4 地址" }
        require(keepAliveRetry in 1..10_000) { "keepAliveRetry 超出范围" }
        require(platform in 0..9) { "platform 超出范围" }
        val ids = HashSet<String>()
        display?.let { ids += DISPLAY_SERVICE_ID }
        speaker?.let { ids += SPEAKER_SERVICE_ID }
        mic?.let { ids += MIC_SERVICE_ID }
        for (service in additionalServices) require(ids.add(service.serviceId)) { "服务 ID 重复: ${service.serviceId}" }
    }

    override fun toString() = "DmsdpConfig(display=${display != null}, speaker=$speaker, mic=$mic, 其他服务 ${additionalServices.size} 个, " +
        "文件分享=${fileShare != null})"

    /**
     * 显示服务（服务类型 8）。尺寸会出现在两处：本机设备信息 101 的属性（屏幕 1012/1013、码流 1005/1006、
     * DPI 1011、帧率 1015、编码 1014、可选 GOP 1016 与码率 1017），以及显示服务 102 的属性
     * （缩放比例 4001、宽高 1009/1010）。没配置的可选项不发送。
     */
    class Display(
        /** 屏幕（画布）宽，像素。 */
        val width: Int,
        /** 屏幕（画布）高，像素。 */
        val height: Int,
        /** 缩放比例（安卓 density 那种，例如 1.5），不是 DPI。 */
        val density: Double,
        val dpi: Int,
        val fps: Int,
        /** 码流宽，默认与屏幕相同。 */
        val videoWidth: Int = width,
        /** 码流高，默认与屏幕相同。 */
        val videoHeight: Int = height,
        /** 视频编码：1 = H.264，2 = H.265。 */
        val codec: Int = CODEC_H264,
        val gop: Int? = null,
        /** 目标码率，bit/s。 */
        val bitrate: Int? = null,
        /** 码率上限，bit/s；null 不声明（官方 1028）。 */
        val maxBitrate: Int? = null,
        /** 码率下限，bit/s；null 不声明（官方 1029）。 */
        val minBitrate: Int? = null,
    ) {
        init {
            for ((name, value) in listOf(
                "width" to width, "height" to height, "dpi" to dpi, "fps" to fps,
                "videoWidth" to videoWidth, "videoHeight" to videoHeight,
            )) {
                require(value > 0) { "$name 必须是正整数" }
            }
            require(!density.isNaN() && !density.isInfinite() && density > 0) { "density 必须是有限正数" }
            require(codec == CODEC_H264 || codec == CODEC_H265) { "codec 只能是 1（H.264）或 2（H.265）" }
            require(gop == null || gop > 0) { "gop 必须是正整数" }
            require(bitrate == null || bitrate > 0) { "bitrate 必须是正整数" }
        }

        companion object {
            const val CODEC_H264 = 1
            const val CODEC_H265 = 2
        }
    }

    /**
     * 一条服务声明（102 的内容）：`{deviceId, deviceName, serviceType, serviceId, properties, status}`，
     * 其中设备 ID 与名称自动填本机的。
     */
    class Service(
        /** 服务类型号，同时是 104 掩码里对应的位，例如显示是 8。 */
        val serviceType: Int,
        val serviceId: String,
        val properties: JsonObject,
        val status: JsonObject = JsonObject(emptyMap()),
    ) {
        init {
            require(serviceType > 0) { "serviceType 必须是正整数" }
            require(serviceId.isNotEmpty()) { "serviceId 不能为空" }
        }
    }

    /**
     * 扬声器服务（服务类型 4，服务 ID `speaker1`）。声明时属性 2003、2004、2005 是空串，2102 是一段 JSON 文本
     * （字符串类型的属性值，不是嵌套对象），见 [audioProfile]。
     *
     * 手机实际用什么编码由 2102 里的能力决定：`codecs` 为空时手机用默认的 AAC，声明 PCM 能力时改发 PCM（两条路都已真机验证）。
     * [multiBus] 为 true 时 2102 的 extendInfo 再声明多音频总线，手机就把导航语音和音乐分成两路发（见
     * [io.github.gcbond.openhc.core.dmsdp.audio.AudioRouter]）。
     *
     * 本来要 PCM、但手机在 [aacPhones] 里时（按手机记下的「PCM 不出声」，编号见
     * [io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef]），这次连接改声明 AAC。认手机靠它的设备信息（命令 101），
     * 正常在服务请求（命令 104）之前就到；没到时按 [codec] 声明。
     *
     * 手机要放声音时在控制连接上触发一次数据会话，车机回 SETUP；[transport] 和实际声明的编码决定 SETUP 里的
     * `Fillp`、`TCP`、`Codec` 三个头部（`Codec` 不影响手机选哪种编码），见 [AudioTransport]、[AudioCodec]。
     * 这里的默认值取最稳妥的（AAC）；应用的设置默认要 PCM，配合按手机退回 AAC。
     */
    class Speaker(
        val transport: AudioTransport = AudioTransport.TCP,
        val codec: AudioCodec = AudioCodec.AAC,
        val multiBus: Boolean = true,
        aacPhones: Set<String> = emptySet(),
    ) {
        /** 要退回 AAC 的手机编号。 */
        val aacPhones: Set<String> = aacPhones.toSet()

        /** 按 [codec] 声明时属性 2102 的值。 */
        val audioProfile: String get() = DmsdpConfig.audioProfile(codec, multiBus)

        override fun toString() = "Speaker(${transport.label}, ${codec.label}, 多声道分流${if (multiBus) "开" else "关"}" +
            (if (aacPhones.isEmpty()) "" else "，${aacPhones.size} 台手机退回 AAC") + ")"
    }

    /**
     * 麦克风服务（服务类型 2，服务 ID `mic1`）。声明时属性 2005、2004、2003 是空串；[profile] 不为 null 时再挂 2102
     * （[MicProfile.json]），引导手机协商出的采样参数和编码；null 时不挂 2102，手机按自己的默认参数要。
     * 数据会话的流程见 [MicSession]。
     */
    class Mic(val profile: MicProfile? = MicProfile.PCM_16K_MONO) {
        override fun toString() = "Mic(${profile ?: "不声明能力"})"
    }

    /** 文件分享的本机时限（不是手机协议规定的时间）。 */
    class FileShare(
        /** 收到文件请求后等用户确认的时间，超时回拒绝。 */
        val offerTimeoutMillis: Long = 30_000,
        /** 接收中多久没有新数据算停滞，超时回取消。 */
        val idleTimeoutMillis: Long = 60_000,
    ) {
        init {
            require(offerTimeoutMillis > 0 && idleTimeoutMillis > 0) { "文件分享时限必须为正" }
        }
    }

    internal companion object {
        const val SERVICE_TYPE_DISPLAY = 8
        const val DISPLAY_SERVICE_ID = "display1"

        /** 扬声器在服务声明（102、104 掩码）里的服务类型号。 */
        const val SERVICE_TYPE_SPEAKER = 4
        const val SPEAKER_SERVICE_ID = "speaker1"

        /** 麦克风在服务声明（102、104 掩码）和数据会话消息里的服务类型号（两处相同）。 */
        const val SERVICE_TYPE_MIC = 2
        const val MIC_SERVICE_ID = "mic1"

        /**
         * 扬声器服务属性 2102 的值：一段 JSON 文本，作为字符串放进属性。键名和顺序照实测接受的形状，不要改：
         *
         * - `codecs`、`profiles` 为空数组：手机用默认的 AAC；
         * - PCM：`codecs` 是 `[1]`（编码号，1 是 PCM），`profiles` 是一个 48kHz、`channel` 12（立体声）、
         *   `format` 1（16 位）的参数对象；手机照此改发 PCM（实际编码仍以载荷判断为准）；
         * - `extendInfo`：声明多音频总线时是 [MULTI_BUS_EXTEND_INFO]，否则是空对象；
         * - `currentVolume`、`soundEffect` 的含义没有确认，原样发送。
         */
        fun audioProfile(codec: AudioCodec, multiBus: Boolean): String {
            val extend = if (multiBus) MULTI_BUS_EXTEND_INFO else "{}"
            return if (codec == AudioCodec.PCM) {
                "{\"codecs\":[1],\"currentVolume\":{\"a\":false},\"extendInfo\":$extend," +
                    "\"profiles\":[{\"sampleRate\":48000,\"channel\":12,\"format\":1}],\"soundEffect\":0}"
            } else {
                "{\"codecs\":[],\"currentVolume\":{\"a\":false},\"extendInfo\":$extend,\"profiles\":[],\"soundEffect\":0}"
            }
        }

        /**
         * 2102 里声明多音频总线的 extendInfo：两个值都是字符串。手机见到它就把声音分路发（音乐第 8 路、导航第 0 路，
         * 播报前后发分流焦点消息）；策略串 `8:4096:1` 照实测能分流的取值，改了手机就不再按这个方式分。
         */
        const val MULTI_BUS_EXTEND_INFO = "{\"MultiAudioBusCap\":\"true\",\"MultiAudioBusPolicy\":\"8:4096:1\"}"
        private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }
}
