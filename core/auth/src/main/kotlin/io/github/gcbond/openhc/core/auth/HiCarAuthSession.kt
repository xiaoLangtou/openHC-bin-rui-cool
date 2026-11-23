package io.github.gcbond.openhc.core.auth

import io.github.gcbond.openhc.core.auth.hichain.CompactJson
import io.github.gcbond.openhc.core.auth.hichain.parseJsonObject
import io.github.gcbond.openhc.core.auth.hichain.exactString
import io.github.gcbond.openhc.core.auth.hichain.strictInt
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.SecureRandom

/**
 * 一条认证连接上的 HiCar 认证会话（外层 `oper_type` 消息）。
 *
 * 承载：手机在控制通道上请求为业务 19（标签 `hicarAuth`）新开一条连接，车机监听并回端口，手机连进来；
 * 有线时是一条 TCP，无线时是蓝牙上的一条 RFCOMM 子通道，上面的格式完全相同。
 * 之后这条连接上的字节全部交给本类（[feed]），本类返回要写回同一条连接的字节（[HiCarAuthAction.Send]）。
 * 消息格式：
 * - 手机 → 车机 `oper_type=1` 设备握手：`data` 是 JSON 字符串，内含 `deviceId`、`has_pin`、`usb_connection` 等；
 * - 车机 → 手机 `oper_type=1` 应答：`version, discover_type, oper_type, data` + `usb_connection`；
 *   有线首次配对再加 `pin_code`（6 位数字字符串），无线不带（手机从二维码或用户输入拿到连接码）；
 *   `data` 是车机信息（值全是字符串）的 JSON 文本；
 * - 双向 `oper_type=3`：`data` 是一条认证库消息（HiChain JSON）的文本，交给 [AccessoryAuthenticator]。
 *
 * 握手里的 `usb_connection` 必须和 [HiCarAuthConfig.usbConnection] 一致，否则按
 * [AuthFailure.UnsupportedConnection] 失败。
 *
 * 重发的设备握手：手机刚从免码回连改走输码时，会在约 150 ms 内把设备握手连发两遍、中间没有认证数据（2026-11 用户日志）。
 * 认证数据开始交换之前再来的握手，按新的一次重新应答（新建认证器，连接码沿用第一次的），一条连接上最多 [MAX_HANDSHAKES] 次；
 * 认证数据开始交换之后再来、次数超了、或者换了 deviceId，仍按协议错误结束。
 *
 * 发出的 JSON 是紧凑格式、按上面的键顺序，非 ASCII 字符原样输出（UTF-8）。
 * 不做 I/O、不开线程；非线程安全。会话结束（成功、失败或抛出异常）后，再喂入的数据一律忽略。
 */
