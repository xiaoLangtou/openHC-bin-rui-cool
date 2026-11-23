package io.github.gcbond.openhc.core.dmsdp.audio

import io.github.gcbond.openhc.core.dmsdp.AesCtr
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 音频拆包：分帧识别（各候选、逐字节喂入、随机数据不误认）、两层解密、失步和统计。数据全部现场合成。 */
class AudioDepacketizerTest {

    private fun depacketizer(recorder: Recorder, stream: AudioStreamInfo = AudioSynthetic.stream()) =
        AudioDepacketizer(AesCtr(AudioSynthetic.KEY), stream, recorder)

    /** 按不同大小切块喂入（覆盖半包、粘包）。 */
    private fun AudioDepacketizer.feedInPieces(bytes: ByteArray, sizes: IntArray = intArrayOf(1, 7, 100, 333, 4096, 13)) {
        var pos = 0
        var i = 0
        while (pos < bytes.size) {
            val n = minOf(sizes[i++ % sizes.size], bytes.size - pos)
            feed(bytes, pos, n)
            pos += n
        }
    }

    @Test
    fun everyCandidateIsRecognizedAndDecrypted() {
        for (kind in FrameKind.entries) {
            for (sealed in listOf(true, false)) {
                val (bytes, payloads) = AudioSynthetic.pcmStream(kind, sealed, count = 6)
                val recorder = Recorder()
                val depacketizer = depacketizer(recorder)
                depacketizer.feedInPieces(bytes)
                depacketizer.finish()
                val framing = assertNotNull(recorder.framing, "$kind/$sealed 没认出来：${recorder.unknownReason}")
                assertEquals(kind.label, framing.frame, "$kind/$sealed")
                assertEquals(sealed, framing.frameEncrypted, "$kind/$sealed")
                assertFalse(framing.payloadEncrypted, "$kind/$sealed")
                assertTrue(framing.playable, "$kind/$sealed")
                assertEquals(0, framing.leadingFrames)
                assertEquals(AudioSynthetic.PT, framing.payloadType)
                assertEquals(AudioSynthetic.SSRC, framing.ssrc)
                assertEquals("CC=0，X=0，P=0", framing.rtpHeader)
                assertEquals(payloads.size, recorder.payloads.size, "$kind/$sealed")
                payloads.forEachIndexed { i, expected -> assertContentEquals(expected, recorder.payloads[i], "$kind/$sealed 第 $i 包") }
                assertEquals((100 until 106).toList(), recorder.sequences)
                assertEquals(0, depacketizer.sequenceGaps)
                assertEquals(0, depacketizer.badFrames)
                assertEquals(bytes.size.toLong(), depacketizer.bytes)
            }
        }
    }

    @Test
    fun byteByByteFeedingGivesTheSameResult() {
        val (bytes, payloads) = AudioSynthetic.pcmStream(FrameKind.LENGTH32, sealed = true, count = 5)
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        for (i in bytes.indices) depacketizer.feed(bytes, i, 1)
        assertEquals(FrameKind.LENGTH32.label, recorder.framing?.frame)
        assertEquals(payloads.size, recorder.payloads.size)
        payloads.forEachIndexed { i, expected -> assertContentEquals(expected, recorder.payloads[i]) }
        // 识别结论里带着最早的 32 个原始字节。
        assertContentEquals(bytes.copyOf(32), recorder.framing!!.head)
    }

