package io.github.gcbond.openhc.core.hisight.video

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.FixedRandom
import io.github.gcbond.openhc.core.hisight.Vectors
import io.github.gcbond.openhc.core.hisight.arr
import io.github.gcbond.openhc.core.hisight.crypto.AesCtr
import io.github.gcbond.openhc.core.hisight.hex
import io.github.gcbond.openhc.core.hisight.int
import io.github.gcbond.openhc.core.hisight.isNull
import io.github.gcbond.openhc.core.hisight.long
import io.github.gcbond.openhc.core.hisight.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VideoDepacketizerTest {
    private class Frame(val es: ByteArray, val ts: Long, val first: Int, val last: Int, val pts: Long, val nal: List<Int>)

    private fun collector(into: MutableList<Frame>) = { unit: AccessUnit ->
        into += Frame(unit.copyBytes(), unit.rtpTimestamp, unit.firstSequence, unit.lastSequence, unit.pts90k, unit.nalTypes().toList())
    }

    /**
     * 每个样例的每种切分都与参考实现逐项对照：交付的 ES、时间戳、序号、PTS、NAL 类型。
     * 出错时两边都必须失败；本实现是流式解析，可能比参考实现（整包到齐才检查）更早一个分片发现错误，但不会更晚。
     */
    @Test
    fun casesMatchPrototype() {
        val cases = Vectors.section("video") as JsonArray
        assertTrue(cases.size > 30)
        for (element in cases) {
            val case = element.jsonObject
            val stream = case.getValue("stream").hex()
            val expectedFrames = case.arr("frames").map { it.jsonObject }
            val finishError = (case["finishError"] as? JsonPrimitive)?.booleanOrNull
            for (splitElement in case.arr("splits")) {
                val split = splitElement.jsonObject
                val label = "${case.str("name")} 切分 ${split["split"]}"
                val chunks = chunksOf(stream, split)
                val frames = ArrayList<Frame>()
                val video = VideoDepacketizer(Vectors.key, collector(frames))
                var errorAt = -1
                for ((i, chunk) in chunks.withIndex()) {
                    try {
                        video.feed(chunk, 0, chunk.size)
                    } catch (e: MalformedMessageException) {
                        errorAt = i
                        break
                    }
                }
                val referenceErrorAt = split.int("errorAt")
                if (referenceErrorAt >= 0) {
                    assertTrue(errorAt in 0..referenceErrorAt, "$label：参考实现在第 $referenceErrorAt 片报错，本实现在 $errorAt")
                    assertFailsWith<MalformedMessageException> { video.feed(ByteArray(1), 0, 1) }
                } else if (errorAt >= 0) {
                    // 参考实现分片都收下了，只在结束检查时失败（例如包长不足 12 字节但数据没到齐）。
                    assertEquals(true, finishError, "$label：本实现在第 $errorAt 片报错，参考实现没有")
                } else {
                    val finished = try {
                        video.finish()
                        true
                    } catch (e: MalformedMessageException) {
                        false
                    }
                    assertEquals(finishError == false, finished, "$label：结束检查")
                }
                assertEquals(expectedFrames.size, frames.size, "$label：帧数")
                for ((expected, actual) in expectedFrames.zip(frames)) compareFrame(expected, actual, label)
                if (referenceErrorAt < 0 && errorAt < 0) {
                    assertEquals(case.long("packets"), video.packetsReceived, label)
                    // 容错语义（issue #3）：丢包类用例断言重对齐与丢弃包计数。
                    (case["resyncs"] as? JsonPrimitive)?.longOrNull?.let { assertEquals(it, video.resyncs, label) }
                    (case["dropped"] as? JsonPrimitive)?.longOrNull?.let { assertEquals(it, video.packetsDropped, label) }
                }
            }
        }
    }

    private fun chunksOf(stream: ByteArray, split: JsonObject): List<ByteArray> {
        val spec = split.getValue("split")
        if (spec is JsonPrimitive) return stream.map { byteArrayOf(it) }
        var pos = 0
        return (spec as JsonArray).map { size ->
            val n = size.jsonPrimitive.int
            stream.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    private fun compareFrame(expected: JsonObject, actual: Frame, label: String) {
        assertEquals(expected.int("esLength"), actual.es.size, label)
        if (!expected.isNull("es")) assertEquals(expected.str("es"), actual.es.toHex(), label)
        assertEquals(expected.str("esSha256"), MessageDigest.getInstance("SHA-256").digest(actual.es).toHex(), label)
        assertEquals(expected.long("ts"), actual.ts, label)
        assertEquals(expected.int("seqStart"), actual.first, label)
        assertEquals(expected.int("seqEnd"), actual.last, label)
        assertEquals(expected.long("pts"), actual.pts, label)
        assertEquals(expected.arr("nal").map { it.jsonPrimitive.int }, actual.nal, label)
    }

    /** 超过初始缓冲的大帧：缓冲按需扩大，回调里的数据与原文一致，且只在回调期间有效。 */
    @Test
    fun largeFrameGrowsBuffersAndReusesUnit() {
        val key = ByteArray(16) { it.toByte() }
        val es = ByteArray(700_000) { (it * 7 + 1).toByte() }
        es[0] = 0; es[1] = 0; es[2] = 0; es[3] = 1; es[4] = 0x65
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val sealed = AesCtr(key).seal(es, FixedRandom(listOf(iv)))
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x84.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) + sealed
        val stream = java.io.ByteArrayOutputStream()
        var seq = 64990
        var pos = 0
        while (pos < pes.size) {
            val n = minOf(60_000, pes.size - pos)
            val last = pos + n == pes.size
            val rtp = byteArrayOf(0x80.toByte(), (96 or (if (last) 0x80 else 0)).toByte(), (seq ushr 8).toByte(), seq.toByte(),
                0, 0, 0x0B, 0xB8.toByte(), 1, 2, 3, 4) + pes.copyOfRange(pos, pos + n)
            stream.write(byteArrayOf((rtp.size ushr 24).toByte(), (rtp.size ushr 16).toByte(), (rtp.size ushr 8).toByte(), rtp.size.toByte()))
            stream.write(rtp)
            seq = if (seq == 64999) 0 else seq + 1
            pos += n
        }
        val bytes = stream.toByteArray()
        var seen: AccessUnit? = null
        var delivered: ByteArray? = null
        val video = VideoDepacketizer(key) { unit ->
            seen = unit
            delivered = unit.copyBytes()
            assertEquals(listOf(5), unit.nalTypes().toList())
        }
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(65536, bytes.size - offset)
            video.feed(bytes, offset, n)
            offset += n
        }
        video.finish()
        assertContentEquals(es, delivered)
        assertEquals(1L, video.framesDelivered)
        assertEquals(3000L, seen!!.rtpTimestamp)
        assertEquals(0L, seen!!.pts90k)
    }

    @Test
    fun accessUnitHelpers() {
        val unit = AccessUnit()
        unit.data = byteArrayOf(9, 0, 0, 1, 0x67, 0, 0, 0, 0, 1, 0x68, 0, 0, 1)
        unit.offset = 1
        unit.length = 13
        assertEquals(listOf(7, 8), unit.nalTypes().toList())
        unit.pts90k = 90_000
        assertEquals(1_000_000L, unit.presentationTimeUs)
        assertContentEquals(unit.data.copyOfRange(1, 14), unit.copyBytes())
    }

    // ---------------------------------------------------------------- 丢包容错（issue #3 Bug 1）

    private val key = ByteArray(16) { it.toByte() }

    /** 构造一个单包帧（marker 置位）的 RTP 流：seq 从 [seq] 开始，ts 用 [ts]。 */
    private fun singlePacketFrame(seq: Int, ts: Int = 0x0BB8): ByteArray {
        val es = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3, 4)
        val sealed = AesCtr(key).seal(es, FixedRandom(listOf(ByteArray(16) { (0xA0 + it).toByte() })))
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x84.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1) + sealed
        val rtp = byteArrayOf(
            0x80.toByte(), (96 or 0x80).toByte(), (seq ushr 8).toByte(), seq.toByte(),
            (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(), 1, 2, 3, 4,
        ) + pes
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((rtp.size ushr 24).toByte(), (rtp.size ushr 16).toByte(), (rtp.size ushr 8).toByte(), rtp.size.toByte()))
        out.write(rtp)
        return out.toByteArray()
    }

    private fun feedAll(video: VideoDepacketizer, vararg chunks: ByteArray) {
        for (chunk in chunks) video.feed(chunk, 0, chunk.size)
    }

    @Test
    fun sequenceGapDropsOneFrameAndResyncs() {
        val frames = ArrayList<Frame>()
        val video = VideoDepacketizer(key, collector(frames))
        feedAll(video, singlePacketFrame(100), singlePacketFrame(105), singlePacketFrame(106))
        video.finish()
        // 中间跳号的那帧被丢弃，会话不终结，后续帧正常交付。
        assertEquals(2L, video.framesDelivered)
        assertEquals(1L, video.resyncs)
        assertEquals(1L, video.packetsDropped)
        assertEquals(listOf(100, 100), listOf(frames[0].first, frames[0].last))
        assertEquals(listOf(106, 106), listOf(frames[1].first, frames[1].last))
    }

    @Test
    fun duplicatePacketDropsOneFrameAndResyncs() {
        val frames = ArrayList<Frame>()
        val video = VideoDepacketizer(key, collector(frames))
        feedAll(video, singlePacketFrame(100), singlePacketFrame(100), singlePacketFrame(101))
        video.finish()
        assertEquals(2L, video.framesDelivered)
        assertEquals(1L, video.resyncs)
    }

    @Test
    fun standardWrapAt65535IsContiguous() {
        val frames = ArrayList<Frame>()
        val video = VideoDepacketizer(key, collector(frames))
        feedAll(video, singlePacketFrame(65535), singlePacketFrame(0))
        video.finish()
        assertEquals(2L, video.framesDelivered)
        assertEquals(0L, video.resyncs)
    }

    @Test
    fun huaweiWrapAt64999IsStillContiguous() {
        val frames = ArrayList<Frame>()
        val video = VideoDepacketizer(key, collector(frames))
        feedAll(video, singlePacketFrame(64999), singlePacketFrame(0))
        video.finish()
        assertEquals(2L, video.framesDelivered)
        assertEquals(0L, video.resyncs)
    }
}
