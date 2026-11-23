package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.HiCarAuthConfig
import io.github.gcbond.openhc.core.auth.PinCodes
import io.github.gcbond.openhc.core.auth.TrustStore
import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.dmsdp.DmsdpConfig
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.nearby.PairingQrCode
import java.security.SecureRandom
import java.util.TimeZone

/**
 * 一次无线投屏连接的本机配置。构造时就把 WiFi 阶段的配置试建一遍，参数不对立刻抛 [IllegalArgumentException]。
 */
class WirelessReceiverConfig(
    /** 身份资产（车型、品牌、名称、nearby 密钥材料、认证服务身份与外层版本）。 */
    val identity: IdentityAssets,
    /**
     * 车机经典蓝牙的公共地址 `AA:BB:CC:DD:EE:FF`，必须是真实地址：手机按二维码里的它连车机蓝牙，也用它和车型编号算
     * 「车机 ID」决定要不要输码；控制握手、认证、设备会话里的 deviceId 也由它推出，几处必须同源。
     */
    val bluetoothAddress: String,
    /** 声明给手机的显示能力。 */
    val display: DisplaySettings,
    /** 长期身份与已配对手机的存储，和有线共用同一份。 */
    val trustStore: TrustStore,
    /** 本机是否也支持 USB 连接：决定二维码的 `ability` 和认证应答里的连接能力。 */
    val supportsUsb: Boolean = true,
    /** 认证外层消息的 `version` 字段取值，默认取身份资产里的。 */
    val authEnvelopeVersion: String = identity.authEnvelopeVersion,
    /** 连接码、各层加密 IV 等的随机来源。测试里可以传确定序列。 */
    val random: SecureRandom = SecureRandom(),
    /** 墙上时间（毫秒），只用于投屏控制消息的 Date 头。 */
    val wallClock: () -> Long = { System.currentTimeMillis() },
    /** 投屏控制消息 Date 头用的时区。 */
    val timeZone: TimeZone = TimeZone.getDefault(),
    /** 固定的连接码（6 位数字）；null 表示每次会话随机生成。只在测试里指定。 */
    val pin: String? = null,
    /** 向手机声明可以接受触摸。 */
    val touchInput: Boolean = false,
    /** 向手机声明可以接受物理按键。 */
    val keyInput: Boolean = false,
    /** 向手机声明可以接受媒体键，规则与有线的 [ReceiverConfig.mediaKeyInput] 相同。 */
    val mediaKeyInput: Boolean = false,
    val timeouts: WirelessTimeouts = WirelessTimeouts(),
    /** WiFi 阶段是否声明扬声器，规则与有线的 [ReceiverConfig.audio] 相同；null（默认）不声明。 */
    val audio: AudioSettings? = null,
    /** 额外声明的 DMSDP 服务（104 掩码命中时回 102），规则与有线的 [ReceiverConfig.extraDmsdpServices] 相同。 */
    val extraDmsdpServices: List<DmsdpConfig.Service> = emptyList(),
) {
    /** 本机 deviceId（64 位大写十六进制），与有线同一算法。 */
    val deviceId: String

    init {
        require(BluetoothAddress.format(BluetoothAddress.parse(bluetoothAddress)) != BluetoothAddress.UNAVAILABLE) {
            "无线连接必须用车机真实的蓝牙地址"
        }
        deviceId = NearbySession.deviceId(identity.host, bluetoothAddress)
        require(authEnvelopeVersion.isNotEmpty()) { "authEnvelopeVersion 不能为空" }
        if (pin != null) require(PinCodes.isValid(pin)) { "连接码必须是 6 位数字" }
        // WiFi 阶段的配置在会话途中才建；这里先用占位地址试建一遍，把显示参数等错误提前到启动时。
        wifiPhaseConfig(ReceiverConfig.WILDCARD)
    }

    /** 二维码内容（[PairingQrCode]），字段来自身份资产、[bluetoothAddress] 和本次的连接码。 */
    fun qrCode(pinCode: String): String = PairingQrCode.build(identity.host, bluetoothAddress, pinCode, supportsUsb)

    /**
     * WiFi 阶段内嵌的有线编排器用的配置：本机地址是车机在 WiFi 上的地址。它的控制监听由 [ControlLink.wifi] 决定
     * （随机端口），这里的控制端口只为满足 [ReceiverConfig] 的取值检查，不会用到。
     */
    internal fun wifiPhaseConfig(localIp: String): ReceiverConfig = ReceiverConfig(
        identity = identity,
        bluetoothAddress = bluetoothAddress,
        localIp = localIp,
        controlPort = UNUSED_CONTROL_PORT,
        display = display,
        authEnvelopeVersion = authEnvelopeVersion,
        trustStore = trustStore,
        controlBindHost = localIp,
        controlLocalFilter = false,
        random = random,
        wallClock = wallClock,
        timeZone = timeZone,
        touchInput = touchInput,
        keyInput = keyInput,
        mediaKeyInput = mediaKeyInput,
        audio = audio,
        extraDmsdpServices = extraDmsdpServices,
    )

    internal fun authConfig(pinCode: String): HiCarAuthConfig =
        HiCarAuthConfig(identity.authService, deviceId, authEnvelopeVersion, pinCode, usbConnection = false)

    /**
     * 认证握手应答里回给手机的车机信息：连接能力（只无线 1，无线和 USB 都支持 3）、完整车型编号、品牌。
     *
     * **只回这 3 个键，是实测得出的**（2026-11-24）：上游 0.2.4 曾在这里对齐官方 SDK 补进 15 个能力键
     * （CarBrand/DayNightMode/InputFeatures/PrimaryInput/isOnlyParkAllow + setCarDetail 的 FPS/WIDTH/HEIGHT/
     * BITRATE/CODECS/MIN/MAX_BITRATE/DPI/SCREEN_WIDTH/SCREEN_HEIGHT），结果手机 HiCar 按这些键走「融合桌面」
     * 判定链，投屏从全屏变成窗口化（1920x720 屏，桌面缩在中间带黑边），v23→v27 升级后立刻复现。
     * 官方星瑞 hicar_config.json 里 WIDTH/HEIGHT/FPS/BITRATE/CODECS 本来就是 null，这些键的语义（尤其
     * WIDTH/HEIGHT 的方向约定、DPI 的取值口径）没有实锤前不能带。以后要加回这些键（比如做音乐元数据需要
     * DAY_NIGHT_MODE），必须一次只加一个、连车验证全屏不回退。
     */
    internal fun authDeviceInfo(): Map<String, String> = linkedMapOf(
        "CONNECT_ABILITY_KEY" to if (supportsUsb) CONNECT_ABILITY_BOTH else CONNECT_ABILITY_WIRELESS,
        "CAR_MODE_ID" to identity.host.fullModel,
        "CAR_BRAND" to identity.host.brand,
    )

    override fun toString() = "WirelessReceiverConfig(画布=${display.width}x${display.height}@${display.fps})"

    private companion object {
        const val UNUSED_CONTROL_PORT = 65535
        const val CONNECT_ABILITY_WIRELESS = "1"
        const val CONNECT_ABILITY_BOTH = "3"
    }
}