class HiCarAuthSession(
    private val config: HiCarAuthConfig,
    private val authenticators: AccessoryAuthenticatorFactory,
    /** 只用来在配置没给连接码时生成连接码。 */
    private val random: SecureRandom,
    /** 由握手内容决定回给手机的车机信息（例如车型、品牌、桌面能力），值都是字符串。 */
    private val deviceInfo: (PhoneHandshake) -> Map<String, String>,
) {
    private val framer = AuthJsonFramer()
    private var authenticator: AccessoryAuthenticator? = null
    private var handshake: PhoneHandshake? = null
    private var mode: AuthMode? = null

    /** 本轮已经处理过认证库消息（`oper_type=3`）：之后再来设备握手就是协议错误。 */
    private var hiChainSeen = false

    /** 这条连接上收到的设备握手次数（含重发）。 */
    private var handshakes = 0

    /** 配置没给连接码时第一次生成的连接码：重发握手时沿用，免得屏幕上显示的码变了。 */
    private var generatedPin: String? = null

    var isFinished: Boolean = false
        private set

    /**
     * 喂入认证 TCP 上收到的字节（可以是任意切分）。
     *
     * @return 按顺序执行的动作。
     * @throws MalformedMessageException 对端数据不合法（分帧、JSON、字段类型或消息顺序不对）。会话随即结束。
     */
    @Throws(MalformedMessageException::class)
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<HiCarAuthAction> {
        if (isFinished) return emptyList()
        try {
            val actions = ArrayList<HiCarAuthAction>()
            for (message in framer.feed(bytes, offset, length)) {
                if (isFinished) break
                actions += onJsonMessage(message)
            }
            return actions
        } catch (e: MalformedMessageException) {
            isFinished = true
            throw e
        }
    }

    /** 认证 TCP 被关闭时调用。会话还没结束就按失败处理。 */
    fun onConnectionClosed(): List<HiCarAuthAction> {
        if (isFinished) return emptyList()
        isFinished = true
        return listOf(HiCarAuthAction.Failed(AuthFailure.ConnectionClosed(framer.hasPendingData)))
    }

    /** 处理一条完整的外层 JSON 消息。 */
    internal fun onJsonMessage(text: String): List<HiCarAuthAction> {
        val root = parseJsonObject(text, "认证外层消息")
        val operType = root["oper_type"].strictInt() ?: throw MalformedMessageException("缺少整数 oper_type")
        val data = root.exactString("data") ?: throw MalformedMessageException("缺少字符串 data")
        return when (operType) {
            OPER_HANDSHAKE -> onHandshake(root, data)
            OPER_HICHAIN -> onHiChain(data)
            else -> throw MalformedMessageException("不支持的 oper_type: $operType")
        }
    }

    private fun onHandshake(root: JsonObject, data: String): List<HiCarAuthAction> {
        val body = parseJsonObject(data, "设备握手")
        val hasPin = body.booleanField("has_pin")
        val usbConnection = body.booleanField("usb_connection")
        val version = root.exactString("version")
        // 诊断只记这几个标志，不记 deviceId（测试锁了这个隐私约束）。v25 一度把完整 JSON 也打进日志，
        // 泄漏手机身份，2026-11-24 随 _launcher 一并回退；要看的字段（_launcher 等）显式挑出来记。
        val flags = "has_pin=$hasPin，usb_connection=$usbConnection，version=${version ?: "无"}"
        val previous = handshake
        if (previous != null && (hiChainSeen || handshakes >= MAX_HANDSHAKES)) {
            val why = if (hiChainSeen) "认证数据已经开始交换" else "这条连接上已经握手 $handshakes 次"
            throw MalformedMessageException("本轮认证出现重复的设备握手（上次 has_pin=${previous.hasPin}；这次 $flags；$why）")
        }
        val deviceId = body.exactString("deviceId")
            ?.takeIf { it.length in 1..AccessoryAuthConfig.MAX_AUTH_ID_BYTES && it.all { c -> c in ' '..'~' } }
            ?: throw MalformedMessageException("deviceId 必须是 1～64 个可打印 ASCII 字符")
        if (previous != null && deviceId != previous.deviceId) {
            throw MalformedMessageException("重发的设备握手换了 deviceId（上次 has_pin=${previous.hasPin}；这次 $flags）")
        }
        handshakes++
        val repeat = previous?.let {
            HiCarAuthAction.Note("手机在认证数据交换之前重发了设备握手（第 $handshakes 次；上次 has_pin=${it.hasPin}），按这次的握手重新应答")
        }
        authenticator = null
        mode = null
        val phone = PhoneHandshake(deviceId, hasPin, usbConnection, version, body)
        handshake = phone
        if (usbConnection != config.usbConnection) {
            // 有线会话收到无线握手，或反过来：两边对连接方式的理解不一致。
            isFinished = true
            return listOfNotNull(repeat, HiCarAuthAction.Note("手机握手：$flags"), HiCarAuthAction.Failed(AuthFailure.UnsupportedConnection))
        }
        val pin = if (hasPin) config.pin ?: (generatedPin ?: PinCodes.random(random).also { generatedPin = it }) else null
        val auth = authenticators.create(AccessoryAuthConfig(config.service, config.selfAuthId, deviceId, pin))
        val note = HiCarAuthAction.Note("手机握手：$flags；本机有这台手机的配对记录=${auth.peerTrusted}")
        if (!hasPin && !auth.peerTrusted) {
            // 手机要求免码回连，但本机没有它的信任记录：不回应答，由上层断开。
            isFinished = true
            return listOfNotNull(repeat, note, HiCarAuthAction.Failed(AuthFailure.NotTrusted))
        }
        authenticator = auth
        val authMode = if (hasPin) AuthMode.PIN_PAIRING else AuthMode.TRUSTED_RECONNECT
        mode = authMode
        val info = CompactJson.writeStringMap(deviceInfo(phone))
        val response = StringBuilder(envelopeHead(OPER_HANDSHAKE, info))
            .append(",\"usb_connection\":").append(config.usbConnection)
        // **不能**声明 "_launcher":true（超级桌面/融合桌面支持，2026-11-24 实锤）：声明了手机就切融合桌面渲染
        // （带小组件的 HiCar 桌面），其布局跟我们的画布对不上，投屏从全屏变窗口化（v23→v27 升级后立刻复现）。
        // 车辆磁贴、应用列表互通、515 元数据这些依赖 _launcher 的能力，等屏幕参数键（WIDTH/HEIGHT/DPI/
        // 物理宽高）按官方语义一条条实锤后再一起加回来。
        // 无线时手机已经从二维码或用户输入拿到连接码，车机不下发。
        if (pin != null && config.usbConnection) response.append(",\"pin_code\":").append(CompactJson.quote(pin))
        response.append('}')
        return listOfNotNull(
            repeat,
            note,
            HiCarAuthAction.Send(response.toString().toByteArray(Charsets.UTF_8)),
            HiCarAuthAction.HandshakeAnswered(deviceId, authMode, pin),
        )
    }

    private fun onHiChain(data: String): List<HiCarAuthAction> {
        val auth = authenticator ?: throw MalformedMessageException("设备握手之前收到认证数据")
        hiChainSeen = true
        val received = messageCode(data)
        val replies = ArrayList<String>(1)
        val actions = ArrayList<HiCarAuthAction>()
        for (action in auth.onMessage(data)) {
            when (action) {
                is AccessoryAction.Send -> {
                    // 认证库消息先规整成紧凑 JSON 再放进 data。
                    val parsed = parseJsonObject(action.message, "认证库消息")
                    replies += codeText(parsed["message"].strictInt())
                    val inner = CompactJson.write(parsed)
                    actions += HiCarAuthAction.Send((envelopeHead(OPER_HICHAIN, inner) + "}").toByteArray(Charsets.UTF_8))
                }
                is AccessoryAction.Progress -> Unit
                is AccessoryAction.Succeeded -> {
                    isFinished = true
                    actions += HiCarAuthAction.Succeeded(action.sessionKey, handshake!!.deviceId, mode!!)
                }
                is AccessoryAction.Failed -> {
                    isFinished = true
                    actions += HiCarAuthAction.Failed(action.failure)
                }
            }
        }
        val answer = if (replies.isEmpty()) "不回" else "回 ${replies.joinToString("、")}"
        actions.add(0, HiCarAuthAction.Note("认证库消息：收到 $received，$answer"))
        return actions
    }

    /** 认证库消息的消息码（诊断用）；解析不了不在这里报错，交给认证器按原样处理。 */
    private fun messageCode(text: String): String = try {
        codeText(parseJsonObject(text, "认证库消息")["message"].strictInt())
    } catch (e: MalformedMessageException) {
        "无法解析的消息"
    }

    private fun codeText(code: Int?): String =
        if (code == null) "无消息码" else "0x" + code.toString(16).uppercase().padStart(4, '0')

    /** `{"version":…,"discover_type":1,"oper_type":…,"data":"…"`（不含结尾的 `}`）。 */
    private fun envelopeHead(operType: Int, data: String): String =
        "{\"version\":${CompactJson.quote(config.envelopeVersion)},\"discover_type\":$DISCOVER_TYPE," +
            "\"oper_type\":$operType,\"data\":${CompactJson.quote(data)}"

    private fun JsonObject.booleanField(name: String): Boolean {
        val primitive = this[name] as? JsonPrimitive
        if (primitive == null || primitive.isString) throw MalformedMessageException("$name 必须是布尔值")
        return primitive.booleanOrNull ?: throw MalformedMessageException("$name 必须是布尔值")
    }

    private companion object {
        const val OPER_HANDSHAKE = 1
        const val OPER_HICHAIN = 3

        /** 车机应答里 discover_type 的取值。 */
        const val DISCOVER_TYPE = 1

        /** 一条认证连接上最多接受的设备握手次数（含重发）。 */
        const val MAX_HANDSHAKES = 3
    }
}

