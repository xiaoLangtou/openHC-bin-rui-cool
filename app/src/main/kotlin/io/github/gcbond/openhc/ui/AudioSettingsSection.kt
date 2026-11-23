package io.github.gcbond.openhc.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.media.AudioPreview
import io.github.gcbond.openhc.platform.media.AudioStreamChoice
import io.github.gcbond.openhc.platform.media.AudioUsage
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.PcmPhoneRecord
import java.text.DateFormat
import java.util.Date

/**
 * 设置页「声音（实验）」一组：总开关、声音延后、导航声道和音乐声道（带试听）、已自动改用 AAC 的手机（可以恢复）。
 * 改动在下次连接时生效。
 */
@Composable
internal fun AudioSettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { AudioPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var delay by remember { mutableIntStateOf(prefs.delayMillis) }
    var navigationStream by remember { mutableIntStateOf(prefs.navigationStream) }
    var mediaStream by remember { mutableIntStateOf(prefs.mediaStream) }
    var fallbacks by remember { mutableStateOf(prefs.pcmFallbacks().records.filter { it.useAac }) }

    SettingsGroup(stringResource(R.string.settings_audio)) {
        SwitchRow(
            title = stringResource(R.string.settings_audio_enabled),
            checked = enabled,
            description = stringResource(R.string.settings_audio_hint),
        ) {
            enabled = it
            prefs.enabled = it
        }
        val delays = AudioPreferences.DELAY_CHOICES
        ChoiceRow(
            title = stringResource(R.string.settings_audio_delay),
            options = delays.map {
                if (it == 0) stringResource(R.string.settings_audio_delay_min) else stringResource(R.string.settings_audio_delay_plus, it)
            },
            selected = delays.indexOf(delay),
            description = stringResource(R.string.settings_audio_delay_hint),
        ) { index ->
            delay = delays[index]
            prefs.delayMillis = delays[index]
        }
        StreamRow(
            title = stringResource(R.string.settings_audio_navigation_stream),
            description = stringResource(R.string.settings_audio_navigation_stream_hint),
            usage = AudioUsage.NAVIGATION,
            selected = navigationStream,
            lowLatency = prefs.lowLatency,
        ) {
            navigationStream = it
            prefs.navigationStream = it
        }
        StreamRow(
            title = stringResource(R.string.settings_audio_media_stream),
            description = stringResource(R.string.settings_audio_media_stream_hint),
            usage = AudioUsage.MEDIA,
            selected = mediaStream,
            lowLatency = prefs.lowLatency,
        ) {
            mediaStream = it
            prefs.mediaStream = it
        }
        GroupRow {
            RowHeader(stringResource(R.string.settings_audio_fallback_title), stringResource(R.string.settings_audio_fallback_help))
            if (fallbacks.isEmpty()) {
                RowDescription(stringResource(R.string.settings_audio_fallback_none), Modifier.padding(top = 2.dp))
            } else {
                for (record in fallbacks) {
                    FallbackRow(record) {
                        prefs.forgetPcmFallback(record.key)
                        fallbacks = prefs.pcmFallbacks().records.filter { it.useAac }
                    }
                }
            }
        }
    }
}

/** 一台改用 AAC 的手机：名字、编号前 6 位、原因、记下的日期，右边「恢复 PCM」。 */
@Composable
private fun FallbackRow(record: PcmPhoneRecord, onReset: () -> Unit) {
    val name = record.name ?: stringResource(R.string.settings_audio_fallback_unnamed)
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAtMillis))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowDescription(
            stringResource(R.string.settings_audio_fallback_item, name, record.key.take(6), record.lastFailure ?: "", date),
            Modifier.weight(1f),
            color = Palette.Text,
        )
        Spacer(Modifier.width(12.dp))
        ActionButton(stringResource(R.string.settings_audio_fallback_reset), onClick = onReset)
    }
}

/** 上次试听的结果；[warn] 为 true 时（被拒、放不出来）用提醒色。 */
private class PreviewNote(val text: String, val warn: Boolean)

/**
 * 一路声音放到哪个声道：名称 / 说明 / 上次试听的结果，右边「试听」；下面一排能左右滑的「自动 1 2 … 20」。
 * 试听和正式播放用同一个建音轨函数（[AudioPreview]），听到的就是连上以后走的路；换了声道就清掉上次的结果。
 */
@Composable
private fun StreamRow(
    title: String,
    description: String,
    usage: AudioUsage,
    selected: Int,
    lowLatency: Boolean,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var previewing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<PreviewNote?>(null) }
    GroupRow(spacing = if (LocalCompact.current) 8.dp else 10.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowHeader(title, description)
                note?.let { RowNote(it.text, warn = it.warn) }
            }
            Spacer(Modifier.width(12.dp))
            ActionButton(
                stringResource(if (previewing) R.string.settings_audio_preview_playing else R.string.settings_audio_preview),
                enabled = !previewing,
                onClick = {
                    previewing = true
                    note = null
                    AudioPreview.play(usage, selected, lowLatency) { result ->
                        Log.i(
                            PREVIEW_TAG,
                            "试听${usage.label}：声道 ${AudioStreamChoice.label(result.stream)}，" +
                                result.route.ifEmpty { "按用途建" } + (result.error?.let { "；放不出来：$it" } ?: ""),
                        )
                        main.post {
                            previewing = false
                            note = previewNote(context, result)
                        }
                    }
                },
            )
        }
        StreamChips(selected) {
            note = null
            onSelect(it)
        }
    }
}

private fun previewNote(context: Context, result: AudioPreview.Result): PreviewNote = when {
    result.error != null -> PreviewNote(context.getString(R.string.settings_audio_preview_failed, result.error), warn = true)
    result.streamRejected -> PreviewNote(context.getString(R.string.settings_audio_preview_rejected, result.stream), warn = true)
    result.stream == AudioStreamChoice.AUTO -> PreviewNote(context.getString(R.string.settings_audio_preview_auto), warn = false)
    else -> PreviewNote(context.getString(R.string.settings_audio_preview_stream, result.stream), warn = false)
}

/** 一排能左右滑的声道：「自动」和 1～20 号，选中的蓝底加粗。打开时滚到选中的那个附近。 */
@Composable
private fun StreamChips(selected: Int, onSelect: (Int) -> Unit) {
    val compact = LocalCompact.current
    val choices = remember { listOf(AudioStreamChoice.AUTO) + (AudioStreamChoice.MIN..AudioStreamChoice.MAX) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (choices.indexOf(selected) - 2).coerceAtLeast(0))
    val auto = stringResource(R.string.settings_audio_stream_auto)
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.SegmentBackground)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(choices) { value ->
            val on = value == selected
            Box(
                Modifier
                    .width(CHIP_WIDTH)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) Palette.On else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                    .padding(vertical = if (compact) 5.dp else 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (value == AudioStreamChoice.AUTO) auto else value.toString(),
                    style = UiType.option(compact).copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                    color = if (on) Palette.Text else Palette.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 试听结果写进声音那一路的日志（和播放同一个标签）。 */
private const val PREVIEW_TAG = "OpenHC-Audio"

/** 每个声道格子的宽度：「自动」两个字也放得下。 */
private val CHIP_WIDTH = 52.dp
