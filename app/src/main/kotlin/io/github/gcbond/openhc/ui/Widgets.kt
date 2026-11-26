package io.github.gcbond.openhc.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gcbond.openhc.R
import kotlinx.coroutines.delay

// 首页和设置页共用的控件。全部自己画（不用系统开关、单选框），深浅两套配色下样子才可控。

// ------------------------------------------------------------------ 页面骨架

/** 页面标题（首页的应用名、设置页的「设置」）：单行，矮屏收一档。 */
@Composable
internal fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = UiType.title(LocalCompact.current),
        color = Palette.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 首页左上角的应用标记：蓝底圆角方块里「HC」。 */
@Composable
internal fun AppMark(modifier: Modifier = Modifier) {
    val compact = LocalCompact.current
    Box(
        modifier
            .size(if (compact) 36.dp else 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.On),
        contentAlignment = Alignment.Center,
    ) {
        Text("HC", style = TextStyle(fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold), color = Palette.OnAccent)
    }
}

/** 顶栏的「设置」「返回」：图标加字的圆角按钮。 */
@Composable
internal fun TopBarButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: ImageVector? = null) {
    val compact = LocalCompact.current
    Row(
        modifier
            .heightIn(min = if (compact) 44.dp else 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (glyph != null) 14.dp else 18.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph != null) Icon(glyph, contentDescription = null, tint = Palette.ButtonText, modifier = Modifier.size(22.dp))
        Text(text, style = UiType.topButton, color = Palette.ButtonText, maxLines = 1)
    }
}

/**
 * 顶栏：左边 [start]，右边 [end]，正中是深浅色切换。宽度不够把切换摆正中时（竖屏、小屏），切换挪到右边那组的前面。
 */
@Composable
internal fun TopBar(start: @Composable RowScope.() -> Unit, end: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= TOP_BAR_CENTERED_MIN) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = start)
                ThemeToggle()
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    content = end,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = start)
                ThemeToggle(Modifier.padding(horizontal = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = end)
            }
        }
    }
}

/** 顶栏宽到这个值才把深浅色切换摆在正中（两边各留得下标题和按钮）。 */
private val TOP_BAR_CENTERED_MIN = 720.dp

/** 圆角卡片：首页的无线、有线两张。 */
@Composable
internal fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = Palette.Card,
    border: Color = Palette.CardBorder,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(color)
            .border(1.dp, border, RoundedCornerShape(Dimens.CardRadius))
            .padding(if (LocalCompact.current) Dimens.CardPaddingCompact else Dimens.CardPadding),
        content = content,
    )
}

/** 卡片左上角的图标：浅蓝方块里一个线条图标。 */
@Composable
internal fun IconTile(glyph: ImageVector, modifier: Modifier = Modifier, large: Boolean = false) {
    val compact = LocalCompact.current
    val tile = when {
        large && !compact -> 72.dp
        large || !compact -> 56.dp
        else -> 44.dp
    }
    Box(
        modifier
            .size(tile)
            .clip(RoundedCornerShape(if (large) 20.dp else 16.dp))
            .background(Palette.Tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(tile * 0.54f))
    }
}

/** 要车主处理的事：琥珀色细框的一条，左边提醒图标，右边可以带一个按钮。 */
@Composable
internal fun WarnBanner(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val compact = LocalCompact.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.WarnBackground)
            .border(1.dp, Palette.WarnBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Glyphs.Alert, contentDescription = null, tint = Palette.Warn, modifier = Modifier.size(20.dp))
        Text(text, style = UiType.hint.copy(fontSize = if (compact) 14.sp else 15.sp), color = Palette.Text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.Warn, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = UiType.action(compact), color = Palette.Warn, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ 按钮

/** 大按钮：连接相关的主要操作（开始、回到投屏画面）蓝底白字，次要的（停止）灰底。 */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    wide: Boolean = false,
) {
    val compact = LocalCompact.current
    Box(
        modifier
            .widthIn(min = if (wide) (if (compact) 240.dp else 320.dp) else if (compact) 140.dp else 180.dp)
            .heightIn(min = if (compact) 46.dp else 56.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Palette.On else Palette.Button)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = UiType.pill.copy(fontSize = if (compact) 17.sp else 19.sp),
            color = if (primary) Palette.OnAccent else Palette.ButtonText,
            textAlign = TextAlign.Center,
        )
    }
}

internal enum class ActionKind { NORMAL, PRIMARY, DANGER }

