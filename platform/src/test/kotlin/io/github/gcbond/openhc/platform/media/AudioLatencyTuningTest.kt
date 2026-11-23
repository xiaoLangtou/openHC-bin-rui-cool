package io.github.gcbond.openhc.platform.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 降延迟的纯逻辑：缓冲参数与声音延后的帧数换算、队列的稳态削峰、延迟拆分的推算。 */
class AudioLatencyTuningTest {

    // ---------------- 参数换算 ----------------

    @Test
    fun defaultTuningIsTheLowestBuffering() {
        val tuning = AudioTuning()
        assertEquals(3840, tuning.startFrames(48_000), "起播 80ms")
        assertEquals(4800, tuning.targetFrames(48_000), "削峰目标 100ms")
        assertEquals(14_400, tuning.maxFrames(48_000), "上限 300ms")
        // 音轨约 40ms：48kHz 双声道 16 位是 1920 帧 × 4 字节；系统最小值更大时用系统的。
        assertEquals(7680, tuning.trackBytes(48_000, 4, minimumBytes = 3528))
        assertEquals(15_360, tuning.trackBytes(48_000, 4, minimumBytes = 15_360))
        assertTrue(tuning.lowLatency)
        assertEquals(960, tuning.trimStepFrames(48_000), "每次削峰约 20ms")
        assertEquals(1_000, tuning.startWindowMillis)
    }

    @Test
    fun extraDelayIsAddedOnTopOfEveryThreshold() {
        val tuning = AudioTuning(extraDelayMillis = 100)
        assertEquals(8640, tuning.startFrames(48_000), "80 + 100ms")
        assertEquals(9600, tuning.targetFrames(48_000), "100 + 100ms")
        assertEquals(19_200, tuning.maxFrames(48_000), "300 + 100ms")
        assertEquals(7680, tuning.trackBytes(48_000, 4, minimumBytes = 0), "音轨缓冲不跟着延后")
        assertEquals(2400, AudioTuning.framesFor(48_000, 50))
        assertEquals(13_230, AudioTuning.framesFor(44_100, 300))
        assertEquals(1, AudioTuning.framesFor(8_000, 0), "至少 1 帧")
        assertEquals(21, AudioTuning.millisFor(48_000, 1024))
        assertTrue("声音延后 +100ms" in tuning.toString())
    }

    @Test
    fun wirelessCushionStacksOnTheUserDelay() {
        val user = AudioTuning(extraDelayMillis = 100, mediaStream = AudioStreamChoice.AUTO)
        val tuning = user.withCushion(300)
        assertEquals(AudioTuning.framesFor(48_000, 80 + 100 + 300), tuning.startFrames(48_000))
        assertEquals(AudioTuning.framesFor(48_000, 100 + 100 + 300), tuning.targetFrames(48_000))
        assertEquals(AudioTuning.framesFor(48_000, 300 + 100 + 300), tuning.maxFrames(48_000))
        assertEquals(user.trackBytes(48_000, 4, 0), tuning.trackBytes(48_000, 4, 0), "音轨缓冲不跟着垫")
        assertEquals(user.lowLatency, tuning.lowLatency)
        assertTrue("无线垫底 +300ms" in tuning.toString() && "声音延后 +100ms" in tuning.toString(), tuning.toString())
        assertTrue("无线垫底" !in user.toString())
        assertFailsWith<IllegalArgumentException> { user.withCushion(-1) }
    }

    @Test
    fun tuningRejectsBadValues() {
        assertFailsWith<IllegalArgumentException> { AudioTuning(startMillis = 0) }
        assertFailsWith<IllegalArgumentException> { AudioTuning(startMillis = 120, targetMillis = 100) }
        assertFailsWith<IllegalArgumentException> { AudioTuning(extraDelayMillis = -50) }
        assertFailsWith<IllegalArgumentException> { AudioTuning(extraDelayMillis = 2_000) }
    }

    // ---------------- 稳态削峰 ----------------

    private class FakeClock {
        var millis = 0L
        fun nanos() = millis * 1_000_000
    }

    private fun queue(clock: FakeClock, target: Int = 300, start: Int = 100, step: Int = 1, startWindow: Long = 0) = AudioJitterQueue(
        startFrames = start,
        targetFrames = target,
        maxFrames = 10_000,
        trimHoldMillis = 2_000,
        trimSpacingMillis = 200,
        trimStepFrames = step,
        startWindowMillis = startWindow,
        clockNanos = clock::nanos,
    )

