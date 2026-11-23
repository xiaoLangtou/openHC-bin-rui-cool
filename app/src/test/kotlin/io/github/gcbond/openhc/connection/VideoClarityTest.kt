package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.session.DisplaySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 清晰度 → 声明给手机的目标码率。 */
class VideoClarityTest {

    @Test
    fun highIsTheDefaultAndDeclaresTwelveMbps() {
        // v7 起默认 HIGH（12 Mbps）：车机解码满血，瓶颈在手机编码端，直接要高码率。
        assertEquals(VideoClarity.HIGH, VideoClarity.DEFAULT)
        assertEquals(12_000_000, VideoClarity.HIGH.bitrate)
        assertNull(VideoClarity.STANDARD.bitrate, "标准不声明，手机按自己的默认码率")
    }

    @Test
    fun highAndHighestDeclareTwelveAndFifteenMbps() {
        assertEquals(12_000_000, VideoClarity.HIGH.bitrate)
        assertEquals(15_000_000, VideoClarity.HIGHEST.bitrate)
        assertEquals(listOf(VideoClarity.STANDARD, VideoClarity.HIGH, VideoClarity.HIGHEST), VideoClarity.entries, "设置页按这个顺序列出")
    }

    @Test
    fun declaredBitratesStayInTheProtocolRange() {
        for (clarity in VideoClarity.entries) {
            val bitrate = clarity.bitrate ?: continue
            assertTrue(bitrate in VideoClarity.MIN_BITRATE..VideoClarity.MAX_BITRATE, "$clarity: $bitrate")
        }
    }

    @Test
    fun storedKeysRoundTripAndUnknownTextFallsBackToDefault() {
        for (clarity in VideoClarity.entries) assertEquals(clarity, VideoClarity.parse(clarity.key))
        for (bad in listOf(null, "", "high ", "max", "12000000")) {
            assertEquals(VideoClarity.DEFAULT, VideoClarity.parse(bad), "$bad")
        }
    }

    @Test
    fun clarityOnlyChangesTheBitrate() {
        val plain = DisplaySettings(width = 1920, height = 1080, fps = 55, dpi = 240, density = 1.5, gop = 110)
        for (clarity in VideoClarity.entries) {
            val declared = plain.withClarity(clarity)
            assertEquals(clarity.bitrate, declared.bitrate, "$clarity")
            assertEquals(listOf(1920, 1080, 55, 240, 110), listOf(declared.width, declared.height, declared.fps, declared.dpi, declared.gop))
            assertEquals(plain.density, declared.density)
        }
    }

    @Test
    fun standardKeepsWhateverWasAlreadyDeclared() {
        val withBitrate = DisplaySettings(width = 1280, height = 720, fps = 30, dpi = 160, density = 1.0, gop = 60, bitrate = 8_000_000)
        assertEquals(8_000_000, withBitrate.withClarity(VideoClarity.STANDARD).bitrate)
        assertEquals(15_000_000, withBitrate.withClarity(VideoClarity.HIGHEST).bitrate)
    }

    @Test
    fun logTextSaysWhetherABitrateIsDeclared() {
        assertTrue("不声明" in VideoClarity.describe(null))
        assertEquals("12000000 bit/s", VideoClarity.describe(VideoClarity.HIGH.bitrate))
    }
}
