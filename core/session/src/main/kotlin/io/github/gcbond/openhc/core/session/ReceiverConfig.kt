package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.HiCarAuthConfig
import io.github.gcbond.openhc.core.auth.TrustStore
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.dmsdp.DmsdpConfig
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.MicProfile
import io.github.gcbond.openhc.core.hisight.HiSightConfig
import io.github.gcbond.openhc.core.nearby.NearbySession
import java.security.SecureRandom
import java.util.TimeZone

/**
 * 一次有线投屏连接的本机配置。构造时就把各层的配置试建一遍，参数不对立刻抛 [IllegalArgumentException]，
 * 不会等到手机连上、认证完才发现。
 */
class ReceiverConfig(
    /** 身份资产（车型、品牌、名称、nearby 密钥材料、认证服务身份）。 */
    val identity: IdentityAssets,
    /**
     * 车机蓝牙地址 `AA:BB:CC:DD:EE:FF`（也接受 `-` 分隔）；取不到传 null。
     * carInfo、控制通道握手、认证身份、设备信息都由它和车型编号推出，生成 carInfo 时必须用同一个值。
     * 换了它就是另一台车，之前的配对记录对不上，手机会要求重新输入连接码。
     */
    val bluetoothAddress: String?,
    /** 车机在 USB 网卡上的 IPv4，与 carInfo 里写的一致。认证和设备会话的监听都绑在这个地址上。 */
    val localIp: String,
    /** 控制监听端口，与 carInfo 里写的一致。 */
    val controlPort: Int,
    /** 声明给手机的显示能力。 */
    val display: DisplaySettings,
    /**
     * 认证外层消息的 `version` 字段取值。它是协议约定的值，仓库里不写，由调用方提供；与手机不一致时认证不会开始。
     */
    val authEnvelopeVersion: String,
    /** 长期身份与已配对手机的存储；首次配对成功时写入，免码回连时读取。 */
    val trustStore: TrustStore,
    /**
     * 控制监听绑定的地址，默认通配 `0.0.0.0`：开监听时手机还没切成 USB 网卡，[localIp] 往往还不存在，
     * 绑不上。通配监听只接受本端地址是 [localIp] 的连接（见 [SessionAction.Listen.requireLocalHost]），
     * 从别的网卡连进来的直接关掉。网卡已经就绪时可以直接传 [localIp]；绑具体地址时不再按本端地址筛选。
     */
    val controlBindHost: String = WILDCARD,
    /**
     * 通配监听时是否只接受本端地址是 [localIp] 的连接。默认开；万一筛选误判（例如系统报的本端地址写法不同），
     * 可以关掉，代价是别的网卡上也能连到控制端口，只能靠后面的认证把关。
     */
    val controlLocalFilter: Boolean = true,
    /** 生成连接码、各层加密 IV 等。测试里可以传确定序列。 */
    val random: SecureRandom = SecureRandom(),
    /** 墙上时间（毫秒），只用于投屏控制消息的 Date 头。 */
    val wallClock: () -> Long = { System.currentTimeMillis() },
    /** 投屏控制消息 Date 头用的时区。 */
    val timeZone: TimeZone = TimeZone.getDefault(),
    /** 首次配对用的固定连接码（6 位数字）；null 表示每次随机生成。只在测试里指定。 */
    val pin: String? = null,
    /** 向手机声明可以接受触摸；开了才能用 [ReceiverSession.sendInput] 发触摸。 */
    val touchInput: Boolean = false,
    /**
     * 服务协商完成后主动请求应用元数据（514/515，歌词/歌曲信息）。默认关——它是不带
     * "_launcher" 时仍然手机可见的行为，v29 起由 app 层显式打开（2026-11-24 拆分验证）。
     */
    val requestMetadata: Boolean = false,
    /** 向手机声明可以接受物理按键。 */
    val keyInput: Boolean = false,
    /**
     * 向手机声明可以接受媒体键（播放、暂停、上一首、下一首等），协商宽松：手机不选回只是这次没有媒体键，
     * 不影响投屏（见 [io.github.gcbond.openhc.core.hisight.HiSightConfig.mediaKeyInput]）。
     * 能不能发看 [SessionEvent.VideoNegotiated] 里的 keyInput。
     */
    val mediaKeyInput: Boolean = false,
    /**
     * 声明扬声器、接手机的媒体声音；null（默认）表示不声明，声音留在手机上。
     * 声明了手机就把声音交给车机，车机接不住时两边都没声音，所以这里默认关，由应用按设置打开。
     */
    val audio: AudioSettings? = null,
    /**
     * 声明麦克风、把车机麦克风的声音交给手机（打电话、语音助手）；null（默认）表示不声明，手机用自己的麦克风。
     * 声明了手机录音时就改用车机麦克风，车机采不到时对方听不到，所以默认关，由应用按设置和录音权限打开。
     */
    val mic: MicSettings? = null,
    /**
     * 额外声明的 DMSDP 服务（104 服务请求掩码里对应位有请求时回 102 服务信息）。
     * 实验用：手机请求了掩码 0x31f（服务 1/2/3/4/5/8/9），核心流程只认 display/speaker/mic，
     * 其他服务（媒体卡片、集成等）由这里声明。
     */
    val extraDmsdpServices: List<DmsdpConfig.Service> = emptyList(),
) {
    /** 本机 deviceId（64 位大写十六进制）：认证里的本机身份、设备会话里的本机标识都用它。 */
    val deviceId: String = NearbySession.deviceId(identity.host, bluetoothAddress)

    /** 设备会话里报给手机的蓝牙地址文本：大写、冒号分隔；取不到时与 nearby 层一样用 `02:00:00:00:00:00`。 */
    val bluetoothText: String = normalizeBluetooth(bluetoothAddress)

    init {
        require(controlPort in 1..65535) { "控制端口必须在 1～65535 之间，实际 $controlPort" }
        require(isIpv4(localIp)) { "localIp 必须是 IPv4 地址: $localIp" }
        require(isIpv4(controlBindHost)) { "controlBindHost 必须是 IPv4 地址: $controlBindHost" }
        require(authEnvelopeVersion.isNotEmpty()) { "authEnvelopeVersion 不能为空" }
        // 各层配置在会话途中才创建；这里先用同样的参数试建一遍，把配置错误提前到启动时。
        authConfig()
        dmsdpConfig(phoneIp = localIp)
        hisightConfig()
    }

    /**
     * 生成这份配置对应的 91 字节 carInfo（经 USB 控制传输发给手机）。[localIp] 必须正好 14 个字符，
     * 例如 `192.168.66.100`～`192.168.66.253`，见 [NearbySession.carInfo]。
     */
    fun carInfo(): ByteArray = NearbySession.carInfo(identity.host, bluetoothAddress, localIp, controlPort)

    internal fun authConfig(): HiCarAuthConfig =
        HiCarAuthConfig(identity.authService, deviceId, authEnvelopeVersion, pin)

    internal fun dmsdpConfig(phoneIp: String, phoneVersion: String? = null): DmsdpConfig = DmsdpConfig(
        host = identity.host,
        deviceId = deviceId,
        bluetoothMac = bluetoothText,
        localIp = localIp,
        phoneIp = phoneIp,
        requestMetadata = requestMetadata,
        display = DmsdpConfig.Display(
            width = display.width,
            height = display.height,
            density = display.density,
            dpi = display.dpi,
            fps = display.fps,
            gop = display.gop,
            bitrate = display.bitrate,
            maxBitrate = display.maxBitrate,
            minBitrate = display.minBitrate,
        ),
        speaker = audio?.let { DmsdpConfig.Speaker(it.transport, it.codec, it.multiBus, it.aacPhones, it.udpPhones) },
        mic = mic?.let { DmsdpConfig.Mic(it.profile) },
        additionalServices = extraDmsdpServices,
        phoneVersion = phoneVersion,
    )

    /** 投屏能力：屏幕和允许的编码尺寸都取画布尺寸（编码尺寸不超过画布）。 */
    internal fun hisightConfig(): HiSightConfig = HiSightConfig(
        screenWidth = display.width,
        screenHeight = display.height,
        videoWidth = display.width,
        videoHeight = display.height,
        fps = display.fps,
        dpi = display.dpi,
        gop = display.gop,
        bitrate = display.bitrate,
        touchInput = touchInput,
        keyInput = keyInput,
        mediaKeyInput = mediaKeyInput,
    )

    /**
     * 认证握手应答里回给手机的车机信息（值都是字符串）：连接能力、完整车型编号、品牌，以及
     * initialConfig 能力键（官方 SDK 经 CarInfo.setCarDetail 上线，见 HiCar 文档「深浅模式」「车机类型」）：
     * DAY_NIGHT_MODE 支持深浅模式检测（导航元数据依赖它），DRIVING_POSITION 方向盘侧（左舵），
     * DEVICE_TYPE=4 前装车机（缺省 0=未知，可能影响手机对元数据/桌面能力的判定）。
     * 车型和品牌来自身份资产。
     */
    internal fun authDeviceInfo(): Map<String, String> = linkedMapOf(
        "CONNECT_ABILITY_KEY" to CONNECT_ABILITY_WIRED,
        "CAR_MODE_ID" to identity.host.fullModel,
        "CAR_BRAND" to identity.host.brand,
        "DEVICE_TYPE" to "4",
        "DAY_NIGHT_MODE" to "day",
        "DRIVING_POSITION" to "left",
    )

    override fun toString() =
        "ReceiverConfig($localIp:$controlPort, 监听=$controlBindHost, 画布=${display.width}x${display.height}@${display.fps})"

    companion object {
        const val WILDCARD = "0.0.0.0"

        /** 有线连接时认证握手应答里声明的连接能力取值。 */
        private const val CONNECT_ABILITY_WIRED = "2"

        private const val UNAVAILABLE_BLUETOOTH = "02:00:00:00:00:00"

        private fun normalizeBluetooth(address: String?): String {
            if (address == null) return UNAVAILABLE_BLUETOOTH
            require(address.length == 17) { "蓝牙地址格式应为 AA:BB:CC:DD:EE:FF" }
            return address.replace('-', ':').uppercase()
        }

        internal fun isIpv4(text: String): Boolean {
            val parts = text.split('.')
            if (parts.size != 4) return false
            return parts.all { p ->
                p.length in 1..3 && p.all { it in '0'..'9' } && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
            }
        }
    }
}