/** 设置页行内、弹框里的小按钮（保存、清除、试听、知道了等）。 */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.NORMAL,
    enabled: Boolean = true,
) {
    val (background, foreground) = when (kind) {
        ActionKind.NORMAL -> Palette.Button to Palette.ButtonText
        ActionKind.PRIMARY -> Palette.On to Palette.OnAccent
        ActionKind.DANGER -> Palette.Warn to Palette.Background
    }
    Box(
        modifier
            .heightIn(min = 42.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UiType.action(LocalCompact.current), color = foreground)
    }
}

private const val DISABLED_ALPHA = 0.4f

// ------------------------------------------------------------------ 弹框

/**
 * 弹框：标题、可滚动的正文、右下角一排按钮。车机屏幕大、误触多，默认点框外不关，按返回或点按钮才关。
 */
@Composable
internal fun DialogCard(
    title: String,
    onDismiss: () -> Unit,
    dismissOnClickOutside: Boolean = false,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside)) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Raised)
                .border(1.dp, Palette.CardBorder, RoundedCornerShape(Dimens.CardRadius))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = UiType.status, color = Palette.Text)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
        }
    }
}

/** 弹框正文的一段。 */
@Composable
internal fun DialogText(text: String, color: Color = Palette.Text) {
    Text(
        text,
        style = UiType.rowTitle(LocalCompact.current).copy(lineHeight = 1.6.em, fontWeight = FontWeight.Normal),
        color = color,
    )
}

// ------------------------------------------------------------------ 连接码

/** 连接码：每位一个圆角方框，字高约为方框的八成。 */
@Composable
internal fun PinBoxes(pin: String, box: Dp, gap: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val fontSize = with(LocalDensity.current) { (box * PIN_FONT_RATIO).toSp() }
    val style = TextStyle(fontSize = fontSize, letterSpacing = 0.sp)
    Row(
        modifier.semantics(mergeDescendants = true) { if (description != null) contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        for (digit in pin) {
            Box(
                Modifier
                    .padding(horizontal = gap)
                    .size(box)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.PinBox),
                contentAlignment = Alignment.Center,
            ) {
                Text(digit.toString(), style = style, color = Palette.PinText, maxLines = 1)
            }
        }
    }
}

private const val PIN_FONT_RATIO = 0.8f

// ------------------------------------------------------------------ 设置页：分组与行

/** 设置页左边有目录时，目录已经写了分组名，分组不再重复写（见 [SettingsGroup]）。 */
internal val LocalGroupTitles = compositionLocalOf { true }

/**
 * 设置页的一个分组：组名（有目录时不写），组里各行放在同一个圆角框里，行与行之间一条左侧缩进的细线。
 *
 * 行要用下面的 [SwitchRow]、[ChoiceRow]、[CardChoiceRow]、[ValueRow]、[GroupRow]：每一行是一个根节点，自己在顶上画分隔线，
 * 分组把第一行顶上那条裁掉。不带分隔线的内容（比如直接放一个 Text）也能放，只是和上一行之间没有线。
 */
@Composable
internal fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalCompact.current
    Column(modifier.fillMaxWidth()) {
        if (LocalGroupTitles.current) {
            Text(
                title,
                style = UiType.groupTitle(compact),
                color = Palette.Muted,
                modifier = Modifier.padding(start = 4.dp, bottom = if (compact) 6.dp else 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Card)
                .border(1.dp, Palette.CardBorder, RoundedCornerShape(Dimens.CardRadius))
                .hideFirstDivider(),
            content = content,
        )
    }
}

/** 组里的一行开关：[名称 ? / 一句话 / 提示]  [滑动开关]，整行可点。[description] 收在「?」里。 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    note: String? = null,
    noteWarn: Boolean = true,
    onNoteClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    summary: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .rowPadding(compact)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title, summary, description, note, noteWarn, onNoteClick, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        PillSwitch(checked)
    }
}

/**
 * 组里的一行选项：名称、一句话、提示，加一条分段选择。选项少（不超过 [INLINE_OPTIONS_MAX] 个）、宽度够时摆在名称右边，
 * 否则在下面铺满一行。[selected] 为 -1 时都不高亮。
 */
