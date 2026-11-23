package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 控制帧的组帧与流式拆帧。 */
class FrameTest {

    private val cipher = NearbyFrameCipher(Vectors.KEYS_16)

    @Test
    fun plainFrameMatchesReference() {
        val frame = NearbyFrame.plain(0, encodeTlv(15, "123456".toByteArray()))
        assertEquals(Vectors.CONNECT_ID_123456, frame.encode().toHex())
        assertEquals("7f808080", frame.encode().copyOfRange(0, 4).toHex())
    }

    @Test
    fun encryptedFramesMatchReference() {
        val tlv7 = cipher.seal(0, hex(Vectors.PAYLOAD_TLV7), Vectors.SEED_A)
        assertEquals(Vectors.CAR_TLV7_SEED_A, tlv7.encode().toHex())
        // 加密帧头：7F | 外层 0 | 标志 1 | 值 10 字节 = TLV(1, 8 字节种子)
        assertEquals("7f80818a8188" + Vectors.SEED_A.toHex(), tlv7.encode().copyOfRange(0, 14).toHex())
        val reply = cipher.seal(0, hex(Vectors.PAYLOAD_REPLY), Vectors.SEED_B)
        assertEquals(Vectors.CAR_REPLY_SEED_B, reply.encode().toHex())
        val resp = cipher.seal(4, hex(Vectors.PAYLOAD_AUTH_RESP), Vectors.SEED_C)
        assertEquals(Vectors.CAR_AUTH_RESP_SEED_C, resp.encode().toHex())
    }

    @Test
    fun decodesReferenceStreamWithAnyChunking() {
        val frames = listOf(
            Vectors.CONNECT_ID_123456, Vectors.PHONE_FIRST, Vectors.CAR_TLV7_SEED_A, Vectors.CAR_REPLY_SEED_B,
            Vectors.PHONE_TLV8, Vectors.PHONE_AUTH_REQ_SEED_D, Vectors.CAR_AUTH_RESP_SEED_C,
        )
        val stream = hex(frames.joinToString(""))
        for (chunk in intArrayOf(1, 2, 3, 5, 7, 64, 163, 1000, stream.size)) {
            val decoder = NearbyFrameDecoder()
            val got = ArrayList<NearbyFrame>()
            var off = 0
            while (off < stream.size) {
                val n = minOf(chunk, stream.size - off)
                decoder.feed(stream, off, n)
                off += n
                while (true) got.add(decoder.next() ?: break)
            }
            assertEquals(frames.size, got.size, "每次喂 $chunk 字节")
            assertEquals(frames, got.map { it.encode().toHex() }, "每次喂 $chunk 字节")
            assertEquals(0, decoder.buffered)
        }
    }

    @Test
    fun decodedFrameFields() {
        val decoder = NearbyFrameDecoder()
        decoder.feed(hex(Vectors.PHONE_FIRST + Vectors.PHONE_AUTH_REQ_SEED_D))
        val first = assertNotNull(decoder.next())
        assertEquals(0, first.outerType)
        assertFalse(first.isEncrypted)
        assertNull(first.seed)
        assertEquals(155, first.payload.size)
        val req = assertNotNull(decoder.next())
        assertEquals(4, req.outerType)
        assertTrue(req.isEncrypted)
        assertContentEquals(Vectors.SEED_D, req.seed)
        assertEquals(Vectors.PAYLOAD_AUTH_REQ, cipher.open(req).toHex())
        assertNull(decoder.next())
    }

    @Test
    fun halfFrameWaitsForMoreData() {
        val bytes = hex(Vectors.PHONE_FIRST)
        val decoder = NearbyFrameDecoder()
        for (n in 1 until bytes.size) {
            decoder.clear()
            decoder.feed(bytes, 0, n)
            assertNull(decoder.next(), "只有 $n 字节")
            assertEquals(n, decoder.buffered)
        }
    }

    @Test
    fun badHeadByteDiscardsWholeBuffer() {
        val decoder = NearbyFrameDecoder()
        decoder.feed(hex("00" + Vectors.CONNECT_ID_123456))
        assertFailsWith<MalformedMessageException> { decoder.next() }
        assertEquals(0, decoder.buffered)
        // 之后的新数据重新开始拆。
        decoder.feed(hex(Vectors.CONNECT_ID_123456))
        assertEquals(Vectors.CONNECT_ID_123456, assertNotNull(decoder.next()).encode().toHex())
    }