/**
 * 扬声器（手机的声音）：向手机提议的传输方式和编码、要不要声明多音频总线（导航单独一路），以及哪些手机要退回 AAC。
 * 默认 TCP + AAC + 多声道分流。编码选 PCM 时扬声器能力改为声明 PCM，实际编码以收到的载荷为准；[aacPhones] 里的手机
 * （编号见 [io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef]，应用按「PCM 不出声」记下的）这次连接改声明 AAC。
 * 传输方式 TCP 时按手机选（先 TCP，[udpPhones] 里的手机和不认 TCP 的手机用 UDP），UDP 时一律 UDP；
 * FillP 本机没有实现，只用来对照手机的反应。
 */
class AudioSettings(
    val transport: AudioTransport = AudioTransport.TCP,
    val codec: AudioCodec = AudioCodec.AAC,
    val multiBus: Boolean = true,
    aacPhones: Set<String> = emptySet(),
    udpPhones: Set<String> = emptySet(),
) {
    val aacPhones: Set<String> = aacPhones.toSet()
    val udpPhones: Set<String> = udpPhones.toSet()

    override fun toString() = "AudioSettings(${transport.label}, ${codec.label}, 多声道分流${if (multiBus) "开" else "关"}" +
        (if (aacPhones.isEmpty()) "" else "，${aacPhones.size} 台手机退回 AAC") +
        (if (udpPhones.isEmpty()) "" else "，${udpPhones.size} 台手机用 UDP") + ")"
}

