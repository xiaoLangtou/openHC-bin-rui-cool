package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.session.DisplaySettings
import kotlin.math.max
import kotlin.math.roundToInt

/** 车机屏幕：实际像素（含状态栏、导航栏，投屏时全屏）、DPI，以及按刷新率算出的默认帧率。 */
data class ScreenSpec(val width: Int, val height: Int, val densityDpi: Int, val autoFps: Int)

/** 设置里的分辨率选项。 */
sealed class ResolutionChoice {
    /** 按屏幕实际尺寸。 */
    object Auto : ResolutionChoice() {
        override fun toString() = AUTO_TEXT
    }

    data class Fixed(val width: Int, val height: Int) : ResolutionChoice() {
        init {
            require(width > 0 && height > 0) { "分辨率必须是正数" }
        }

        override fun toString() = "${width}x$height"
    }

    companion object {
        const val AUTO_TEXT = "auto"

        /** 解析设置里存的文本（`auto` 或 `宽x高`）；认不出来按自动。 */
        fun parse(text: String?): ResolutionChoice {
            if (text == null || text == AUTO_TEXT) return Auto
            val parts = text.split('x')
            if (parts.size != 2) return Auto
            val w = parts[0].toIntOrNull()
            val h = parts[1].toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) return Auto
            return Fixed(w, h)
        }
    }
}

/**
 * 声明给手机的画布怎么定。纯逻辑。
 *
 * - 画布：自动时取屏幕实际尺寸（投屏页全屏沉浸，画面铺满整块屏）；宽高向下取偶数（H.264 按宏块编码，奇数边长没意义）。
 * - DPI：按屏幕 DPI 声明；画布比屏幕小时，车机显示时会把画面放大，DPI 按同样比例缩小，手机界面上的元素在车机屏上
 *   的实际大小就不变。再乘以设置里的「界面大小」百分比：有的车机为了好点按把系统 DPI 设得偏高，大屏上手机界面
 *   显得很大，声明的 DPI 调小后手机按更小的缩放排版，同一屏放得下更多内容；画布和码率不变。缩放比例（density）= DPI / 160。
 * - 帧率：自动时按屏幕刷新率（见 platform 的 FrameRatePolicy），否则用设置的值。
 * - 关键帧间隔（GOP）：一律声明，约 [KEY_FRAME_SECONDS] 秒一个关键帧，见 [keyFrameInterval]。
 */
object DisplayPlan {
    /** 设置页列出的固定分辨率（常见车机屏）。 */
    val PRESETS: List<ResolutionChoice.Fixed> = listOf(
        ResolutionChoice.Fixed(1920, 1080),
        ResolutionChoice.Fixed(1280, 720),
        ResolutionChoice.Fixed(1024, 600),
    )

    /** 设置页列出的固定帧率；0 表示自动。 */
    val FPS_CHOICES: List<Int> = listOf(30, 60)

    /** 设置页列出的「界面大小」（声明 DPI 的百分比），第一个是默认。 */
    val UI_SCALE_CHOICES: List<Int> = listOf(100, 85, 75, 65)

    const val DEFAULT_UI_SCALE = 100

    private const val BASE_DPI = 160.0
    private const val MIN_DPI = 60

    /** 大约几秒一个关键帧。 */
    const val KEY_FRAME_SECONDS = 2

    /** 协议允许声明的关键帧间隔（帧数）。 */
    const val MIN_GOP = 30
    const val MAX_GOP = 600

    /** [uiScalePercent]：界面大小，见 [UI_SCALE_CHOICES]；只能缩小（1～100）。 */
    fun resolve(
        screen: ScreenSpec,
        resolution: ResolutionChoice,
        fpsSetting: Int,
        uiScalePercent: Int = DEFAULT_UI_SCALE,
    ): DisplaySettings {
        require(screen.width > 0 && screen.height > 0 && screen.densityDpi > 0 && screen.autoFps > 0) { "屏幕参数非法: $screen" }
        require(uiScalePercent in 1..100) { "界面大小只能是 1～100%: $uiScalePercent" }
        val (rawW, rawH) = when (resolution) {
            ResolutionChoice.Auto -> screen.width to screen.height
            is ResolutionChoice.Fixed -> resolution.width to resolution.height
        }
        val width = even(rawW)
        val height = even(rawH)
        // 车机把画面等比放大到屏幕上的倍数。
        val displayScale = minOf(screen.width.toDouble() / width, screen.height.toDouble() / height)
        val dpi = max(MIN_DPI, (screen.densityDpi / displayScale * uiScalePercent / 100.0).roundToInt())
        val fps = if (fpsSetting > 0) fpsSetting else screen.autoFps
        return DisplaySettings(
            width = width,
            height = height,
            fps = fps,
            dpi = dpi,
            density = dpi / BASE_DPI,
            gop = keyFrameInterval(fps),
            // 码率上下限（1028/1029）默认不声明，只有清晰度「稳定」档才声明（见 withClarity）。
        )
    }

    /**
     * 声明给手机的关键帧间隔（帧数）：帧率 × [KEY_FRAME_SECONDS]，限制在 [MIN_GOP]～[MAX_GOP]。
     *
     * 车机这边丢了帧、换了解码器，都要等到下一个关键帧才能接着解，协议里又没有「请马上发关键帧」的消息；
     * 不声明时手机可能很久才发一个，期间画面一直出不来。声明了手机会照做，最坏也只等这么久。
     * 也不宜太密：关键帧比普通帧大好几倍，太密费码率。
     */
    fun keyFrameInterval(fps: Int): Int {
        require(fps > 0) { "帧率必须是正数" }
        return (fps.toLong() * KEY_FRAME_SECONDS).coerceIn(MIN_GOP.toLong(), MAX_GOP.toLong()).toInt()
    }

    private fun even(value: Int): Int = max(2, value and 1.inv())
}
