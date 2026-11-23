package io.github.gcbond.openhc.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 声明给手机的画布、DPI、帧率和关键帧间隔。 */
class DisplayPlanTest {

    private val screen = ScreenSpec(width = 1920, height = 1080, densityDpi = 240, autoFps = 55)

    @Test
    fun autoUsesTheWholeScreenAndItsRefreshRate() {
        val display = DisplayPlan.resolve(screen, ResolutionChoice.Auto, fpsSetting = 0)
        assertEquals(1920 to 1080, display.width to display.height)
        assertEquals(55, display.fps)
        assertEquals(240, display.dpi)
        assertEquals(1.5, display.density)
        assertEquals(110, display.gop, "默认就声明关键帧间隔：55fps 约 2 秒一个")
    }

    @Test
    fun uiScaleShrinksOnlyTheDeclaredDpi() {
        // 系统 240 dpi 的屏：85% 声明 204，画布、帧率不变。
        val smaller = DisplayPlan.resolve(screen, ResolutionChoice.Auto, fpsSetting = 0, uiScalePercent = 85)
        assertEquals(1920 to 1080, smaller.width to smaller.height)
        assertEquals(55, smaller.fps)
        assertEquals(204, smaller.dpi)
        assertEquals(204 / 160.0, smaller.density)
        assertEquals(156, DisplayPlan.resolve(screen, ResolutionChoice.Auto, 0, uiScalePercent = 65).dpi)
        // 和画布缩放叠加：1280x720 画布放大 1.5 倍显示，DPI 先除以 1.5 再乘 75%。
        assertEquals(120, DisplayPlan.resolve(screen, ResolutionChoice.Fixed(1280, 720), 0, uiScalePercent = 75).dpi)
        // 默认 100% 和原来一样。
        assertEquals(240, DisplayPlan.resolve(screen, ResolutionChoice.Auto, 0).dpi)
        assertEquals(DisplayPlan.DEFAULT_UI_SCALE, DisplayPlan.UI_SCALE_CHOICES.first())
        assertFailsWith<IllegalArgumentException> { DisplayPlan.resolve(screen, ResolutionChoice.Auto, 0, uiScalePercent = 0) }
        assertFailsWith<IllegalArgumentException> { DisplayPlan.resolve(screen, ResolutionChoice.Auto, 0, uiScalePercent = 120) }
    }

    @Test
    fun gopFollowsTheDeclaredFps() {
        assertEquals(60, DisplayPlan.resolve(screen, ResolutionChoice.Fixed(1280, 720), fpsSetting = 30).gop)
        assertEquals(120, DisplayPlan.resolve(screen, ResolutionChoice.Auto, fpsSetting = 60).gop)
    }

    @Test
    fun gopIsAboutTwoSecondsWithinTheAllowedRange() {
        assertEquals(120, DisplayPlan.keyFrameInterval(60))
        assertEquals(96, DisplayPlan.keyFrameInterval(48))
        assertEquals(30, DisplayPlan.keyFrameInterval(15), "正好在下限")
        assertEquals(30, DisplayPlan.keyFrameInterval(10), "不足下限按下限")
        assertEquals(30, DisplayPlan.keyFrameInterval(1))
        assertEquals(600, DisplayPlan.keyFrameInterval(300), "正好在上限")
        assertEquals(600, DisplayPlan.keyFrameInterval(500), "超出上限按上限")
        assertEquals(600, DisplayPlan.keyFrameInterval(Int.MAX_VALUE), "不会溢出")
        assertFailsWith<IllegalArgumentException> { DisplayPlan.keyFrameInterval(0) }
    }

    @Test
    fun smallerCanvasScalesTheDpiDown() {
        // 1280×720 显示在 1920×1080 的屏上放大 1.5 倍：DPI 缩小 1.5 倍，界面元素实际大小不变。
        val display = DisplayPlan.resolve(screen, ResolutionChoice.Fixed(1280, 720), fpsSetting = 30)
        assertEquals(1280 to 720, display.width to display.height)
        assertEquals(30, display.fps)
        assertEquals(160, display.dpi)
        assertEquals(1.0, display.density)
    }

    @Test
    fun differentAspectRatioUsesTheFittingScale() {
        // 1024×600 在 1920×1080 上按高放大 1.8 倍（按宽是 1.875 倍，装不下）。
        val display = DisplayPlan.resolve(screen, ResolutionChoice.Fixed(1024, 600), fpsSetting = 0)
        assertEquals(133, display.dpi)
    }

    @Test
    fun oddSizesAreRoundedDownToEven() {
        val odd = ScreenSpec(width = 1365, height = 767, densityDpi = 160, autoFps = 60)
        val display = DisplayPlan.resolve(odd, ResolutionChoice.Auto, fpsSetting = 0)
        assertEquals(1364 to 766, display.width to display.height)
    }

    @Test
    fun rejectsBrokenScreenSpecs() {
        assertFailsWith<IllegalArgumentException> {
            DisplayPlan.resolve(ScreenSpec(0, 1080, 160, 60), ResolutionChoice.Auto, 0)
        }
    }

    @Test
    fun resolutionSettingRoundTrips() {
        assertEquals(ResolutionChoice.Auto, ResolutionChoice.parse(null))
        assertEquals(ResolutionChoice.Auto, ResolutionChoice.parse(ResolutionChoice.Auto.toString()))
        for (preset in DisplayPlan.PRESETS) assertEquals(preset, ResolutionChoice.parse(preset.toString()))
        for (bad in listOf("", "1920", "1920x", "x1080", "0x1080", "-1x2", "axb", "1x2x3")) {
            assertEquals(ResolutionChoice.Auto, ResolutionChoice.parse(bad), bad)
        }
    }
}