    /** 每 [stepMillis] 一进一出（各 100 帧）；返回每次削峰发生的时刻。 */
    private fun steady(queue: AudioJitterQueue, clock: FakeClock, untilMillis: Long, stepMillis: Long = 20): List<Long> {
        val trimTimes = ArrayList<Long>()
        while (clock.millis < untilMillis) {
            val before = queue.trimmedChunks
            queue.offer(ByteArray(4), 100)
            assertNotNull(queue.take(0))
            if (queue.trimmedChunks > before) trimTimes += clock.millis
            clock.millis += stepMillis
        }
        return trimTimes
    }

    @Test
    fun backlogThatStaysAboveTargetIsTrimmedOneChunkAtATime() {
        val clock = FakeClock()
        val queue = queue(clock)
        repeat(6) { queue.offer(ByteArray(4), 100) }
        // 一进一出时取走后的低点是 600 帧，高于目标 300。
        val trims = steady(queue, clock, untilMillis = 5_000)
        assertEquals(listOf(2_000L, 2_200L, 2_400L), trims, "持续 2 秒才开始，两次之间隔 200ms，每次一块，压到目标就停")
        assertEquals(300, queue.trimmedFrames)
        assertEquals(300, queue.bufferedFrames, "低点回到目标")
        assertEquals(0, queue.droppedFrames, "削峰不算突发超限")
    }

    /** 小块（PCM 一包约 5ms）时每次削峰丢约一个步长：至少一块，再丢会超过步长就停。 */
    @Test
    fun eachTrimDropsAboutOneStepOfSmallChunks() {
        val clock = FakeClock()
        val queue = queue(clock, start = 50, step = 200)
        repeat(20) { queue.offer(ByteArray(4), 50) }
        val dropped = ArrayList<Long>()
        val times = ArrayList<Long>()
        while (clock.millis < 5_000) {
            val before = queue.trimmedFrames
            queue.offer(ByteArray(4), 50)
            assertNotNull(queue.take(0))
            if (queue.trimmedFrames > before) {
                dropped += queue.trimmedFrames - before
                times += clock.millis
            }
            clock.millis += 20
        }
        assertEquals(listOf(2_000L, 2_200L, 2_400L, 2_600L), times)
        assertEquals(listOf(200L, 200L, 200L, 100L), dropped, "最后一次只丢到目标")
        assertEquals(300, queue.bufferedFrames)
    }

    /** 起播窗口里：取数时高于目标就直接压回目标（不低于目标），不等 2 秒；窗口过后不再这样做。 */
    @Test
    fun startBurstIsPulledBackToTargetWithinTheStartWindow() {
        val clock = FakeClock()
        val queue = queue(clock, startWindow = 1_000)
        repeat(10) { queue.offer(ByteArray(4), 100) }
        assertNotNull(queue.take(0))
        assertEquals(700, queue.startTrimmedFrames, "1000 帧压到 300，再取走一块")
        assertEquals(200, queue.bufferedFrames)
        clock.millis = 500
        repeat(5) { queue.offer(ByteArray(4), 100) }
        assertNotNull(queue.take(0))
        assertEquals(1_100, queue.startTrimmedFrames, "窗口里晚到的一批也压回去")
        clock.millis = 1_500
        repeat(5) { queue.offer(ByteArray(4), 100) }
        assertNotNull(queue.take(0))
        assertEquals(1_100, queue.startTrimmedFrames, "窗口过了交给稳态削峰")
        assertEquals(0, queue.trimmedFrames)
    }

    @Test
    fun restartAfterStarvationOpensANewStartWindow() {
        val clock = FakeClock()
        val queue = queue(clock, start = 200, startWindow = 1_000)
        repeat(2) { queue.offer(ByteArray(4), 100) }
        repeat(2) { assertNotNull(queue.take(0)) }
        queue.onStarved()
        clock.millis = 10_000
        repeat(8) { queue.offer(ByteArray(4), 100) }
        assertNotNull(queue.take(0))
        assertEquals(500, queue.startTrimmedFrames, "重新起播时 800 帧压回 300")
    }

    @Test
    fun levelAtOrBelowTargetIsNeverTrimmed() {
        val clock = FakeClock()
        val queue = queue(clock)
        repeat(3) { queue.offer(ByteArray(4), 100) }
        assertTrue(steady(queue, clock, untilMillis = 10_000).isEmpty())
        assertEquals(0, queue.trimmedChunks)
    }