    @Test
    fun crcMismatchDiscardsWholeBuffer() {
        val good = hex(Vectors.CONNECT_ID_123456)
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val decoder = NearbyFrameDecoder()
        decoder.feed(good + bad + good)
        assertNotNull(decoder.next())
        assertFailsWith<MalformedMessageException> { decoder.next() }
        // 坏帧之后粘着的好帧也一起丢掉（整个缓冲丢弃）。
        assertEquals(0, decoder.buffered)
        assertNull(decoder.next())
        // 改载荷而不改 CRC 同样被发现。
        val tampered = good.copyOf().also { it[8] = '9'.code.toByte() }
        decoder.feed(tampered)
        assertFailsWith<MalformedMessageException> { decoder.next() }
    }

    @Test
    fun unterminatedEaIsMalformed() {
        val decoder = NearbyFrameDecoder()
        decoder.feed(hex("7f00000000"))
        assertFailsWith<MalformedMessageException> { decoder.next() }
        // 载荷长度字段连续 4 字节都没有终止位：不再等更多数据，直接按非法处理；报错写明字段和帧内位置。
        decoder.feed(hex("7f80808000000000"))
        val bad = assertFailsWith<MalformedMessageException> { decoder.next() }
        assertEquals("拆帧：载荷长度 从帧内第 4 字节起连续 4 字节都没有终止位", bad.message)
        assertEquals(0, decoder.buffered)
    }

    @Test
    fun encryptedFrameNeedsEightByteSeed() {
        // 标志为奇数但没有种子。
        assertFailsWith<MalformedMessageException> { decodeOne(frameBytes(0, 1, EMPTY_BYTES, byteArrayOf(1))) }
        // 种子长度不是 8。
        val shortSeed = frameBytes(0, 1, encodeTlv(1, ByteArray(7)), byteArrayOf(1))
        assertFailsWith<MalformedMessageException> { decodeOne(shortSeed) }
        // 标志段的值不是 TLV。
        val notTlv = frameBytes(0, 1, byteArrayOf(0x81.toByte()), byteArrayOf(1))
        assertFailsWith<MalformedMessageException> { decodeOne(notTlv) }
        // 标志 3 也是奇数，同样按加密处理；种子合格就能拆出来。
        val odd = decodeOne(frameBytes(4, 3, encodeTlv(1, Vectors.SEED_A), byteArrayOf(5)))
        assertTrue(odd.isEncrypted)
        assertContentEquals(Vectors.SEED_A, odd.seed)
        // 偶数标志带值：仍是明文，值被忽略。
        val even = decodeOne(frameBytes(4, 2, byteArrayOf(1, 2), byteArrayOf(5)))
        assertFalse(even.isEncrypted)
        assertContentEquals(byteArrayOf(5), even.payload)
    }

    @Test
    fun oversizedFramesAreMalformed() {
        val decoder = NearbyFrameDecoder(maxPayload = 16)
        decoder.feed(frameBytes(0, 0, EMPTY_BYTES, ByteArray(16)))
        assertNotNull(decoder.next())
        // 只看到帧头就拒绝，不等载荷。
        decoder.feed(frameBytes(0, 0, EMPTY_BYTES, ByteArray(17)).copyOf(6))
        assertFailsWith<MalformedMessageException> { decoder.next() }
        val flagTooLong = ByteWriter().u8(0x7F).ea(0).ea(0).ea(NearbyFrameDecoder.MAX_FLAG_VALUE + 1).toByteArray()
        decoder.feed(flagTooLong)
        assertFailsWith<MalformedMessageException> { decoder.next() }
    }

    @Test
    fun largeOuterTypeAndPayloadRoundTrip() {
        val frame = NearbyFrame.plain(300, pattern(5000))
        val decoder = NearbyFrameDecoder()
        decoder.feed(frame.encode())
        val got = assertNotNull(decoder.next())
        assertEquals(300, got.outerType)
        assertContentEquals(pattern(5000), got.payload)
    }

    private fun decodeOne(bytes: ByteArray): NearbyFrame {
        val decoder = NearbyFrameDecoder()
        decoder.feed(bytes)
        return assertNotNull(decoder.next())
    }

    /** 按格式手工拼一帧（标志段可以随意构造），CRC 照算。 */
    private fun frameBytes(outerType: Int, flag: Int, flagValue: ByteArray, payload: ByteArray): ByteArray {
        val w = ByteWriter().u8(0x7F).ea(outerType).tlv(flag, flagValue).ea(payload.size).bytes(payload)
        val body = w.toByteArray()
        return w.u16Le(Crc16Arc.compute(body)).toByteArray()
    }
}
