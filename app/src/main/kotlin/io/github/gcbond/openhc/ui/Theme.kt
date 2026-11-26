package io.github.gcbond.openhc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 界面的配色、字号和几个公共尺寸。深色、浅色两套配色，页面顶上正中的太阳 / 月亮切换（见 [ThemeToggle]），默认深色。

/**
 * 配色。各颜色按 [dark] 取深色或浅色那一套；[dark] 是快照状态，切换后读过它的界面都会重画，
 * 所以只能在组合、布局、绘制时读，不要存进 remember 或顶层常量。
 */
internal object Palette {
    /** 当前是不是深色。由 [MainActivity][io.github.gcbond.openhc.MainActivity] 按设置写入，[ThemeToggle] 切换。 */
    var dark by mutableStateOf(true)

    private fun pick(dark: Long, light: Long) = Color(if (this.dark) dark else light)

    /** 页面底色。 */
    val Background get() = pick(0xFF0D1117, 0xFFF2F4F7)

    /** 卡片、分组框。 */
    val Card get() = pick(0xFF161B22, 0xFFFFFFFF)

    /** 卡片描边：浅色时白卡片放在浅灰底上要一圈细线，深色时不画。 */
    val CardBorder get() = pick(0x00000000, 0xFFE3E7ED)
    val Text get() = pick(0xFFF0F3F7, 0xFF0F172A)

    /** 比正文淡一档、仍要读的文字（卡片里的一句话说明）。 */
    val Text2 get() = pick(0xFFC9D1DC, 0xFF334155)

    /** 次要文字：说明、页脚。 */
    val Muted get() = pick(0xFFA0A9B8, 0xFF556070)

    /** 小标题、图标。 */
    val Accent get() = pick(0xFF7FB0FF, 0xFF1D4ED8)

    /** 图标底下的浅色方块。 */
    val Tint get() = if (dark) Color(0x292563EB) else Color(0x1A2563EB)

    /** 要用户注意的：缺东西、出错、需要重开才生效。 */
    val Warn get() = pick(0xFFF4B740, 0xFF9A6200)
    val WarnBackground get() = if (dark) Color(0x1AF4B740) else Color(0xFFFFF6E0)
    val WarnBorder get() = if (dark) Color(0x59F4B740) else Color(0xFFF2C76B)

    /** 好的状态（开着、已连上）的小圆点。 */
    val Positive get() = pick(0xFF3DD68C, 0xFF16A34A)

    /** 主按钮、开关打开、选项选中。上面的字用 [OnAccent]。 */
    val On get() = Color(0xFF2563EB)
    val OnAccent get() = Color.White
    val SwitchOff get() = pick(0xFF3A4352, 0xFFCBD2DC)

    /** 次要按钮（「设置」「返回」「停止」、行内小按钮）的底色和字色。 */
    val Button get() = pick(0xFF262E3B, 0xFFE8ECF1)
    val ButtonText get() = pick(0xFFF0F3F7, 0xFF0F172A)

    /** 分段选择、声道一排的底槽。 */
    val SegmentBackground get() = pick(0xFF0D1117, 0xFFEEF1F5)
    val Divider get() = pick(0xFF232A35, 0xFFE8ECF1)

    /** 连接码方框。 */
    val PinBox get() = pick(0xFF262E3B, 0xFFE8ECF1)
    val PinText get() = pick(0xFFF0F3F7, 0xFF0F172A)

    /** 弹出框的底色。 */
    val Raised get() = pick(0xFF1E2530, 0xFFFFFFFF)

    /** 设置页左边目录里选中的那一项。 */
    val NavSelected get() = pick(0xFF1E2530, 0xFFDCE7FD)
    val NavSelectedText get() = pick(0xFFFFFFFF, 0xFF1E3A8A)

    /** 太阳、月亮选中时的颜色。 */
    val Sun get() = Color(0xFFD97706)
    val Moon get() = Color(0xFFFFD27A)
}

/** 公共尺寸。 */
internal object Dimens {
    /** 页面左右留白；窄屏（宽不到 [WIDE_PAGE_MIN]）收成 [PageSideNarrow]。 */
    val PageSide = 32.dp
    val PageSideNarrow = 16.dp
    val WIDE_PAGE_MIN = 600.dp

    /** 低于这个高度算矮屏：字号、间距收一档。 */
    val CompactHeight = 560.dp
    val CardRadius = 20.dp
    val CardPadding = 24.dp
    val CardPaddingCompact = 16.dp
}

