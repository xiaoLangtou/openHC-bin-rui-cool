package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex

/**
 * 手机在数据通道协商请求（资源层命令 1）里报的 WiFi 能力。能力串是一串 `EA(tag) EA(值)`，没有长度字段：
 *
 * | tag | 含义 | 值 |
 * |---|---|---|
 * | 1 | 能当组主 | 1 = 能 |
 * | 2 | 能当组成员 | 1 = 能 |
 * | 3 | 支持 2.4 GHz | 1 = 支持 |
 * | 4 | 支持 5 GHz | 1 = 支持 |
 * | 5 | 5 GHz 信道位图 | 7 位一组、低位组在前、末字节最高位为 1（与 EA 同一种写法，但可以超过 4 字节）；位序见 [WifiChannels.CHANNELS_5G] |
 * | 6 | 双频同时工作 | 1 = 支持 |
 * | 7 | 发送方当前连着的 WiFi 频率（MHz） | 0 = 没连 |
 *
 * 新版手机还会带别的 tag（含义未知），原样放在 [otherFields]。没出现的 tag 对应字段为 null；
 * 同一个 tag 出现多次时以最后一次为准。
 *
 * 值都按 tag 5 那种写法读（7 位一组、末字节最高位为 1，最多 9 字节）：它包含 EA，现有手机报的值读出来不变，
 * 但别的 tag 的值超过 4 字节时也读得了，后面的字段不会错位。从某个字节起还是解不了时（格式不认识），
 * 只用前面解出来的字段，[unparsedFrom] 记下从哪里停的，[incompleteRaw] 留着整串原文写诊断日志。
 */
class WifiCapability(
    val canBeGroupOwner: Boolean? = null,
    val canBeGroupClient: Boolean? = null,
    val supports2g: Boolean? = null,
    val supports5g: Boolean? = null,
    /** 信道位图原值；没有 tag 5 时为 null。 */
    val channelBitmap: Long? = null,
    val dualBand: Boolean? = null,
    /** 手机当前连着的 WiFi 频率（MHz），0 表示没连；没有 tag 7 时为 null。 */
    val connectedFrequencyMhz: Int? = null,
    otherFields: Map<Int, Long> = emptyMap(),
    /** 能力串从第几个字节起解不了（后面的忽略）；整串都解开了为 null。 */
    val unparsedFrom: Int? = null,
    incompleteRaw: ByteArray? = null,
) {
    /** 不认识的 tag 和它的值。 */
    val otherFields: Map<Int, Long> = LinkedHashMap(otherFields)

    private val raw = incompleteRaw?.copyOf()

    /** 没解完整时的整串能力原文（写诊断日志用：能力串里只有能力和频率，没有地址）；解完整时为 null。 */
    val incompleteRaw: ByteArray? get() = raw?.copyOf()

    /** 位图里已知含义的 5 GHz 信道；没有 tag 5 时为 null。 */
    val channels5g: Set<Int>? get() = channelBitmap?.let { WifiChannels.channelsOf(it) }

    /**
     * 手机按它报的能力能不能用这个频率：2.4 GHz 只要手机没明说不支持；5 GHz 要手机报了支持，
     * 而且报了信道位图时这个信道在位图里。
     */
    fun canUse(frequencyMhz: Int): Boolean = when {
        WifiChannels.is2g(frequencyMhz) -> supports2g != false
        WifiChannels.is5g(frequencyMhz) -> {
            val channels = channels5g
            supports5g == true && (channels == null || WifiChannels.channelOf(frequencyMhz) in channels)
        }
        else -> false
    }

    override fun toString(): String {
        val head = "WifiCapability(组主=$canBeGroupOwner, 组成员=$canBeGroupClient, 2.4G=$supports2g, 5G=$supports5g, " +
            "5G 信道=${channels5g}, 双频=$dualBand, 当前频率=$connectedFrequencyMhz, 其他 tag=$otherFields"
        val at = unparsedFrom ?: return "$head)"
        return "$head，第 $at 字节起解不了，原文 ${raw?.toHex()})"
    }
}

/** 拒绝数据通道协商时回给手机的结果值。 */
enum class DataChannelRefusal(val code: Int) {
    /** 正忙（例如上一次的数据通道还没清掉、认证还没完成）。 */
    BUSY(2),

    /** 没有可用的数据通道（例如请求的不是 WiFi，或者双方找不到能用的频率）。 */
    NO_CHANNEL(255),
}

/**
 * 资源层（外层类型 4）的数据通道协商，只在蓝牙控制通道上出现，车机是应答方：
 *
 * ```
 * 命令 1 请求（手机 → 车机）：EA(数据通道) TLV(3, 能力串)
 * 命令 2 应答（车机 → 手机）：接受 EA(数据通道) EA(1) TLV(3, 角色 1 字节 ‖ EA(频率 MHz))
 *                             拒绝 EA(数据通道) EA(2 忙 / 255 没有可用数据通道)
 * 命令 7 结果（手机 → 车机）：EA(数据通道) EA(结果) [TLV(3, 角色 1 字节 ‖ EA(频率 MHz))]
 * ```
 *
 * 数据通道 3 = WiFi。角色是发送方自己的角色（1 = 组主，2 = 组成员），是裸字节，不是 EA；频率是 EA。
 * 结果 1 = 成功，255 = 没有可用数据通道，其他 = 对端忙。手机照抄车机应答里的频率去找网。
 */
internal object DataChannelMessages {
    const val CMD_REQUEST = 1
    const val CMD_RESPONSE = 2
    const val CMD_RESULT = 7

