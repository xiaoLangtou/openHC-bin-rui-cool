package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.dmsdp.ControlAudioFrames.fragments
import io.github.gcbond.openhc.core.dmsdp.ControlAudioFrames.group
import io.github.gcbond.openhc.core.dmsdp.ControlAudioFrames.pcm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 控制连接上的声音：分片的识别、按组拼接、组头校验。数据都是合成的。 */
class ControlChannelAudioTest {

    /** 拼出的一组：流号、服务类型、用途、路号和 PCM。 */
    private data class Out(val stream: Long, val service: Int, val usage: Int, val bus: Int, val pcm: ByteArray)

    private fun sink(out: MutableList<Out>): (ControlChannelAudio.Group) -> Unit = { g ->
        out += Out(g.stream, g.service, g.usage, g.bus, g.data.copyOfRange(g.offset, g.offset + g.length))
    }

    private fun collect(audio: ControlChannelAudio, input: List<ByteArray>): List<Out> {
        val out = ArrayList<Out>()
        for (f in input) assertTrue(audio.feed(f, sink(out)))
        return out
    }

    @Test
    fun threeFragmentsWithShortGroupHeaderMakeOneGroupOfPcm() {
        val audio = ControlChannelAudio()
        val expected = listOf(pcm(1024, 1), pcm(1024, 2))
        val out = collect(audio, expected.withIndex().flatMap { (seq, samples) -> fragments(seq.toLong(), group(samples), 3) })
        assertEquals(2, out.size)
        assertContentEquals(expected[0], out[0].pcm)
        assertContentEquals(expected[1], out[1].pcm)
        // 14 字节组头不带用途和路号。
        assertEquals(0, out[0].usage)
        assertEquals(AudioBus.UNKNOWN, out[0].bus)
        assertEquals(2, audio.groups)
        assertEquals(0, audio.dropped)
    }

    /** 一组 6 片（中间 4 片的位置标记都是 2），组头 18 字节，带用途和路号。 */
    @Test
    fun sixFragmentsWithLongGroupHeaderCarryUsageAndBus() {
        val audio = ControlChannelAudio()
        val samples = pcm(2048, 3)
        val out = collect(
            audio,
            fragments(40, group(samples, header = 18, usage = 1, bus = 8), 6) +
                fragments(41, group(pcm(2048, 4), header = 18, usage = 12, bus = 0), 6),
        )
        assertEquals(2, out.size)
        assertContentEquals(samples, out[0].pcm)
        assertEquals(1 to 8, out[0].usage to out[0].bus)
        assertContentEquals(pcm(2048, 4), out[1].pcm)
        assertEquals(12 to 0, out[1].usage to out[1].bus)
        assertEquals(0, audio.dropped)
    }

    /** 两路声音（流号不同）的片交替到达：各拼各的，都不丢。 */
    @Test
    fun interleavedStreamsAreAssembledSeparately() {
        val audio = ControlChannelAudio()
        val music = fragments(5, group(pcm(2048, 7), 18), 6, stream = 0x11111111)
        val guide = fragments(9, group(pcm(1024, 8)), 3, stream = 0x22222222)
        val order = listOf(music[0], guide[0], music[1], music[2], guide[1], music[3], guide[2], music[4], music[5])
        val out = collect(audio, order)
        assertEquals(listOf(0x22222222L, 0x11111111L), out.map { it.stream })
        assertContentEquals(pcm(1024, 8), out[0].pcm)
        assertContentEquals(pcm(2048, 7), out[1].pcm)
        assertEquals(0, audio.dropped)
    }

    /** 服务类型：扬声器 3、通话扬声器 10、13 都认；组头第 0 字节和片头的服务类型对不上的组丢掉。 */
    @Test
    fun serviceTypesAreCheckedAgainstTheGroupHeader() {
        val audio = ControlChannelAudio()
        val out = collect(audio, fragments(1, group(pcm(64, 1), service = 10), 2, service = 10))
        assertEquals(10, out.single().service)
        assertTrue(audio.feed(fragments(2, group(pcm(64, 2), service = 13), 1, service = 13).single(), sink(ArrayList())))
        val mismatched = fragments(3, group(pcm(64, 3), service = 3), 2, service = 10)
        val more = ArrayList<Out>()
        for (f in mismatched) assertTrue(audio.feed(f, sink(more)))
        assertTrue(more.isEmpty())
        assertEquals(1, audio.dropped)
    }

    @Test
    fun missingOrShuffledFragmentsDropTheGroupButKeepGoing() {
        val audio = ControlChannelAudio()
        val out = ArrayList<Out>()
        val first = fragments(7, group(pcm(256, 3)), 3)
        val second = fragments(8, group(pcm(256, 4)), 3)
        // 第 7 组缺中间一片：认得出，但拼不成。
        assertTrue(audio.feed(first[0], sink(out)))
        assertTrue(audio.feed(first[2], sink(out)))
        // 第 8 组完整。
        for (f in second) audio.feed(f, sink(out))
        assertEquals(1, out.size)
        assertContentEquals(pcm(256, 4), out[0].pcm)
        assertEquals(1, audio.dropped)
        // 组头里的总长对不上：丢掉。
        val bad = group(pcm(64, 5)).also { it[13] = (it[13] + 1).toByte() }
        for (f in fragments(9, bad, 2)) assertTrue(audio.feed(f, sink(out)))
        assertEquals(1, out.size)
        assertEquals(2, audio.dropped)
        // 末片的组号和前面不一样：丢掉。
        val mixed = fragments(10, group(pcm(64, 6)), 2).toMutableList()
        mixed[1] = fragments(11, group(pcm(64, 6)), 2)[1]
        for (f in mixed) assertTrue(audio.feed(f, sink(out)))
        assertEquals(1, out.size)
        assertEquals(3, audio.dropped)
    }

    @Test
    fun otherPayloadsAreNotRecognized() {
        val audio = ControlChannelAudio()
        val never: (ControlChannelAudio.Group) -> Unit = { error("不该交出") }
        val ok = fragments(1, group(pcm(16, 6)), 1).single()
        assertFalse(audio.feed(ok.copyOf(ControlChannelAudio.HEADER - 1), never), "太短")
        assertFalse(audio.feed(ok.copyOf().also { it[17] = (it[17] + 1).toByte() }, never), "长度字段对不上")
        assertFalse(audio.feed(ok.copyOf().also { it[6] = 0 }, never), "固定值不对")
        assertFalse(audio.feed(ok.copyOf().also { it[8] = 4 }, never), "服务类型不认")
        assertFalse(audio.feed(ok.copyOf().also { it[9] = 0 }, never), "位置标记为 0")
        assertFalse(audio.feed(ok.copyOf().also { it[9] = 4 }, never), "位置标记超出")
        assertFalse(audio.feed("{\"a\":1}".toByteArray(), never))
        assertEquals(0, audio.fragments)
    }
}