    @Test
    fun dippingToTargetRestartsTheHoldTime() {
        val clock = FakeClock()
        val queue = queue(clock)
        repeat(6) { queue.offer(ByteArray(4), 100) }
        steady(queue, clock, untilMillis = 1_500)
        // 只取不放：低点落到目标（300）以内，计时重来。
        repeat(3) { assertNotNull(queue.take(0)) }
        clock.millis += 20
        repeat(3) { queue.offer(ByteArray(4), 100) }
        val trims = steady(queue, clock, untilMillis = 5_000)
        assertEquals(1_520L + 2_000L, trims.first(), "从重新高于目标那一刻起再等 2 秒")
    }

    @Test
    fun starvationRestartsTheHoldTime() {
        val clock = FakeClock()
        val queue = queue(clock)
        repeat(6) { queue.offer(ByteArray(4), 100) }
        steady(queue, clock, untilMillis = 1_900)
        queue.onStarved()
        val trims = steady(queue, clock, untilMillis = 5_000)
        assertEquals(1_900L + 2_000L, trims.first(), "放空之后计时重来")
    }

    @Test
    fun theLastChunkIsNeverTrimmed() {
        val clock = FakeClock()
        val queue = queue(clock, target = 100, start = 100)
        queue.offer(ByteArray(4), 100)
        queue.offer(ByteArray(4), 500)
        assertNotNull(queue.take(0))
        clock.millis = 3_000
        queue.offer(ByteArray(4), 500)
        assertNotNull(queue.take(0))
        assertEquals(500, queue.bufferedFrames, "取走后只剩一块：高于目标也不削")
        assertEquals(0, queue.trimmedChunks)
    }

    // ---------------- 延迟拆分 ----------------

    @Test
    fun latencyIsSplitIntoFourParts() {
        val latency = AudioLatency.estimate(
            sampleRate = 48_000,
            queueFrames = 2048,
            decoderFrames = 1024,
            framesWritten = 100_000,
            headPosition = 98_080,
            timestampFrame = 96_640,
            timestampNanos = 1_000_000_000,
            nowNanos = 1_010_000_000,
        )
        assertEquals(42, latency.queueMillis)
        assertEquals(21, latency.decoderMillis)
        assertEquals(40, latency.trackMillis, "写入 100000、播放头 98080：1920 帧")
        // 时间戳那一刻正在输出 96640，过了 10ms（480 帧）推到 97120，比播放头晚 960 帧。
        assertEquals(20, latency.hardwareMillis)
        assertEquals(123, latency.totalMillis)
        assertTrue(latency.toString().startsWith("我们这边总延迟 123ms = 队列 42ms + 解码 21ms + 音轨已写未播 40ms + 硬件 20ms"))
    }

    @Test
    fun unknownHardwarePartIsNotGuessed() {
        val latency = AudioLatency.estimate(48_000, 0, 0, 4800, 0, null, 0, 0)
        assertNull(latency.hardwareMillis)
        assertNull(latency.totalMillis)
        assertTrue("硬件部分未知" in latency.toString() && "至少 100ms" in latency.toString(), latency.toString())
    }

    @Test
    fun positionsWrapAtThirtyTwoBits() {
        val wrapped = 1L shl 32
        // 真实位置：写入 2^32 + 500、播放头 2^32 + 20（报上来的 32 位值是 20）、正在输出 2^32 − 460（还没回绕）。
        val latency = AudioLatency.estimate(
            sampleRate = 48_000,
            queueFrames = 0,
            decoderFrames = -5,
            framesWritten = wrapped + 500,
            headPosition = 20,
            timestampFrame = wrapped - 460,
            timestampNanos = 0,
            nowNanos = 0,
        )
        assertEquals(0, latency.decoderMillis, "负数按 0")
        assertEquals(10, latency.trackMillis, "跨过 2^32 仍是 480 帧")
        assertEquals(10, latency.hardwareMillis, "跨过 2^32 仍是 480 帧")
        // 推算略超过播放头（10ms 以内，取整误差）按 0；超过太多（时间戳太旧）记为未知，不出负数也不估。
        val slightly = AudioLatency.estimate(48_000, 0, 0, 1000, 500, 600, 0, 0)
        assertEquals(0, slightly.hardwareMillis)
        val stale = AudioLatency.estimate(48_000, 0, 0, 1000, 500, 400, 0, 1_000_000_000)
        assertNull(stale.hardwareMillis)
        assertTrue("硬件部分未知" in stale.toString())
    }
}