@Composable
internal fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: Int,
    description: String? = null,
    note: String? = null,
    summary: String? = null,
    onSelect: (Int) -> Unit,
) {
    val compact = LocalCompact.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .rowPadding(compact),
    ) {
        val width = maxWidth
        if (options.size <= INLINE_OPTIONS_MAX && width >= INLINE_CHOICE_MIN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowTexts(title, summary, description, note, true, null, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Segmented(options, selected, onSelect, Modifier.widthIn(max = width * 0.55f), fill = false)
            }
        } else {
            Column {
                RowTexts(title, summary, description, note, true, null, Modifier)
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
                Segmented(options, selected, onSelect, Modifier.fillMaxWidth(), fill = true)
            }
        }
    }
}

private const val INLINE_OPTIONS_MAX = 4
private val INLINE_CHOICE_MIN = 560.dp

/** [CardChoiceRow] 的一个选项：名称、一句话说明、可选的小标签（如「试验」）。 */
internal class CardOption(val title: String, val text: String, val tag: String? = null)

/**
 * 组里的一行大选项：名称、一句话，下面几张并排的选项卡片，每张写名称和适合什么情况（文字长、分段放不下时用）。
 * 窄屏时卡片竖着排。
 *
 * 选中的选项有自己的设置时（[detail] 对它返回内容），卡片下面接一块和选中卡片同色的面板，顶上一个小尖角指着选中的卡片，
 * 表明这些设置属于它；换选项时面板淡入淡出、高度跟着平滑变化，展开完把整块面板滚进可见范围（刚打开页面时不滚）。
 * 面板里放 [DetailNote] 和普通的行（[SwitchRow] 等），行间分隔线照常画，第一条裁掉。
 */
@Composable
internal fun CardChoiceRow(
    title: String,
    options: List<CardOption>,
    selected: Int,
    description: String? = null,
    note: String? = null,
    summary: String? = null,
    detail: (Int) -> (@Composable ColumnScope.() -> Unit)? = { null },
    onSelect: (Int) -> Unit,
) {
    val compact = LocalCompact.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .rowPadding(compact),
    ) {
        val across = maxWidth >= CARD_CHOICE_ACROSS_MIN
        val gap = 12.dp
        val cardWidth = (maxWidth - gap * (options.size - 1)) / options.size
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            RowTexts(title, summary, description, note, true, null, Modifier)
            Column {
                val cards: @Composable (Modifier) -> Unit = { cardModifier ->
                    options.forEachIndexed { index, option -> OptionCard(option, index == selected, cardModifier) { onSelect(index) } }
                }
                if (across) {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        cards(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cards(Modifier.fillMaxWidth())
                    }
                }
                // 尖角：并排时指着选中卡片的中间，竖排时靠左。
                val caretTarget = if (across) cardWidth * (selected + 0.5f) + gap * selected else CARET_HALF_WIDTH + 24.dp
                val caretX by animateDpAsState(caretTarget, label = "选项面板尖角")
                // 车主换了选项、新选项有面板时，等面板展开完把它整块滚进来；页面刚打开时的选中项不算。
                // 面板底下再多露一截（下一行的开头），一看就知道面板到底了。
                val panel = remember { BringIntoViewRequester() }
                var panelSize by remember { mutableStateOf(IntSize.Zero) }
                val margin = with(LocalDensity.current) { DETAIL_SCROLL_MARGIN.toPx() }
                var shownFor by remember { mutableIntStateOf(selected) }
                LaunchedEffect(selected) {
                    if (selected == shownFor) return@LaunchedEffect
                    shownFor = selected
                    if (detail(selected) == null) return@LaunchedEffect
                    delay(DETAIL_SETTLE_MS)
                    panel.bringIntoView(Rect(0f, 0f, panelSize.width.toFloat(), panelSize.height + margin))
                }
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier
                        .bringIntoViewRequester(panel)
                        .onSizeChanged { panelSize = it },
                    transitionSpec = {
                        fadeIn(tween(DETAIL_FADE_IN_MS, delayMillis = DETAIL_FADE_OUT_MS)) togetherWith
                            fadeOut(tween(DETAIL_FADE_OUT_MS)) using SizeTransform(clip = true)
                    },
                    label = "选项面板",
                ) { index ->
                    val content = detail(index)
                    if (content != null) OptionDetail(caretX, content) else Spacer(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private val CARD_CHOICE_ACROSS_MIN = 520.dp
private val CARET_HALF_WIDTH = 9.dp
private val CARET_HEIGHT = 8.dp
private const val DETAIL_FADE_IN_MS = 180
private const val DETAIL_FADE_OUT_MS = 90

/** 面板展开（淡入加高度变化）大致用的时间，过了再滚。 */
private const val DETAIL_SETTLE_MS = 420L

/** 滚动时面板下面多露出的高度。 */
private val DETAIL_SCROLL_MARGIN = 64.dp

/** 选中选项的设置面板：顶上的尖角加一块圆角面板，颜色和选中的卡片一样。 */
@Composable
private fun OptionDetail(caretX: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(CARET_HEIGHT)
                .drawBehind {
                    val x = caretX.toPx()
                    val half = CARET_HALF_WIDTH.toPx()
                    val caret = Path().apply {
                        moveTo(x - half, size.height)
                        lineTo(x, 0f)
                        lineTo(x + half, size.height)
                        close()
                    }
                    drawPath(caret, Palette.Tint)
                },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Tint)
                .hideFirstDivider(),
            content = content,
        )
    }
}