/** 无线连接各步的超时（毫秒）。 */
class WirelessTimeouts(
    /** 认证子通道的端口回给手机之后，等手机连进来。超时就关监听、结束会话。 */
    val authChannelAcceptMillis: Long = 5_000,
    /** 一次认证的总时长，从认证子通道连上算起。 */
    val authMillis: Long = 120_000,
    /** 开热点，从认证通过、开始开热点时算起。 */
    val networkStartMillis: Long = 10_000,
    /** 从向手机要 MAC 起，到手机连上车机热点上的 TCP。手机连热点、拿地址可能要好几秒。 */
    val phoneJoinMillis: Long = 20_000,
    /**
     * 车机主动回连：手机同意开认证子通道后，车机去连它的时限。手机只等约 10 秒；按 SDP 查再连比按通道号直连慢，留到 9 秒。
     */
    val reconnectAuthConnectMillis: Long = 9_000,
    /** 车机主动回连：请手机开认证子通道之后，等手机应答（实测手机同意时 1 秒内回，要问用户时约 10 秒后回拒绝）。 */
    val reconnectAnswerMillis: Long = 20_000,
    /** 车机主动回连：手机暂时拒绝之后，控制通道留着等手机上的确认（原厂车机拨通后也是等 120 秒）。 */
    val reconnectConfirmMillis: Long = 120_000,
) {
    init {
        require(
            authChannelAcceptMillis > 0 && authMillis > 0 && networkStartMillis > 0 && phoneJoinMillis > 0 &&
                reconnectAuthConnectMillis > 0 && reconnectAnswerMillis > 0 && reconnectConfirmMillis > 0,
        ) {
            "超时必须为正"
        }
    }
}