/** [HiCarAuthSession] 的配置。 */
class HiCarAuthConfig(
    /** 认证服务身份，来自身份资产（[io.github.gcbond.openhc.core.common.identity.IdentityAssets.authService]）。 */
    val service: AuthServiceIdentity,
    /** 车机认证身份（ASCII 原文，1～64 字节），由连接层按协议给出。 */
    val selfAuthId: String,
    /** 车机应答外层 `version` 字段的取值。协议常量，不在本模块写死，由调用方提供。 */
    val envelopeVersion: String,
    /**
     * 首次配对用的 6 位连接码；为 null 时会话在需要时自己随机生成（只限有线：车机在握手应答里把它发给手机）。
     * 无线时必须给出：就是二维码里、屏幕上显示的那一个。
     */
    val pin: String? = null,
    /**
     * 这条认证连接是不是有线（写进握手应答的 `usb_connection`）。默认有线；无线（蓝牙 RFCOMM 子通道）传 false：
     * 应答里 `usb_connection` 为 false、不带 `pin_code`，手机的握手也必须声明 `usb_connection=false`。
     */
    val usbConnection: Boolean = true,
) {
    init {
        AccessoryAuthConfig.requireAuthId(selfAuthId, "selfAuthId")
        require(envelopeVersion.isNotEmpty()) { "envelopeVersion 不能为空" }
        if (pin != null) require(PinCodes.isValid(pin)) { "连接码必须是 6 位 ASCII 数字" }
        require(usbConnection || pin != null) { "无线认证必须给出当前连接码（二维码和屏幕上显示的那一个）" }
    }

    override fun toString(): String = "HiCarAuthConfig(pin=${if (pin == null) "自动生成" else "已指定"})"
}

