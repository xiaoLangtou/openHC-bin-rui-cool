package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.ReceiveBuffer

/**
 * 控制帧的流式拆帧器：TCP 一次读到的数据可能是半帧，也可能粘着好几帧。
 *
 * 用法：每读到一段数据就 [feed]，然后反复调 [next] 直到返回 null。
 * 出错时的处理与车机端一致：首字节不是 0x7F、CRC 不符、结构非法时，**清空整个缓冲**
 * 再抛 [MalformedMessageException]，连接不断，后面的新数据重新开始拆。
 *
 * 另有两条 OpenHC 自定的防护：载荷长度超过 [maxPayload]、标志段的值超过
 * [MAX_FLAG_VALUE] 字节时当作非法，防止对端用一个巨大的长度让我们无限攒缓冲。
 */
internal class NearbyFrameDecoder(private val maxPayload: Int = DEFAULT_MAX_PAYLOAD) {
    private val buffer = ReceiveBuffer(1024)

    init {
        require(maxPayload in 0..Ea.MAX_VALUE) { "maxPayload 越界: $maxPayload" }
    }

    /** 缓冲里还没拆成帧的字节数。 */
    val buffered: Int get() = buffer.size

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        if (length == 0) return
        buffer.append(data, offset, length)
    }

    /**
     * 取下一帧；数据还不够一帧时返回 null。
     * 数据非法时清空缓冲并抛 [MalformedMessageException]。
     */
    fun next(): NearbyFrame? {
        if (buffer.size == 0) return null
        try {
            return parse()
        } catch (e: MalformedMessageException) {
            clear()
            throw e
        }
    }

    fun clear() = buffer.clear()

    private fun parse(): NearbyFrame? {
        val buf = buffer.array
        val p = buffer.start
        val end = buffer.end
        if (buf[p].toInt() and 0xFF != NearbyFrame.HEAD) {
            throw MalformedMessageException("首字节不是 0x7F")
        }
        var off = p + 1
        val outerType = decodeEa(buf, off, end, "外层类型", p) ?: return null
        off += outerType.size
        val flag = decodeEa(buf, off, end, "标志", p) ?: return null
        off += flag.size
        val flagLength = decodeEa(buf, off, end, "标志段长度", p) ?: return null
        off += flagLength.size
        if (flagLength.value > MAX_FLAG_VALUE) {
            throw MalformedMessageException("标志段的值 ${flagLength.value} 字节，超过上限 $MAX_FLAG_VALUE")
        }
        if (end - off < flagLength.value) return null
        val flagValue = buf.copyOfRange(off, off + flagLength.value)
        off += flagLength.value
        val payloadLength = decodeEa(buf, off, end, "载荷长度", p) ?: return null
        off += payloadLength.size
        if (payloadLength.value > maxPayload) {
            throw MalformedMessageException("载荷 ${payloadLength.value} 字节，超过上限 $maxPayload")
        }
        if (end - off < payloadLength.value + CRC_SIZE) return null

        val payloadEnd = off + payloadLength.value
        val got = (buf[payloadEnd].toInt() and 0xFF) or ((buf[payloadEnd + 1].toInt() and 0xFF) shl 8)
        val want = Crc16Arc.compute(buf, p, payloadEnd - p)
        if (got != want) {
            throw MalformedMessageException("CRC 不符：帧内 %04x，算得 %04x".format(got, want))
        }
        val frame = NearbyFrame(outerType.value, flag.value, flagValue, buf.copyOfRange(off, payloadEnd))
        // 加密帧拿不到合格的种子就没法解密，同样按拆帧失败处理。
        frame.seed
        buffer.consume(payloadEnd + CRC_SIZE - p)
        return frame
    }

    /** 报错时写明是帧头哪个字段、从帧内第几个字节起（缓冲数组里的下标看诊断日志时对不上）。 */
    private fun decodeEa(buf: ByteArray, offset: Int, end: Int, field: String, frameStart: Int): EaValue? {
        val packed = try {
            Ea.tryDecode(buf, offset, end)
        } catch (e: MalformedMessageException) {
            throw MalformedMessageException(
                "拆帧：$field 从帧内第 ${offset - frameStart} 字节起连续 ${Ea.MAX_BYTES} 字节都没有终止位", e,
            )
        }
        return if (packed < 0) null else EaValue((packed ushr 8).toInt(), (packed and 0xFF).toInt())
    }

    private class EaValue(val value: Int, val size: Int)

    companion object {
        /** 控制通道上的消息都很小（手机首包 163 字节），64 KiB 足够宽裕。 */
        const val DEFAULT_MAX_PAYLOAD = 64 * 1024

        /** 标志段的值目前只有 10 字节（种子 TLV）。 */
        const val MAX_FLAG_VALUE = 256

        private const val CRC_SIZE = 2
    }
}