    const val TAG_DETAIL = 3

    const val RESULT_OK = 1

    const val TAG_GROUP_OWNER = 1
    const val TAG_GROUP_CLIENT = 2
    const val TAG_2G = 3
    const val TAG_5G = 4
    const val TAG_CHANNEL_BITMAP = 5
    const val TAG_DUAL_BAND = 6
    const val TAG_CONNECTED_FREQUENCY = 7

    /** 信道位图最多读这么多字节（63 位），再长按格式错误处理。 */
    private const val MAX_BITMAP_BYTES = 9

    class Request(val dataChannel: Int, val capability: WifiCapability)

    class Result(val dataChannel: Int, val result: Int, val role: Int?, val frequencyMhz: Int?)

    /** 解析命令 1 的值。必须带 TLV 3（能力串）；其他 TLV 忽略。能力串内容解不完整不算错（见 [parseCapability]）。 */
    fun parseRequest(value: ByteArray): Request {
        val r = ByteReader(value)
        val dataChannel = r.ea()
        val detail = Tlv.parseAll(r.rest()).firstOrNull { it.tag == TAG_DETAIL }
            ?: throw MalformedMessageException("数据通道协商请求里没有能力串（TLV 3）")
        return Request(dataChannel, parseCapability(detail.valueView()))
    }

    /** 解析能力串（格式见 [WifiCapability]）。不因格式抛异常：从解不了的字节起忽略，只用前面解出来的字段。 */
    fun parseCapability(bytes: ByteArray): WifiCapability {
        val r = ByteReader(bytes)
        val values = LinkedHashMap<Int, Long>()
        var bitmap: Long? = null
        var stoppedAt: Int? = null
        while (r.remaining > 0) {
            val start = r.position
            try {
                val tag = r.ea()
                val value = readBitmap(r)
                if (tag == TAG_CHANNEL_BITMAP) bitmap = value else values[tag] = value
            } catch (e: MalformedMessageException) {
                stoppedAt = start
                break
            }
        }
        fun flag(tag: Int): Boolean? = values.remove(tag)?.let { it != 0L }
        val frequency = values.remove(TAG_CONNECTED_FREQUENCY)
        return WifiCapability(
            canBeGroupOwner = flag(TAG_GROUP_OWNER),
            canBeGroupClient = flag(TAG_GROUP_CLIENT),
            supports2g = flag(TAG_2G),
            supports5g = flag(TAG_5G),
            channelBitmap = bitmap,
            dualBand = flag(TAG_DUAL_BAND),
            connectedFrequencyMhz = frequency?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
            otherFields = values,
            unparsedFrom = stoppedAt,
            incompleteRaw = if (stoppedAt != null) bytes else null,
        )
    }

    /** 命令 2：接受，带上车机的角色和选定的频率（含外层 TLV）。 */
    fun acceptPayload(dataChannel: Int, role: Int, frequencyMhz: Int): ByteArray {
        require(role in 0..0xFF) { "角色是 1 字节: $role" }
        val detail = ByteWriter(6).u8(role).ea(frequencyMhz).toByteArray()
        val value = ByteWriter(12).ea(dataChannel).ea(RESULT_OK).tlv(TAG_DETAIL, detail).toByteArray()
        return encodeTlv(CMD_RESPONSE, value)
    }

    /** 命令 2：拒绝（含外层 TLV）。 */
    fun refusePayload(dataChannel: Int, refusal: DataChannelRefusal): ByteArray =
        encodeTlv(CMD_RESPONSE, ByteWriter(8).ea(dataChannel).ea(refusal.code).toByteArray())

    /** 解析命令 7 的值。TLV 3 可以没有（失败时）；有时必须正好是角色 1 字节加一个 EA 频率。 */
    fun parseResult(value: ByteArray): Result {
        val r = ByteReader(value)
        val dataChannel = r.ea()
        val result = r.ea()
        val detail = Tlv.parseAll(r.rest()).firstOrNull { it.tag == TAG_DETAIL }
            ?: return Result(dataChannel, result, null, null)
        val d = ByteReader(detail.valueView())
        val role = d.u8()
        val frequency = d.ea()
        if (d.remaining > 0) throw MalformedMessageException("协商结果的 TLV 3 末尾多出 ${d.remaining} 字节")
        return Result(dataChannel, result, role, frequency)
    }

    /** 测试和以后车机当发起方时用：组命令 1 的值。 */
    fun requestValue(dataChannel: Int, capability: ByteArray): ByteArray =
        ByteWriter(capability.size + 8).ea(dataChannel).tlv(TAG_DETAIL, capability).toByteArray()

    /** 信道位图：7 位一组、低位组在前，最高位为 1 的字节是最后一个。 */
    fun readBitmap(r: ByteReader): Long {
        var value = 0L
        for (i in 0 until MAX_BITMAP_BYTES) {
            val b = r.u8()
            value = value or ((b and 0x7F).toLong() shl (7 * i))
            if (b and 0x80 != 0) return value
        }
        throw MalformedMessageException("信道位图连续 $MAX_BITMAP_BYTES 字节都没有终止位")
    }

    fun writeBitmap(w: ByteWriter, bitmap: Long): ByteWriter {
        require(bitmap >= 0) { "位图不能为负" }
        var rest = bitmap
        while (true) {
            val group = (rest and 0x7F).toInt()
            rest = rest ushr 7
            if (rest == 0L) return w.u8(group or 0x80)
            w.u8(group)
        }
    }
}
