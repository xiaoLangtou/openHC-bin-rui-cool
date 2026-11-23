package io.github.gcbond.openhc.core.dmsdp.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 控制连接上的声音：分片的识别、按组拼接、组头校验。数据都是合成的。 */
class ControlChannelAudioTest {

    /** 一组：14 字节组头（最后两字节是整组长度）+ [pcm]。 */
    private fun group(pcm: ByteArray): ByteArray {
        val total = ControlChannelAudio.GROUP_HEADER + pcm.size
        val head = ByteArray(ControlChannelAudio.GROUP_HEADER) { (it + 1).toByte() }
        head[12] = (total shr 8).toByte()
        head[13] = total.toByte()
        return head + pcm
    }

    /** 把一组切成 [parts] 片，每片带 18 字节片头。前缀用合成值。 */
    private fun fragments(seq: Long, whole: ByteArray, parts: Int): List<ByteArray> {
        val size = (whole.size + parts - 1) / parts
        return (0 until parts).map { i ->
            val data = whole.copyOfRange(i * size, minOf(whole.size, (i + 1) * size))
            val h = ByteArray(ControlChannelAudio.HEADER)
            for (k in 0 until 8) h[k] = (0x40 + k).toByte()
            h[8] = parts.toByte()
            h[9] = (i + 1).toByte()
            for (k in 0 until 4) h[10 + k] = (seq shr (24 - 8 * k)).toByte()
            h[14] = 0
            h[15] = i.toByte()
            h[16] = (data.size shr 8).toByte()
            h[17] = data.size.toByte()
            h + data
        }
    }

    private fun pcm(frames: Int, seed: Int) = ByteArray(frames * 4) { ((it * 7 + seed) and 0xFF).toByte() }

    @Test
    fun threeFragmentsMakeOneGroupOfPcm() {
        val audio = ControlChannelAudio()
        val out = ArrayList<ByteArray>()
        val expected = listOf(pcm(1024, 1), pcm(1024, 2))
        for ((seq, samples) in expected.withIndex()) {
            for (f in fragments(seq.toLong(), group(samples), 3)) {
                assertTrue(audio.feed(f) { d, o, l -> out += d.copyOfRange(o, o + l) })
            }
        }
        assertEquals(2, out.size)
        assertContentEquals(expected[0], out[0])
        assertContentEquals(expected[1], out[1])
        assertEquals(2, audio.groups)
        assertEquals(0, audio.dropped)
    }

    @Test
    fun missingOrShuffledFragmentsDropTheGroupButKeepGoing() {
        val audio = ControlChannelAudio()
        val out = ArrayList<ByteArray>()
        val sink: (ByteArray, Int, Int) -> Unit = { d, o, l -> out += d.copyOfRange(o, o + l) }
        val first = fragments(7, group(pcm(256, 3)), 3)
        val second = fragments(8, group(pcm(256, 4)), 3)
        // 第 7 组缺中间一片：认得出，但拼不成。
        assertTrue(audio.feed(first[0], sink))
        assertTrue(audio.feed(first[2], sink))
        // 第 8 组完整。
        for (f in second) audio.feed(f, sink)
        assertEquals(1, out.size)
        assertContentEquals(pcm(256, 4), out[0])
        assertEquals(1, audio.dropped)
        // 组头里的总长对不上：丢掉。
        val bad = group(pcm(64, 5)).also { it[13] = (it[13] + 1).toByte() }
        for (f in fragments(9, bad, 2)) assertTrue(audio.feed(f, sink))
        assertEquals(1, out.size)
        assertEquals(2, audio.dropped)
    }

    @Test
    fun otherPayloadsAreNotRecognized() {
        val audio = ControlChannelAudio()
        val sink: (ByteArray, Int, Int) -> Unit = { _, _, _ -> error("不该交出") }
        val ok = fragments(1, group(pcm(16, 6)), 1).single()
        assertFalse(audio.feed(ok.copyOf(ControlChannelAudio.HEADER - 1), sink), "太短")
        assertFalse(audio.feed(ok.copyOf().also { it[17] = (it[17] + 1).toByte() }, sink), "长度字段对不上")
        assertFalse(audio.feed(ok.copyOf().also { it[9] = 2 }, sink), "第几片超出片数")
        assertFalse(audio.feed(ok.copyOf().also { it[8] = 0 }, sink), "片数为 0")
        assertFalse(audio.feed(ok.copyOf().also { it[15] = 1 }, sink), "片序号和第几片不一致")
        assertFalse(audio.feed("{\"a\":1}".toByteArray(), sink))
        assertEquals(0, audio.fragments)
    }
}