    @Test
    fun encryptedPayloadInsidePlainRtpIsDetected() {
        val payloads = (0 until 4).map { AudioSynthetic.pcm(it * 1024) }
        var bytes = ByteArray(0)
        payloads.forEachIndexed { i, pcm ->
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(10 + i, AudioSynthetic.seal(pcm, 50 + i)))
        }
        val recorder = Recorder()
        depacketizer(recorder).feedInPieces(bytes)
        val framing = assertNotNull(recorder.framing)
        assertFalse(framing.frameEncrypted)
        assertTrue(framing.payloadEncrypted)
        assertTrue(framing.playable)
        assertEquals(listOf(4096, 4096, 4096), framing.payloadLengths)
        payloads.forEachIndexed { i, expected -> assertContentEquals(expected, recorder.payloads[i]) }
    }

    @Test
    fun noiseLikePcmPayloadIsMarkedUnplayable() {
        val random = Random(7)
        var bytes = ByteArray(0)
        repeat(4) { i ->
            val noise = ByteArray(4096).also { random.nextBytes(it) }
            bytes += AudioSynthetic.frame(FrameKind.LENGTH16, AudioSynthetic.rtp(i, noise))
        }
        val recorder = Recorder()
        depacketizer(recorder).feedInPieces(bytes)
        val framing = assertNotNull(recorder.framing)
        assertFalse(framing.playable)
        assertEquals(4, recorder.payloads.size, "照样交出，由上层决定不放")
        assertTrue(recorder.playable.none { it })
    }

    @Test
    fun randomBytesAreNotRecognized() {
        val random = Random(42)
        val bytes = ByteArray(300 * 1024).also { random.nextBytes(it) }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes, intArrayOf(4096))
        depacketizer.finish()
        assertNull(recorder.framing)
        assertNotNull(recorder.unknownReason)
        assertContentEquals(bytes.copyOf(32), recorder.unknownHead)
        assertTrue(recorder.payloads.isEmpty())
        assertEquals(bytes.size.toLong(), depacketizer.bytes, "识别失败后的字节也计数")
    }

    /** 帧长首尾相接、版本位也是 2，但序号和 SSRC 是随机的：不能被认成 RTP。 */
    @Test
    fun framesThatOnlyLookLikeVersionTwoAreNotLocked() {
        val random = Random(3)
        var bytes = ByteArray(0)
        repeat(12) {
            val body = ByteArray(200).also { random.nextBytes(it) }
            body[0] = 0x80.toByte()
            body[1] = 96
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, body)
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        depacketizer.finish()
        assertNull(recorder.framing)
        assertNotNull(recorder.unknownReason)
        assertTrue(recorder.payloads.isEmpty())
    }

    @Test
    fun leadingNonRtpFrameAndInterleavedRtcpAreSkipped() {
        val hello = "hello, not rtp!!".toByteArray()
        val payloads = (0 until 4).map { AudioSynthetic.pcm(it * 1024) }
        var bytes = AudioSynthetic.frame(FrameKind.LENGTH32, hello)
        bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(1, payloads[0]))
        bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtcpSenderReport())
        for (i in 1 until 4) bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(1 + i, payloads[i]))
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        assertEquals(1, recorder.framing?.leadingFrames)
        assertEquals(4, recorder.payloads.size)
        assertEquals(1, depacketizer.rtcpPackets)
        assertEquals(0, depacketizer.badFrames, "开头那一帧算前导，不算坏帧")
    }

    @Test
    fun sequenceGapsAreCountedAndWrapsAreNot() {
        val sequences = listOf(64998, 64999, 0, 1, 65535, 0, 3)
        var bytes = ByteArray(0)
        sequences.forEachIndexed { i, sequence ->
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(sequence, AudioSynthetic.pcm(i * 1024)))
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        assertEquals(sequences, recorder.sequences)
        // 1 → 65535 和 0 → 3 是跳变；64999 → 0、65535 → 0 是回绕。
        assertEquals(2, depacketizer.sequenceGaps)
    }

    /** 两路交错（分流时的音乐和导航）：识别照样认得出，各路各算序号，路号跟着 SSRC。 */
    @Test
    fun interleavedStreamsAreRecognizedAndTrackedPerSsrc() {
        val order = listOf(1008L to 500, 1000L to 9, 1008L to 501, 1000L to 10, 1008L to 502, 1008L to 503, 1000L to 12)
        var bytes = ByteArray(0)
        order.forEachIndexed { i, (ssrc, sequence) ->
            val packet = AudioSynthetic.rtp(sequence, AudioSynthetic.pcm(i * 1024), ssrc = ssrc)
            bytes += AudioSynthetic.frame(FrameKind.CONTROL, AudioSynthetic.seal(packet, i + 1), sealed = true)
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        val framing = assertNotNull(recorder.framing, recorder.unknownReason)
        assertEquals(FrameKind.CONTROL.label, framing.frame)
        assertEquals(1008L, framing.ssrc, "凑够 3 包的是第 8 路")
        assertEquals(order.map { it.second }, recorder.sequences, "按到达顺序全部交出")
        assertEquals(listOf(8, 0, 8, 0, 8, 8, 0), recorder.buses)
        assertTrue(recorder.usages.all { it == 0 }, "这几路的 SSRC 没带用途")
        assertEquals(1, depacketizer.sequenceGaps, "只有第 0 路 10 → 12 算跳变，交错本身不算")
        assertTrue("第 8 路 4 包" in depacketizer.streams && "第 0 路 3 包" in depacketizer.streams, depacketizer.streams)
        assertTrue(recorder.notes.any { "新的一路" in it })
    }

    /** SSRC 高 16 位带着用途时：路号取低 16 位，用途原样交出。 */
    @Test
    fun usageBitsInTheSsrcAreSplitFromTheBus() {
        val call = 0x0002_03EAL
        var bytes = ByteArray(0)
        repeat(4) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.pcm(i * 1024), ssrc = call)) }
        val recorder = Recorder()
        depacketizer(recorder).feedInPieces(bytes)
        assertEquals(call, recorder.framing?.ssrc)
        assertEquals(listOf(2, 2, 2, 2), recorder.buses)
        assertEquals(listOf(2, 2, 2, 2), recorder.usages)
    }

    /**
     * 识别期间最多容许 [AudioDepacketizer.MAX_LANES] 路：每帧都像「新的一路」的数据认不出来；
     * 4 路之后再来一路就判这种分帧不对，哪怕后面有一路凑够了连续 3 包。
     */
    @Test
    fun tooManyLanesDuringDetectionAreRejected() {
        fun stream(lanes: Int): ByteArray {
            var bytes = ByteArray(0)
            repeat(lanes) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.pcm(0), ssrc = 0x5000L + i)) }
            for (k in 1..3) bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(k, AudioSynthetic.pcm(0), ssrc = 0x5000L))
            return bytes
        }
        fun detect(bytes: ByteArray): Recorder {
            val recorder = Recorder()
            val depacketizer = depacketizer(recorder)
            depacketizer.feedInPieces(bytes)
            depacketizer.finish()
            return recorder
        }
        assertNotNull(detect(stream(AudioDepacketizer.MAX_LANES)).framing, "4 路以内照常认")
        val tooMany = detect(stream(AudioDepacketizer.MAX_LANES + 1))
        assertNull(tooMany.framing, "第 5 路一出现这种分帧就判不对")
        assertTrue(tooMany.payloads.isEmpty())

        var random = ByteArray(0)
        repeat(8) { i -> random += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.pcm(0), ssrc = 0x6000L + i)) }
        assertNull(detect(random).framing, "每帧一路：认不出来")
    }

    @Test
    fun pcmByteOrderIsDecided() {
        for (bigEndian in listOf(false, true)) {
            val (bytes, payloads) = AudioSynthetic.pcmStream(FrameKind.LENGTH16, sealed = true, count = 6, bigEndian = bigEndian)
            val recorder = Recorder()
            val depacketizer = depacketizer(recorder)
            depacketizer.feedInPieces(bytes)
            val expected = if (bigEndian) PcmByteOrder.BIG_ENDIAN else PcmByteOrder.LITTLE_ENDIAN
            assertEquals(expected, depacketizer.pcmByteOrder)
            assertEquals(expected, recorder.orders.last())
            assertEquals(PcmByteOrder.UNDECIDED, recorder.orders.first(), "攒够数据之前不下结论")
            assertTrue(recorder.notes.any { "字节序" in it })
            payloads.forEachIndexed { i, payload -> assertContentEquals(payload, recorder.payloads[i], "载荷原样交出，转换由播放端做") }
        }
    }

    @Test
    fun desyncAfterLockSwitchesToRawWithoutThrowing() {
        val (bytes, _) = AudioSynthetic.pcmStream(FrameKind.LENGTH32, sealed = false, count = 4)
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feed(bytes, 0, bytes.size)
        assertEquals(4, recorder.payloads.size)
        val garbage = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 1, 2, 3)
        depacketizer.feed(garbage, 0, garbage.size)
        val more = AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(200, AudioSynthetic.pcm(0)))
        depacketizer.feed(more, 0, more.size)
        depacketizer.finish()
        assertEquals(4, recorder.payloads.size, "失步之后不再拆包")
        assertTrue(recorder.notes.any { "失步" in it })
        assertTrue("失步" in depacketizer.conclusion)
    }

    @Test
    fun connectionEndingAfterTwoGoodFramesStillLocks() {
        val (bytes, payloads) = AudioSynthetic.pcmStream(FrameKind.CONTROL, sealed = true, count = 2)
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feed(bytes, 0, bytes.size)
        assertNull(recorder.framing, "两包还不够")
        depacketizer.finish()
        assertEquals(FrameKind.CONTROL.label, recorder.framing?.frame)
        assertEquals(payloads.size, recorder.payloads.size)
    }

    @Test
    fun aacPayloadWithAdtsHeaderIsCheckedForTheExtraLayer() {
        val random = Random(9)
        fun adtsUnit(): ByteArray {
            val body = ByteArray(300).also { random.nextBytes(it) }
            val length = body.size + 7
            val header = byteArrayOf(
                0xFF.toByte(), 0xF1.toByte(), 0x4C, (0x80 or (length shr 11)).toByte(),
                (length shr 3).toByte(), ((length and 7) shl 5 or 0x1F).toByte(), 0xFC.toByte(),
            )
            return header + body
        }
        val stream = AudioSynthetic.stream(codec = AudioCodec.AAC)
        for (payloadSealed in listOf(false, true)) {
            val units = (0 until 4).map { adtsUnit() }
            var bytes = ByteArray(0)
            units.forEachIndexed { i, unit ->
                val payload = if (payloadSealed) AudioSynthetic.seal(unit, 90 + i) else unit
                bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload))
            }
            val recorder = Recorder()
            depacketizer(recorder, stream).feedInPieces(bytes)
            val framing = assertNotNull(recorder.framing)
            assertEquals(payloadSealed, framing.payloadEncrypted)
            assertTrue(framing.playable)
            units.forEachIndexed { i, unit -> assertContentEquals(unit, recorder.payloads[i]) }
        }
    }

    /** 手机在 ADTS 前也垫了 PCM 同款的 12 字节头（头里声明 ADTS 整帧长，包尾多 4 字节）：剥掉头，只交 ADTS 帧。 */
    @Test
    fun aacPayloadWithTwelveByteHeaderIsStripped() {
        val random = Random(21)
        val units = (0 until 4).map { AudioSynthetic.adts(ByteArray(300).also { random.nextBytes(it) }) }
        var bytes = ByteArray(0)
        units.forEachIndexed { i, unit ->
            val payload = AudioSynthetic.pcmHeader(i.toLong(), unit.size) + unit + ByteArray(4)
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload))
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder, AudioSynthetic.stream(codec = AudioCodec.AAC))
        depacketizer.feedInPieces(bytes)
        depacketizer.finish()
        val framing = assertNotNull(recorder.framing)
        assertTrue(framing.playable)
        assertEquals(AudioCodec.AAC, framing.payloadCodec)
        assertEquals(units.size.toLong(), depacketizer.aacHeadersStripped)
        assertEquals(0, depacketizer.aacWithoutHeader)
        units.forEachIndexed { i, unit -> assertContentEquals(unit, recorder.payloads[i]) }
        assertTrue(recorder.notes.any { "AAC 载荷前有 12 字节头" in it }, recorder.notes.toString())
    }

    /** 头里声明的长度对不上头后的 ADTS 帧长：不算带头的包，整个载荷原样交出（宁可交垃圾也不能错剥）。 */
    @Test
    fun aacPayloadWithMismatchingHeaderLengthPassesThrough() {
        val random = Random(22)
        val unit = AudioSynthetic.adts(ByteArray(300).also { random.nextBytes(it) })
        val payload = AudioSynthetic.pcmHeader(0, unit.size - 1) + unit + ByteArray(6)
        var bytes = ByteArray(0)
        repeat(4) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload)) }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder, AudioSynthetic.stream(codec = AudioCodec.AAC))
        depacketizer.feedInPieces(bytes)
        depacketizer.finish()
        assertEquals(0, depacketizer.aacHeadersStripped)
        assertEquals(4, depacketizer.aacWithoutHeader)
        assertEquals(4, recorder.payloads.size)
        repeat(4) { i -> assertContentEquals(payload, recorder.payloads[i]) }
    }

    /** 按载荷判断实际编码，不看车机要的：要 PCM 来了 ADTS 走 AAC，要 AAC 来了 PCM 走 PCM。 */
    @Test
    fun actualCodecFollowsThePayloadNotTheRequest() {
        val random = Random(11)
        val units = (0 until 4).map { AudioSynthetic.adts(ByteArray(300).also { b -> random.nextBytes(b) }) }
        var adtsBytes = ByteArray(0)
        units.forEachIndexed { i, unit -> adtsBytes += AudioSynthetic.frame(FrameKind.CONTROL, AudioSynthetic.seal(AudioSynthetic.rtp(i, unit), 20 + i), sealed = true) }
        val askedPcm = Recorder()
        depacketizer(askedPcm, AudioSynthetic.stream(codec = AudioCodec.PCM)).feedInPieces(adtsBytes)
        assertEquals(AudioCodec.AAC, askedPcm.framing?.payloadCodec)
        assertTrue(askedPcm.codecs.all { it == AudioCodec.AAC } && askedPcm.codecs.size == 4)
        assertTrue(askedPcm.playable.all { it })
        assertTrue(askedPcm.notes.any { "手机实际发的是 AAC" in it && "车机要的是 PCM" in it }, askedPcm.notes.toString())

        val (pcmBytes, payloads) = AudioSynthetic.pcmStream(FrameKind.CONTROL, sealed = true, count = 6)
        val askedAac = Recorder()
        val depacketizer = depacketizer(askedAac, AudioSynthetic.stream(codec = AudioCodec.AAC))
        depacketizer.feedInPieces(pcmBytes)
        assertEquals(AudioCodec.PCM, askedAac.framing?.payloadCodec)
        assertTrue(askedAac.codecs.all { it == AudioCodec.PCM })
        assertEquals(PcmByteOrder.LITTLE_ENDIAN, depacketizer.pcmByteOrder, "实际是 PCM 就照样判断字节序")
        payloads.forEachIndexed { i, expected -> assertContentEquals(expected, askedAac.payloads[i]) }
        assertTrue(askedAac.notes.any { "手机实际发的是 PCM" in it && "车机要的是 AAC" in it })
        assertTrue("载荷 PCM" in depacketizer.conclusion)
    }

    /** 两头都不像（既不是 ADTS 也不像 PCM）时按车机要的走：要 AAC 照旧送解码器，要 PCM 才静音。 */
    @Test
    fun undecidablePayloadFollowsTheRequest() {
        for (asked in AudioCodec.entries) {
            val random = Random(5)
            var bytes = ByteArray(0)
            repeat(4) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH16, AudioSynthetic.rtp(i, ByteArray(400).also { random.nextBytes(it) })) }
            val recorder = Recorder()
            depacketizer(recorder, AudioSynthetic.stream(codec = asked)).feedInPieces(bytes)
            assertEquals(asked, recorder.framing?.payloadCodec)
            assertEquals(asked == AudioCodec.AAC, recorder.framing?.playable, "$asked")
            assertTrue(recorder.notes.any { "既不像 ADTS 也不像 PCM" in it })
        }
    }

    /** 载荷都太短、判断不了是不是 PCM：按车机要的照常放（要 AAC 就送解码器，不会被当成 PCM）。 */
    @Test
    fun tooShortPayloadFollowsTheRequestAndStaysPlayable() {
        for (asked in AudioCodec.entries) {
            val random = Random(8)
            var bytes = ByteArray(0)
            repeat(4) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, ByteArray(60).also { random.nextBytes(it) })) }
            val recorder = Recorder()
            depacketizer(recorder, AudioSynthetic.stream(codec = asked)).feedInPieces(bytes)
            assertEquals(asked, recorder.framing?.payloadCodec, "$asked")
            assertEquals(true, recorder.framing?.playable, "$asked")
            assertTrue(recorder.notes.any { "载荷太短" in it })
        }
    }

    @Test
    fun adtsFrameFollowedByExtraBytesIsStillAac() {
        var bytes = ByteArray(0)
        repeat(4) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.adts(ByteArray(200), trailing = 3))) }
        val recorder = Recorder()
        depacketizer(recorder, AudioSynthetic.stream(codec = AudioCodec.PCM)).feedInPieces(bytes)
        assertEquals(AudioCodec.AAC, recorder.framing?.payloadCodec, "帧长小于载荷长也算 ADTS")
    }

    /**
     * ADTS 帧前带 12 字节头（和 PCM 的头一样，分流时包尾多几个字节）：认成 AAC，只交出头后的 ADTS 帧。
     * 帧长有奇有偶；要的是 PCM 也一样走 AAC；载荷再加一层密也认得出。
     */
    @Test
    fun adtsBehindThePayloadHeaderIsAacWithTheHeaderStripped() {
        val random = Random(13)
        for (asked in AudioCodec.entries) for (payloadSealed in listOf(false, true)) {
            val units = listOf(300, 317, 288, 301, 299).map { AudioSynthetic.adts(ByteArray(it).also { b -> random.nextBytes(b) }) }
            var bytes = ByteArray(0)
            units.forEachIndexed { i, unit ->
                val headed = AudioSynthetic.pcmHeader(timestamp = i * 1_024L, dataLength = unit.size) + unit + byteArrayOf(1, 2, 3, 4)
                val payload = if (payloadSealed) AudioSynthetic.seal(headed, 30 + i) else headed
                bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload))
            }
            val recorder = Recorder()
            val depacketizer = depacketizer(recorder, AudioSynthetic.stream(codec = asked))
            depacketizer.feedInPieces(bytes)
            val label = "要 $asked，载荷加密 $payloadSealed"
            val framing = assertNotNull(recorder.framing, label)
            assertEquals(AudioCodec.AAC, framing.payloadCodec, label)
            assertEquals(payloadSealed, framing.payloadEncrypted, label)
            assertTrue(framing.playable, label)
            assertEquals(units.size, recorder.payloads.size, label)
            units.forEachIndexed { i, unit -> assertContentEquals(unit, recorder.payloads[i], "$label：第 $i 包只剩 ADTS 帧") }
            assertEquals(units.size.toLong(), depacketizer.aacHeadersStripped, label)
            assertEquals(0L, depacketizer.pcmHeadersStripped, label)
            assertEquals(1, recorder.notes.count { "AAC 载荷前有 12 字节头" in it }, label)
            assertTrue("AAC 载荷去掉 12 字节头" in depacketizer.conclusion, label)
        }
    }

    @Test
    fun payloadHeaderRules() {
        val unit = AudioSynthetic.adts(ByteArray(250)) // 帧长 257，奇数
        val headed = AudioSynthetic.pcmHeader(timestamp = 7, dataLength = unit.size) + unit
        assertEquals(unit.size, AudioPacketHeader.dataLength(headed, 0, headed.size, adts = true))
        assertEquals(-1, AudioPacketHeader.dataLength(headed, 0, headed.size, adts = false), "PCM 的长度必须是偶数")
        val tail = headed + ByteArray(AudioPacketHeader.MAX_TRAILER)
        assertEquals(unit.size, AudioPacketHeader.dataLength(tail, 0, tail.size, adts = true), "包尾多出上限以内的字节")
        val tooLong = tail + byteArrayOf(0)
        assertEquals(-1, AudioPacketHeader.dataLength(tooLong, 0, tooLong.size, adts = true), "包尾多出超过上限")
        val mismatch = AudioSynthetic.pcmHeader(timestamp = 7, dataLength = unit.size + 1) + unit + byteArrayOf(0)
        assertEquals(-1, AudioPacketHeader.dataLength(mismatch, 0, mismatch.size, adts = true), "ADTS 帧长和头里的长度不一样")
        assertEquals(-1, AudioPacketHeader.dataLength(unit, 0, unit.size, adts = true), "不带头的 ADTS")
        val pcm = AudioSynthetic.pcm(0, frames = 64)
        val headedPcm = AudioSynthetic.pcmHeader(timestamp = 7, dataLength = pcm.size) + pcm
        assertEquals(pcm.size, AudioPacketHeader.dataLength(headedPcm, 0, headedPcm.size, adts = false))
        assertEquals(-1, AudioPacketHeader.dataLength(headedPcm, 0, headedPcm.size, adts = true), "头后不是 ADTS")
    }

    @Test
    fun adtsHeaderCheck() {
        val unit = AudioSynthetic.adts(ByteArray(3))
        assertTrue(Adts.looksLike(unit, 0, unit.size))
        assertTrue(Adts.looksLike(unit + byteArrayOf(1, 2), 0, unit.size + 2), "尾部多几个字节")
        assertFalse(Adts.looksLike(unit, 0, unit.size - 1), "声明的帧长超出数据")
        assertFalse(Adts.looksLike(unit.copyOf().also { it[1] = 0xF3.toByte() }, 0, unit.size), "layer 不是 00")
        assertFalse(Adts.looksLike(unit.copyOf().also { it[2] = (13 shl 2).toByte() }, 0, unit.size), "采样率索引 13 不在表里")
        val withCrc = unit.copyOf().also { it[1] = 0xF0.toByte() }
        assertTrue(Adts.looksLike(withCrc, 0, withCrc.size), "带 CRC 的 9 字节头，帧长 10 仍够")
        val short = AudioSynthetic.adts(ByteArray(0)).also { it[1] = 0xF0.toByte() }
        assertFalse(Adts.looksLike(short + ByteArray(5), 0, short.size + 5), "帧长 7 小于带 CRC 的头长 9")
    }

    /** 单看一包，以 FF F0 开头的 PCM 也可能凑出合法的 ADTS 头；按识别用的几包多数决定，一包不会改变结论。 */
    @Test
    fun oneAdtsLookingPcmPacketDoesNotFlipTheDecision() {
        val first = AudioSynthetic.pcm(0).also { it[0] = 0xFF.toByte(); it[1] = 0xF0.toByte() }
        assertTrue(Adts.looksLike(first, 0, first.size), "这一包单看确实像")
        var bytes = AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(0, first))
        for (i in 1 until 5) bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.pcm(i * 1024)))
        val recorder = Recorder()
        depacketizer(recorder, AudioSynthetic.stream(codec = AudioCodec.AAC)).feedInPieces(bytes)
        assertEquals(AudioCodec.PCM, recorder.framing?.payloadCodec)
    }

    @Test
    fun pcmPayloadThatIsNotWholeFramesIsReportedOnce() {
        var bytes = ByteArray(0)
        repeat(5) { i -> bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, AudioSynthetic.pcm(i * 1024).copyOf(4094))) }
        val recorder = Recorder()
        depacketizer(recorder).feedInPieces(bytes)
        assertEquals(AudioCodec.PCM, recorder.framing?.payloadCodec)
        assertEquals(1, recorder.notes.count { "不是整帧" in it })
    }

    /** PCM 载荷前的 12 字节头：第 10～11 字节等于其后的数据长度就去掉；规则只记一次，头部记前 3 包。 */
    @Test
    fun pcmHeaderIsStrippedWhenItsLengthFieldMatches() {
        // 10 包才攒够判断字节序的样本（每包 256 帧）。
        val pcm = (0 until 10).map { AudioSynthetic.pcm(it * 256, frames = 256) }
        var bytes = ByteArray(0)
        pcm.forEachIndexed { i, data ->
            val payload = AudioSynthetic.pcmHeader(timestamp = i * 5_333L, dataLength = data.size) + data
            bytes += AudioSynthetic.frame(FrameKind.CONTROL, AudioSynthetic.seal(AudioSynthetic.rtp(i, payload, pt = 0), 60 + i), sealed = true)
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder, AudioSynthetic.stream(codec = AudioCodec.PCM))
        depacketizer.feedInPieces(bytes)
        assertEquals(AudioCodec.PCM, recorder.framing?.payloadCodec)
        assertEquals(listOf(1036, 1036, 1036), recorder.framing?.payloadLengths, "识别结论里是带头的原始长度")
        pcm.forEachIndexed { i, expected -> assertContentEquals(expected, recorder.payloads[i], "第 $i 包去掉了头") }
        assertEquals(10, depacketizer.pcmHeadersStripped)
        assertEquals(0, depacketizer.pcmWithoutHeader)
        assertEquals(1, recorder.notes.count { "12 字节头" in it && "去掉头再放" in it }, "规则只记一次")
        val headers = recorder.notes.filter { it.startsWith("PCM 头") }
        assertEquals(3, headers.size, "前 3 包")
        assertTrue(headers[1].endsWith("0001" + "00000000000014d5" + "0400"), headers[1])
        assertTrue("去掉 12 字节头" in depacketizer.conclusion)
        assertEquals(PcmByteOrder.LITTLE_ENDIAN, depacketizer.pcmByteOrder)
    }

    @Test
    fun pcmPayloadIsKeptWhenTheLengthFieldDoesNotMatch() {
        val pcm = (0 until 5).map { AudioSynthetic.pcm(it * 256, frames = 256) }
        var bytes = ByteArray(0)
        pcm.forEachIndexed { i, data ->
            val payload = AudioSynthetic.pcmHeader(timestamp = 0, dataLength = data.size + 4) + data
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload))
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        assertEquals(1036, recorder.payloads[0].size, "长度对不上就原样放")
        assertEquals(0, depacketizer.pcmHeadersStripped)
        assertEquals(5, depacketizer.pcmWithoutHeader)
        assertTrue(recorder.notes.none { "12 字节头" in it })
    }

    /** 手机分流时 PCM 包尾多出几个字节：只放头里声明的那段，尾巴丢掉，提示一次。 */
    @Test
    fun pcmTrailerAfterDeclaredDataIsDropped() {
        var bytes = ByteArray(0)
        for (i in 0 until 6) {
            val data = AudioSynthetic.pcm(i * 256, frames = 256)
            val payload = AudioSynthetic.pcmHeader(timestamp = i.toLong(), dataLength = data.size) + data + byteArrayOf(1, 2, 3, 4)
            bytes += AudioSynthetic.frame(FrameKind.LENGTH32, AudioSynthetic.rtp(i, payload))
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feedInPieces(bytes)
        assertTrue(recorder.payloads.isNotEmpty())
        assertTrue(recorder.payloads.all { it.size == 1024 }, "只放声明的 1024 字节：${recorder.payloads.map { it.size }}")
        assertEquals(0, depacketizer.pcmWithoutHeader)
        assertEquals(1, recorder.notes.count { "包尾" in it && "多 4 字节" in it }, "尾巴只提示一次")
    }

    /** 头部每 500 包抽一包记下来；中途有包对不上规则时提示一次、原样放。 */
    @Test
    fun pcmHeaderIsSampledEveryFiveHundredPackets() {
        var bytes = ByteArray(0)
        for (i in 0 until 1001) {
            val data = AudioSynthetic.pcm(i * 256, frames = 256)
            val length = if (i == 700) data.size + 2 else data.size
            bytes += AudioSynthetic.frame(FrameKind.LENGTH16, AudioSynthetic.rtp(i, AudioSynthetic.pcmHeader(i.toLong(), length) + data))
        }
        val recorder = Recorder()
        val depacketizer = depacketizer(recorder)
        depacketizer.feed(bytes, 0, bytes.size)
        val headers = recorder.notes.filter { it.startsWith("PCM 头") }
        assertEquals(listOf(1, 2, 3, 500, 1000), headers.map { it.substringAfter("第 ").substringBefore(" 包").toInt() })
        assertEquals(1000, depacketizer.pcmHeadersStripped)
        assertEquals(1, depacketizer.pcmWithoutHeader)
        assertEquals(1, recorder.notes.count { "对不上 12 字节头" in it })
    }

    @Test
    fun rtpParserFollowsRfc3550() {
        val view = RtpView()
        // CSRC 1 个、扩展头 1 个字、填充 2 字节。
        val packet = byteArrayOf(
            0xB1.toByte(), (0x80 or 96).toByte(), 0, 5, 0, 0, 0, 9, 0x11, 0x22, 0x33, 0x44,
            1, 2, 3, 4,
            0x10, 0, 0, 1, 9, 9, 9, 9,
            0x55, 0x66, 0, 2,
        )
        assertTrue(view.parse(packet, 0, packet.size))
        assertFalse(view.isRtcp)
        assertTrue(view.marker)
        assertEquals(96, view.payloadType)
        assertEquals(5, view.sequence)
        assertEquals(9L, view.timestamp)
        assertEquals(24, view.payloadStart)
        assertEquals(26, view.payloadEnd)
        assertEquals("CC=1，X=1（扩展长度字段=1，按 32 位字计），P=1", view.describeHeader())
        assertFalse(view.parse(packet, 0, 20), "扩展头被截断")
        assertTrue(view.parse(AudioSynthetic.rtcpSenderReport(), 0, 28))
        assertTrue(view.isRtcp)
        assertFalse(view.parse(byteArrayOf(0x40, 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1), 0, 13), "版本 1")
    }

    @Test
    fun pcmProbeSeparatesAudioFromNoise() {
        val tone = AudioSynthetic.pcm(0)
        assertTrue(PcmProbe.looksLikePcm(tone, 0, tone.size, 2))
        val loud = AudioSynthetic.pcm(0, amplitude = 30_000.0)
        assertTrue(PcmProbe.looksLikePcm(loud, 0, loud.size, 2))
        val silence = ByteArray(4096)
        assertTrue(PcmProbe.looksLikePcm(silence, 0, silence.size, 2))
        val noise = ByteArray(4096).also { Random(1).nextBytes(it) }
        assertFalse(PcmProbe.looksLikePcm(noise, 0, noise.size, 2))
        assertTrue(PcmProbe.looksLikePcm(noise, 0, 100, 2), "太短不判断")
    }
}
