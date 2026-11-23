package io.github.gcbond.openhc.core.hisight.video

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr

/**
 * 视频连接的流式拆包：`BE32 RTP 包长 || RTP 包`，RTP 载荷按 marker 拼成 PES，
 * PES 头是明文，只有其后的 ES 是 `IV16 || AES-CTR 密文`。
 *
 * 缓冲策略：只把变长的 RTP 头（固定 12 字节、扩展头 4 字节）暂存在小数组里，CSRC 和扩展内容按
 * 计数跳过；RTP 载荷从调用方的数组直接拷进可复用的 PES 缓冲（每个字节只拷这一次）；marker 到达后
 * 把 ES 解密到另一块可复用的输出缓冲，经 [AccessUnit] 交出。两块缓冲按需倍增、不缩小，上限 10MiB。
 *
 * 容错策略（见上游 issue #3 的偶发断连）：丢包、重复包、乱序、SSRC 变化、同一 PES 内时间戳变化
 * 都按「丢掉半截 PES、丢弃载荷直到下一个 marker、重新对齐」处理——最多丢一帧，绝不终结会话。
 * 结构性错误（版本/包长/PES 头/缓冲超限）仍然判失败，失败后不能继续使用。
 * 序号回绕按模 65536 计算，同时兼容华为的 64999→0 与标准 RTP 的 65535→0。
 */
internal class VideoDepacketizer(sessionKey: ByteArray, private val sink: AccessUnitSink) {
    private val cipher = AesCtr(sessionKey)
    private val unit = AccessUnit()

    private var state = STATE_LENGTH
    private val head = ByteArray(12)
    private var headFill = 0
    private var packetLength = 0
    private var headerLength = 0
    private var skipRemaining = 0
    private var payloadRemaining = 0

    private var csrcCount = 0
    private var hasExtension = false
    private var marker = false
    private var sequence = 0
    private var timestamp = 0L
    private var ssrc = 0L

    private var lastSequence = -1
    private var streamSsrc = -1L
    private var pesTimestamp = 0L
    private var pesFirstSequence = 0

    /** 丢包重对齐中：丢弃载荷直到下一个 marker（该 marker 属于已损坏的帧，同样丢弃）。 */
    private var discardUntilMarker = false

    private var pes = ByteArray(INITIAL_BUFFER)
    private var pesLength = 0
    private var es = ByteArray(INITIAL_BUFFER)

    var failed = false
        private set
    var framesDelivered = 0L
        private set
    var packetsReceived = 0L
        private set
    var bytesReceived = 0L
        private set

    /** 丢包重对齐次数（每次最多丢一帧；频繁增长说明链路质量差，不影响会话存活）。 */
    var resyncs = 0L
        private set
    /** 因重对齐丢弃的 RTP 包总数。 */
    var packetsDropped = 0L
        private set