/** 手机的设备握手（`oper_type=1`）。[data] 是握手 `data` 的完整内容，供上层读取其他字段（例如桌面能力、屏幕尺寸）。 */
class PhoneHandshake(
    val deviceId: String,
    val hasPin: Boolean,
    val usbConnection: Boolean,
    /** 外层的 `version` 字段（没有则为 null）。 */
    val version: String?,
    val data: JsonObject,
) {
    override fun toString(): String = "PhoneHandshake(hasPin=$hasPin, usb=$usbConnection)"
}

enum class AuthMode {
    /** 首次连接：6 位连接码 + 长期身份交换。 */
    PIN_PAIRING,

    /** 再次连接：用已保存的信任关系免码认证。 */
    TRUSTED_RECONNECT,
}

/** [HiCarAuthSession] 返回的动作，按顺序执行。 */
sealed class HiCarAuthAction {
    /** 写回认证 TCP 的字节（一条完整的外层 JSON，UTF-8，不加分隔符）。握手应答里含连接码，不要写日志。 */
    class Send(val bytes: ByteArray) : HiCarAuthAction() {
        override fun toString(): String = "Send(${bytes.size} 字节)"
    }

    /** 已回应设备握手。[pinCode] 只在首次配对时有值，界面可以显示。 */
    class HandshakeAnswered(val peerAuthId: String, val mode: AuthMode, val pinCode: String?) : HiCarAuthAction() {
        override fun toString(): String = "HandshakeAnswered(mode=$mode)"
    }

    /** 认证成功。[sessionKey] 是 16 字节会话密钥（用于之后各层的帧加密），不要写日志，用完清零。 */
    class Succeeded(val sessionKey: ByteArray, val peerAuthId: String, val mode: AuthMode) : HiCarAuthAction() {
        override fun toString(): String = "Succeeded(mode=$mode, sessionKey=${sessionKey.size} 字节)"
    }

    class Failed(val failure: AuthFailure) : HiCarAuthAction() {
        override fun toString(): String = "Failed($failure)"
    }

    /** 只用于诊断的说明（握手标志、认证库消息码；不含 deviceId、连接码、密钥），可以直接写日志。 */
    class Note(val text: String) : HiCarAuthAction() {
        override fun toString(): String = "Note($text)"
    }
}

/** 6 位连接码。 */
object PinCodes {
    const val LENGTH = 6

    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** 均匀随机的 6 位数字（保留前导零）。 */
    fun random(random: SecureRandom): String = random.nextInt(1_000_000).toString().padStart(LENGTH, '0')
}