/**
 * 车机麦克风：向手机声明的麦克风能力。默认声明 16kHz 单声道、只给 PCM（[MicProfile.PCM_16K_MONO]），手机会落到 PCM；
 * [profile] 为 null 时不声明能力（与不带能力的原始形状相同），手机按自己的默认参数要，可能要 AAC。
 */
class MicSettings(val profile: MicProfile? = MicProfile.PCM_16K_MONO) {
    override fun toString() = "MicSettings(${profile ?: "不声明能力"})"
}

/**
 * 声明给手机的显示能力：画布（屏幕）宽高、帧率、DPI、缩放比例，可选 GOP 和码率。
 * 车机屏幕刷新率不是 60Hz 时，帧率按实际刷新率填。
 */
class DisplaySettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val dpi: Int,
    /** 缩放比例（安卓 density 那种，例如 1.5），不是 DPI。 */
    val density: Double,
    /** 关键帧间隔；null 表示不声明。 */
    val gop: Int? = null,
    /** 目标码率，bit/s；null 表示不声明。 */
    val bitrate: Int? = null,
    /** 码率上限，bit/s；null 表示不声明（官方 1028）。 */
    val maxBitrate: Int? = null,
    /** 码率下限，bit/s；null 表示不声明（官方 1029）。 */
    val minBitrate: Int? = null,
) {
    override fun toString() = "DisplaySettings(${width}x$height@$fps, dpi=$dpi, density=$density)"
}