    fun feed(data: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "范围非法" }
        if (failed) throw MalformedMessageException("视频流已经失败，不能继续使用")
        bytesReceived += length
        try {
            consume(data, offset, offset + length)
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    /** 连接关闭时调用：残留半个包或缺 marker 的 PES 都算失败。 */
    fun finish() {
        if (failed) throw MalformedMessageException("视频流已经失败")
        if (state != STATE_LENGTH || headFill != 0) throw MalformedMessageException("视频连接结束时仍有未收完的 RTP 包")
        if (pesLength != 0) throw MalformedMessageException("视频连接结束时 PES 缺少结尾 marker")
    }

    private fun consume(data: ByteArray, from: Int, to: Int) {
        var pos = from
        while (pos < to) {
            when (state) {
                STATE_LENGTH -> {
                    pos = fillHead(data, pos, to, 4)
                    if (headFill == 4) {
                        headFill = 0
                        val length = be32(head, 0)
                        if (length < 1 || length > MAX_PACKET) {
                            throw MalformedMessageException("视频 RTP 包长 $length 不在 1..10MiB")
                        }
                        if (length < 12) throw MalformedMessageException("视频 RTP 包不足 12 字节")
                        packetLength = length.toInt()
                        state = STATE_FIXED_HEADER
                    }
                }
                STATE_FIXED_HEADER -> {
                    pos = fillHead(data, pos, to, 12)
                    if (headFill == 12) {
                        headFill = 0
                        parseFixedHeader()
                    }
                }
                STATE_SKIP_CSRC, STATE_SKIP_EXTENSION -> {
                    val n = minOf(skipRemaining, to - pos)
                    pos += n
                    skipRemaining -= n
                    if (skipRemaining == 0) {
                        if (state == STATE_SKIP_CSRC && hasExtension) {
                            state = STATE_EXTENSION_HEADER
                        } else {
                            beginPayload()
                        }
                    }
                }
                STATE_EXTENSION_HEADER -> {
                    pos = fillHead(data, pos, to, 4)
                    if (headFill == 4) {
                        headFill = 0
                        val profile = be16(head, 0)
                        val extensionLength = be16(head, 2)
                        // 扩展长度的单位是字节，不乘 4；profile 只要求最低位为 1。
                        if (profile and 1 != 1) throw MalformedMessageException("视频 RTP 扩展 profile 最低位不是 1")
                        headerLength += 4 + extensionLength
                        if (headerLength >= packetLength) throw MalformedMessageException("视频 RTP 扩展截断或缺少载荷")
                        skipRemaining = extensionLength
                        if (skipRemaining > 0) state = STATE_SKIP_EXTENSION else beginPayload()
                    }
                }
                STATE_PAYLOAD, STATE_DISCARD_PAYLOAD -> {
                    val n = minOf(payloadRemaining, to - pos)
                    if (state == STATE_PAYLOAD) {
                        System.arraycopy(data, pos, pes, pesLength, n)
                        pesLength += n
                    }
                    pos += n
                    payloadRemaining -= n
                    if (payloadRemaining == 0) {
                        if (state == STATE_PAYLOAD) packetsReceived++ else packetsDropped++
                        state = STATE_LENGTH
                        if (marker) {
                            if (discardUntilMarker) {
                                // 损坏帧的收尾包也丢弃，下一个包从干净的 PES 头开始。
                                discardUntilMarker = false
                                pesLength = 0
                            } else deliver()
                        }
                    }
                }
            }
        }
    }

    private fun fillHead(data: ByteArray, pos: Int, to: Int, size: Int): Int {
        val n = minOf(size - headFill, to - pos)
        System.arraycopy(data, pos, head, headFill, n)
        headFill += n
        return pos + n
    }

    private fun parseFixedHeader() {
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        if (b0 ushr 6 != 2) throw MalformedMessageException("视频 RTP 版本不是 2")
        if (b0 and 0x20 != 0) throw MalformedMessageException("视频 RTP 带 padding，未实现")
        if (b1 and 0x7F != PAYLOAD_TYPE) throw MalformedMessageException("视频 RTP 载荷类型不是 96")
        hasExtension = b0 and 0x10 != 0
        csrcCount = b0 and 0x0F
        marker = b1 and 0x80 != 0
        sequence = be16(head, 2)
        timestamp = be32(head, 4)
        ssrc = be32(head, 8)
        headerLength = 12 + 4 * csrcCount
        if (headerLength >= packetLength) throw MalformedMessageException("视频 RTP 的 CSRC 截断或缺少载荷")
        if (hasExtension && headerLength + 4 >= packetLength) throw MalformedMessageException("视频 RTP 扩展头截断")
        skipRemaining = 4 * csrcCount
        when {
            skipRemaining > 0 -> state = STATE_SKIP_CSRC
            hasExtension -> state = STATE_EXTENSION_HEADER
            else -> beginPayload()
        }
    }

    /**
     * RTP 头读完：连续性检查不通过就进入丢弃重对齐（不终结会话），通过则把载荷拷进 PES。
     * 序号按模 65536 判断连续，天然兼容标准 65535→0 回绕；华为的 64999→0 回绕单独特判。
     */
    private fun beginPayload() {
        val payloadLength = packetLength - headerLength
        val delta = (sequence - lastSequence) and 0xFFFF
        val contiguous = delta == 1 || (lastSequence == HUAWEI_LAST_SEQUENCE && sequence == 0)
        val resync = !discardUntilMarker && lastSequence >= 0 && (
            !contiguous ||                                      // 丢包、重复、乱序
                (streamSsrc >= 0 && ssrc != streamSsrc) ||      // SSRC 变化
                (pesLength > 0 && timestamp != pesTimestamp)    // 同一 PES 内时间戳变化
            )
        if (resync) {
            resyncs++
            discardUntilMarker = true
            pesLength = 0
        }
        if (pesLength == 0) {
            pesFirstSequence = sequence
            pesTimestamp = timestamp
        }
        lastSequence = sequence
        streamSsrc = ssrc
        if (discardUntilMarker) {
            payloadRemaining = payloadLength
            state = STATE_DISCARD_PAYLOAD
            return
        }
        if (pesLength.toLong() + payloadLength > MAX_PES) throw MalformedMessageException("未完成的 PES 超过 10MiB")
        if (pes.size < pesLength + payloadLength) pes = grow(pes, pesLength, pesLength + payloadLength)
        payloadRemaining = payloadLength
        state = STATE_PAYLOAD
    }

    /**
     * PES 头：`00 00 01`、stream_id、BE16 长度（0 表示不声明，否则长度 + 6 等于整个 PES）、
     * 第 6 字节固定 0x84、第 7 字节含 PTS 标志、第 8 字节、第 9..13 字节为 PTS。ES 一般从第 14 字节开始；
     * 第 7 字节最低位和第 8 字节都非 0 时，第 14 字节必须是 0x8e，ES 从 15 + 第 8 字节开始。
     * 这不是标准的 9 + header_data_length 算法。
     */
    private fun deliver() {
        val p = pes
        val n = pesLength
        if (n < 14 || p[0].toInt() != 0 || p[1].toInt() != 0 || p[2].toInt() != 1) {
            throw MalformedMessageException("视频 PES 头截断或起始码错误")
        }
        val declared = be16(p, 4)
        if (declared != 0 && declared + 6 != n) throw MalformedMessageException("视频 PES 声明长度与实际不符")
        val flags = p[7].toInt() and 0xFF
        if (p[6].toInt() and 0xFF != 0x84 || flags and 0x80 == 0) {
            throw MalformedMessageException("视频 PES 标志不是 0x84 或缺少 PTS")
        }
        val pts = ((p[9].toLong() and 0x06) shl 29) or ((p[10].toLong() and 0xFF) shl 22) or
            ((p[11].toLong() and 0xFE) shl 14) or ((p[12].toLong() and 0xFF) shl 7) or
            ((p[13].toLong() and 0xFE) shr 1)
        val headerData = p[8].toInt() and 0xFF
        var esOffset = 14
        if (flags and 1 != 0 && headerData > 0) {
            if (headerData == 1 || n <= 14 || p[14].toInt() and 0xFF != 0x8E) {
                throw MalformedMessageException("视频 PES 扩展长度或 0x8e 标识错误")
            }
            esOffset = 15 + headerData
        }
        if (esOffset + AesCtr.IV_BYTES >= n) throw MalformedMessageException("视频 PES 缺少完整 IV 或 ES 为空")
        val cipherStart = esOffset + AesCtr.IV_BYTES
        val esLength = n - cipherStart
        if (es.size < esLength) es = grow(es, 0, esLength)
        cipher.decryptInto(p, esOffset, p, cipherStart, esLength, es, 0)

        unit.data = es
        unit.offset = 0
        unit.length = esLength
        unit.rtpTimestamp = timestamp
        unit.pts90k = pts
        unit.firstSequence = pesFirstSequence
        unit.lastSequence = sequence
        sink.onAccessUnit(unit)
        framesDelivered++
        pesLength = 0
    }

    private fun grow(buffer: ByteArray, keep: Int, needed: Int): ByteArray {
        var cap = buffer.size
        while (cap < needed) cap = minOf(cap * 2, MAX_PES.toInt())
        val out = ByteArray(cap)
        System.arraycopy(buffer, 0, out, 0, keep)
        return out
    }

    private fun be16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    companion object {
        const val MAX_PACKET = 10L * 1024 * 1024
        /** 本机资源限制（不是协议字段）：未完成的 PES 最多 10MiB。 */
        const val MAX_PES = MAX_PACKET
        private const val PAYLOAD_TYPE = 96
        /** 华为手机 RTP 序号回绕点（不是标准 65535）。 */
        const val HUAWEI_LAST_SEQUENCE = 64999
        private const val INITIAL_BUFFER = 256 * 1024

        private const val STATE_LENGTH = 0
        private const val STATE_FIXED_HEADER = 1
        private const val STATE_SKIP_CSRC = 2
        private const val STATE_EXTENSION_HEADER = 3
        private const val STATE_SKIP_EXTENSION = 4
        private const val STATE_PAYLOAD = 5
        private const val STATE_DISCARD_PAYLOAD = 6
    }
}