/** 选项面板里的第一行说明：[warn] 时琥珀色、前面一个提醒图标；否则是普通说明的颜色。 */
@Composable
internal fun DetailNote(text: String, warn: Boolean = false) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .rowPadding(compact),
        verticalAlignment = Alignment.Top,
    ) {
        if (warn) {
            Icon(
                Glyphs.Alert,
                contentDescription = null,
                tint = Palette.Warn,
                modifier = Modifier.padding(top = 1.dp, end = 8.dp).size(if (compact) 18.dp else 20.dp),
            )
        }
        Text(
            text,
            style = if (warn) UiType.rowNote(compact) else UiType.rowDesc(compact),
            color = if (warn) Palette.Warn else Palette.Text2,
        )
    }
}

@Composable
private fun OptionCard(option: CardOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) Palette.Tint else Color.Transparent)
            .border(2.dp, if (selected) Palette.On else Palette.SwitchOff, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                option.title,
                style = UiType.rowTitle(compact).copy(fontWeight = FontWeight.Medium),
                color = Palette.Text,
                modifier = Modifier.weight(1f, fill = false),
            )
            option.tag?.let { Tag(it) }
        }
        Text(option.text, style = UiType.rowSummary(compact), color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 灰底小标签（「试验」）。 */
@Composable
internal fun Tag(text: String) {
    Text(
        text,
        style = UiType.footer(false),
        color = Palette.Text2,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Button)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 组里的一行数值：[名称 / 一句话]  [当前值 ›]，整行可点（点了去改）。 */
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    description: String? = null,
    valueColor: Color = Palette.Text,
    monospace: Boolean = false,
    note: String? = null,
    summary: String? = null,
    onClick: () -> Unit,
) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .clickable(role = Role.Button, onClick = onClick)
            .rowPadding(compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(title, summary, description, note, true, null, Modifier.weight(1f))
        Text(
            value,
            style = if (monospace) UiType.value(compact) else UiType.option(compact),
            color = valueColor,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp),
        )
        Icon(Glyphs.Chevron, contentDescription = null, tint = Palette.Muted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/** 组里自由排版的一行（列表、输入框、几个按钮）：和别的行一样的内边距和分隔线。[spacing] 是里面各块之间的距离。 */
@Composable
internal fun GroupRow(modifier: Modifier = Modifier, spacing: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .groupDivider()
            .rowPadding(LocalCompact.current),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** 行里的小标题（名称那一级）。 */
@Composable
internal fun RowTitle(text: String, modifier: Modifier = Modifier, color: Color = Palette.Text) {
    Text(text, style = UiType.rowTitle(LocalCompact.current), color = color, modifier = modifier)
}

/** 行里的说明文字。 */
@Composable
internal fun RowDescription(text: String, modifier: Modifier = Modifier, color: Color = Palette.Muted) {
    Text(text, style = UiType.rowDesc(LocalCompact.current), color = color, modifier = modifier)
}

/** 行里的提示：默认琥珀色加粗（要用户注意的）；[warn] 为 false 时是普通说明的样子。可点时点它去处理。 */
@Composable
internal fun RowNote(text: String, warn: Boolean = true, onClick: (() -> Unit)? = null) {
    val compact = LocalCompact.current
    Text(
        text,
        style = if (warn) UiType.rowNote(compact) else UiType.rowDesc(compact),
        color = if (warn) Palette.Warn else Palette.Muted,
        modifier = Modifier
            .padding(top = 4.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    )
}

/**
 * 行里的名称和文字：名称下面一句话（[summary]）一直显示；更长的解释（说明、非警告的附注）收进名称后的「?」，点开才显示；
 * 警告和可点的附注照常显示，免得车主漏看要处理的事。
 */
@Composable
private fun RowTexts(
    title: String,
    summary: String?,
    description: String?,
    note: String?,
    noteWarn: Boolean,
    onNoteClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val noteInHelp = note != null && !noteWarn && onNoteClick == null
    val help = listOfNotNull(description, note.takeIf { noteInHelp }).joinToString("\n").ifEmpty { null }
    Column(modifier) {
        RowHeader(title, help, summary = summary)
        if (note != null && !noteInHelp) RowNote(note, noteWarn, onNoteClick)
    }
}

/**
 * 行的名称；有说明时名称后面跟一个「?」：说明平时收起，点「?」在下面展开一块、再点收起（每行各自记）。
 * 「?」自己可点，点它不会触发整行的开关或跳转。[summary] 是名称下面一直显示的一句话。
 */
@Composable
internal fun RowHeader(title: String, help: String?, modifier: Modifier = Modifier, summary: String? = null) {
    var shown by rememberSaveable(help) { mutableStateOf(false) }
    val compact = LocalCompact.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitle(title, Modifier.weight(1f, fill = false))
            if (help != null) HelpButton(shown) { shown = !shown }
        }
        if (summary != null) {
            Text(summary, style = UiType.rowSummary(compact), color = Palette.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (help != null && shown) {
            RowDescription(
                help,
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.SegmentBackground)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = Palette.Text2,
            )
        }
    }
}

/** 「?」按钮：圆圈里一个问号，展开时填蓝。可点区域比圆圈大一圈，车上好点。 */
@Composable
private fun HelpButton(shown: Boolean, onClick: () -> Unit) {
    val compact = LocalCompact.current
    val label = stringResource(R.string.row_help)
    val state = stringResource(if (shown) R.string.row_help_shown else R.string.row_help_hidden)
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(if (compact) 32.dp else 36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (compact) 20.dp else 22.dp)
                .clip(CircleShape)
                .background(if (shown) Palette.On else Palette.Tint)
                .border(1.dp, if (shown) Palette.On else Palette.Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = UiType.rowDesc(compact).copy(fontWeight = FontWeight.Bold, lineHeight = UiType.rowDesc(compact).fontSize),
                color = if (shown) Palette.OnAccent else Palette.Accent,
                maxLines = 1,
            )
        }
    }
}

/** 滑动开关：开 = 蓝色轨道、圆点在右；关 = 灰色轨道、圆点在左。 */
@Composable
private fun PillSwitch(checked: Boolean) {
    val compact = LocalCompact.current
    val knob = if (compact) 22.dp else 26.dp
    val trackWidth = if (compact) 46.dp else 54.dp
    val inset = 3.dp
    val x by animateDpAsState(if (checked) trackWidth - knob - inset else inset, label = "开关圆点")
    Box(
        Modifier
            .size(trackWidth, knob + inset * 2)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (checked) Palette.On else Palette.SwitchOff),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 分段选择：选中的那段蓝底白字，其余是底槽上的普通字。[fill] 为真时各段等宽铺满，否则按字宽。 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier, fill: Boolean) {
    val compact = LocalCompact.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.SegmentBackground)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                (if (fill) Modifier.weight(1f) else Modifier.widthIn(min = if (compact) 60.dp else 72.dp))
                    .heightIn(min = if (compact) 36.dp else 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Palette.On else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = UiType.option(compact).copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal),
                    color = if (on) Palette.OnAccent else Palette.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Modifier.rowPadding(compact: Boolean) =
    padding(horizontal = 20.dp, vertical = if (compact) 10.dp else 14.dp)

/** 在这一行顶上画分隔线（左侧缩进、1 像素）。 */
internal fun Modifier.groupDivider(): Modifier = drawBehind {
    val inset = DIVIDER_INSET.toPx()
    drawRect(Palette.Divider, topLeft = Offset(inset, 0f), size = Size((size.width - inset).coerceAtLeast(0f), 1f))
}

/** 分组框把内容往上挪 1 像素、高度少报 1 像素，加上圆角裁剪，第一行顶上那条分隔线就看不见了。 */
private fun Modifier.hideFirstDivider(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - 1).coerceAtLeast(0)) { placeable.place(0, -1) }
}

private val DIVIDER_INSET = 20.dp
