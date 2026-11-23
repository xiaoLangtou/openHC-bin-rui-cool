package io.github.gcbond.openhc.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gcbond.openhc.R

// 首页和设置页共用的控件。全部自己画（不用系统开关、单选框），车机主题下样子才可控。

// ------------------------------------------------------------------ 页面骨架

/** 页面大标题（首页的应用名、设置页的「设置」）：单行，矮屏收一档。 */
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

/** 右上角的「设置」「返回」：白字灰底圆角。两页的这个按钮在同一个位置。 */
@Composable
internal fun TopBarButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = UiType.topButton,
        color = Palette.Text,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** 圆角卡片：首页的无线、有线两栏。 */
@Composable
internal fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = Palette.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(color)
            .padding(Dimens.CardPadding),
        content = content,
    )
}

// ------------------------------------------------------------------ 按钮

/** 胶囊按钮：连接相关的主要操作（开始、回到投屏画面）用蓝色渐变，次要的（停止）用灰蓝。 */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val brush = if (primary) {
        Brush.horizontalGradient(listOf(Palette.PrimaryStart, Palette.PrimaryEnd))
    } else {
        SolidColor(Palette.Secondary)
    }
    Box(
        modifier
            .widthIn(min = if (LocalCompact.current) 150.dp else 200.dp)
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(percent = 50))
            .background(brush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = UiType.pill, color = Color.White, textAlign = TextAlign.Center)
    }
}

internal enum class ActionKind { NORMAL, PRIMARY, DANGER }

/** 设置页行内的小按钮（保存、清除、粘贴等）：圆角方块。 */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.NORMAL,
    enabled: Boolean = true,
) {
    val background = when (kind) {
        ActionKind.NORMAL -> Palette.Button
        ActionKind.PRIMARY -> Palette.On
        ActionKind.DANGER -> Palette.Warn
    }
    Box(
        modifier
            .heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = UiType.action(LocalCompact.current),
            color = if (kind == ActionKind.DANGER) Palette.Background else Color.White,
        )
    }
}

private const val DISABLED_ALPHA = 0.4f

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

/**
 * 设置页的一个分组：组名只写一次，组里各行放在同一个圆角框里，行与行之间一条左侧缩进的细线。
 *
 * 行要用下面的 [SwitchRow]、[ChoiceRow]、[ValueRow]、[GroupRow]：每一行是一个根节点，自己在顶上画分隔线，
 * 分组把第一行顶上那条裁掉。不带分隔线的内容（比如直接放一个 Text）也能放，只是和上一行之间没有线。
 */
@Composable
internal fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compact = LocalCompact.current
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = UiType.groupTitle(compact),
            color = Palette.Accent,
            modifier = Modifier.padding(start = 4.dp, bottom = if (compact) 6.dp else 8.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Card)
                .hideFirstDivider(),
            content = content,
        )
    }
}

/** 组里的一行开关：[名称 / 说明 / 提示]  [滑动开关]，整行可点。 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    note: String? = null,
    noteWarn: Boolean = true,
    onNoteClick: (() -> Unit)? = null,
    enabled: Boolean = true,
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
        RowTexts(title, description, note, noteWarn, onNoteClick, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        PillSwitch(checked)
    }
}

/** 组里的一行选项：名称 / 说明 / 提示，下面一条等宽铺满的分段选择。[selected] 为 -1 时都不高亮。 */
@Composable
internal fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: Int,
    description: String? = null,
    note: String? = null,
    onSelect: (Int) -> Unit,
) {
    val compact = LocalCompact.current
    Column(
        Modifier
            .fillMaxWidth()
            .groupDivider()
            .rowPadding(compact),
    ) {
        RowTexts(title, description, note, true, null, Modifier)
        Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
        Segmented(options, selected, onSelect)
    }
}

/** 组里的一行数值：[名称 / 说明]  [当前值 ›]，整行可点（点了去改）。 */
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    description: String? = null,
    valueColor: Color = Palette.Text,
    monospace: Boolean = false,
    note: String? = null,
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
        RowTexts(title, description, note, true, null, Modifier.weight(1f))
        Text(
            value,
            style = if (monospace) UiType.value(compact) else UiType.option(compact),
            color = valueColor,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp),
        )
        Text("›", style = UiType.chevron(compact), color = Palette.Muted, modifier = Modifier.padding(start = 8.dp))
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
            .padding(top = 3.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    )
}

/**
 * 行里的名称和文字：解释性的文字（说明、非警告的附注）收进标题后的「?」，点开才显示；
 * 警告和可点的附注照常显示，免得车主漏看要处理的事。
 */
@Composable
private fun RowTexts(
    title: String,
    description: String?,
    note: String?,
    noteWarn: Boolean,
    onNoteClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val noteInHelp = note != null && !noteWarn && onNoteClick == null
    val help = listOfNotNull(description, note.takeIf { noteInHelp }).joinToString("\n").ifEmpty { null }
    Column(modifier) {
        RowHeader(title, help)
        if (note != null && !noteInHelp) RowNote(note, noteWarn, onNoteClick)
    }
}

/**
 * 行的名称；有说明时名称后面跟一个「?」：说明平时收起，点「?」展开、再点收起（每行各自记）。
 * 「?」自己可点，点它不会触发整行的开关或跳转。
 */
@Composable
internal fun RowHeader(title: String, help: String?, modifier: Modifier = Modifier) {
    var shown by rememberSaveable(help) { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitle(title, Modifier.weight(1f, fill = false))
            if (help != null) HelpButton(shown) { shown = !shown }
        }
        if (help != null && shown) RowDescription(help, Modifier.padding(top = 2.dp))
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
            .size(if (compact) 28.dp else 32.dp)
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
                .size(if (compact) 18.dp else 20.dp)
                .clip(CircleShape)
                .background(if (shown) Palette.On else Color.Transparent)
                .border(1.dp, if (shown) Palette.On else Palette.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?",
                style = UiType.rowDesc(compact).copy(fontWeight = FontWeight.Bold, lineHeight = UiType.rowDesc(compact).fontSize),
                color = if (shown) Color.White else Palette.Muted,
                maxLines = 1,
            )
        }
    }
}

/** 滑动开关：开 = 蓝色轨道、圆点在右；关 = 灰色轨道、圆点在左。 */
@Composable
private fun PillSwitch(checked: Boolean) {
    val compact = LocalCompact.current
    val knob = if (compact) 20.dp else 24.dp
    val trackWidth = if (compact) 42.dp else 50.dp
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

/** 等宽分段选择：选中的那段蓝底白字加粗，其余灰字。 */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.SegmentBackground)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Palette.On else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 4.dp, vertical = if (compact) 5.dp else 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = UiType.option(compact).copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) Palette.Text else Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Modifier.rowPadding(compact: Boolean) =
    padding(horizontal = 16.dp, vertical = if (compact) 9.dp else 12.dp)

/** 在这一行顶上画分隔线（左侧缩进 16dp、1 像素）。 */
internal fun Modifier.groupDivider(): Modifier = drawBehind {
    val inset = DIVIDER_INSET.toPx()
    drawRect(Palette.Divider, topLeft = Offset(inset, 0f), size = Size((size.width - inset).coerceAtLeast(0f), 1f))
}

/** 分组框把内容往上挪 1 像素、高度少报 1 像素，加上圆角裁剪，第一行顶上那条分隔线就看不见了。 */
private fun Modifier.hideFirstDivider(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - 1).coerceAtLeast(0)) { placeable.place(0, -1) }
}

private val DIVIDER_INSET = 16.dp