/** 当前是不是矮屏（见 [Dimens.CompactHeight]），由各页面的根布局按实际高度提供。 */
internal val LocalCompact = compositionLocalOf { false }

/** 各处文字样式。只定字号、粗细，颜色由调用处给；字距一律为 0（中文加字距反而难看）。 */
internal object UiType {
    private val base = TextStyle(letterSpacing = 0.sp)

    fun title(compact: Boolean) = base.copy(fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
    val topButton = base.copy(fontSize = 17.sp)
    val pill = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Medium)
    fun action(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp)

    // 首页卡片
    fun cardTitle(compact: Boolean) = base.copy(fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
    fun cardText(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 17.sp, lineHeight = 1.55.em)
    fun cardHeader(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 15.sp)
    val status = base.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold)
    val hint = base.copy(fontSize = 14.sp, lineHeight = 1.5.em)
    fun message(compact: Boolean) = base.copy(fontSize = if (compact) 17.sp else 20.sp, lineHeight = 1.5.em)
    fun footer(compact: Boolean) = base.copy(fontSize = if (compact) 12.sp else 13.sp, lineHeight = 1.4.em)

    // 设置页
    fun groupTitle(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold)
    fun nav(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 17.sp)
    fun rowTitle(compact: Boolean) = base.copy(fontSize = if (compact) 16.sp else 18.sp)
    fun rowSummary(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = 1.45.em)
    fun rowDesc(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, lineHeight = 1.5.em)
    fun rowNote(compact: Boolean) =
        base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Bold, lineHeight = 1.4.em)
    fun option(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 16.sp)
    fun value(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp, fontFamily = FontFamily.Monospace)
    fun chevron(compact: Boolean) = base.copy(fontSize = if (compact) 18.sp else 20.sp)
}

/**
 * 全应用的主题：按 [Palette.dark] 用深色或浅色。还在用的 Material 组件（输入框、弹框）从这里取色：
 * 主色是开关蓝，「错误」用提醒用的琥珀色，和自己画的控件一致。
 */
@Composable
internal fun OpenHcTheme(content: @Composable () -> Unit) {
    val colors = if (Palette.dark) {
        darkColorScheme(
            primary = Palette.On,
            onPrimary = Palette.OnAccent,
            primaryContainer = Palette.On,
            onPrimaryContainer = Palette.OnAccent,
            secondary = Palette.Accent,
            onSecondary = Palette.Background,
            tertiary = Palette.Accent,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Background,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Raised,
            onSurfaceVariant = Palette.Muted,
            surfaceContainerLowest = Palette.Background,
            surfaceContainerLow = Palette.Raised,
            surfaceContainer = Palette.Raised,
            surfaceContainerHigh = Palette.Raised,
            surfaceContainerHighest = Palette.Raised,
            outline = Palette.Muted,
            outlineVariant = Palette.SwitchOff,
            error = Palette.Warn,
            onError = Palette.Background,
            errorContainer = Palette.Warn,
            onErrorContainer = Palette.Background,
        )
    } else {
        lightColorScheme(
            primary = Palette.On,
            onPrimary = Palette.OnAccent,
            primaryContainer = Palette.On,
            onPrimaryContainer = Palette.OnAccent,
            secondary = Palette.Accent,
            onSecondary = Palette.Card,
            tertiary = Palette.Accent,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Card,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Button,
            onSurfaceVariant = Palette.Muted,
            surfaceContainerLowest = Palette.Card,
            surfaceContainerLow = Palette.Card,
            surfaceContainer = Palette.Card,
            surfaceContainerHigh = Palette.Card,
            surfaceContainerHighest = Palette.Button,
            outline = Palette.Muted,
            outlineVariant = Palette.SwitchOff,
            error = Palette.Warn,
            onError = Palette.Card,
            errorContainer = Palette.WarnBackground,
            onErrorContainer = Palette.Warn,
        )
    }
    // 中文不加字距；其余沿用默认字号。
    val d = Typography()
    val typography = Typography(
        bodyLarge = d.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = d.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = d.bodySmall.copy(letterSpacing = 0.sp),
        labelLarge = d.labelLarge.copy(letterSpacing = 0.sp),
        labelMedium = d.labelMedium.copy(letterSpacing = 0.sp),
        labelSmall = d.labelSmall.copy(letterSpacing = 0.sp),
        titleMedium = d.titleMedium.copy(letterSpacing = 0.sp),
        titleSmall = d.titleSmall.copy(letterSpacing = 0.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
