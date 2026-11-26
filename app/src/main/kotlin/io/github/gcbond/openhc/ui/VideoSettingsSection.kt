package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.settings.AppSettings

/**
 * 设置页「画面」一组：帧率、界面大小、清晰度、全屏显示。分辨率不给选，一律按屏幕实际尺寸。改动在下次连接时生效。
 * 新的画面设置做成一行（[ChoiceRow] / [SwitchRow]）放进这一组。
 */
@Composable
internal fun PictureSettingsSection(settings: AppSettings, screen: ScreenSpec) {
    SettingsGroup(stringResource(R.string.settings_group_picture)) {
        FpsSetting(settings, screen)
        UiScaleSetting(settings)
        ClaritySettingsSection()
        FullScreenSetting(settings)
    }
}

/** 帧率：自动（按屏幕刷新率）或固定几档。 */
@Composable
internal fun FpsSetting(settings: AppSettings, screen: ScreenSpec) {
    var fps by remember { mutableIntStateOf(settings.fps) }
    val values = listOf(0) + DisplayPlan.FPS_CHOICES
    ChoiceRow(
        title = stringResource(R.string.settings_fps),
        summary = stringResource(R.string.settings_fps_summary),
        options = values.map {
            if (it == 0) stringResource(R.string.settings_choice_auto) else stringResource(R.string.settings_fps_fixed, it)
        },
        selected = values.indexOf(fps),
        description = stringResource(R.string.settings_fps_auto, screen.autoFps) + "。" +
            stringResource(R.string.settings_fps_hint),
    ) { index ->
        fps = values[index]
        settings.fps = values[index]
    }
}

/** 界面大小：声明给手机的 DPI 按百分比缩小，大屏车机上手机界面显得太大时用。下次连接生效。 */
@Composable
internal fun UiScaleSetting(settings: AppSettings) {
    var percent by remember { mutableIntStateOf(settings.uiScalePercent) }
    val values = DisplayPlan.UI_SCALE_CHOICES
    ChoiceRow(
        title = stringResource(R.string.settings_ui_scale),
        summary = stringResource(R.string.settings_ui_scale_summary),
        options = values.map {
            if (it == DisplayPlan.DEFAULT_UI_SCALE) stringResource(R.string.settings_ui_scale_default) else "$it%"
        },
        selected = values.indexOf(percent),
        description = stringResource(R.string.settings_ui_scale_hint),
    ) { index ->
        percent = values[index]
        settings.uiScalePercent = values[index]
    }
}

/** 「全屏显示」开关：关掉后保留底部导航栏，画面让出它的位置。改动在下次连接时生效。 */
@Composable
internal fun FullScreenSetting(settings: AppSettings) {
    var on by remember { mutableStateOf(settings.fullScreen) }
    SwitchRow(
        title = stringResource(R.string.settings_full_screen),
        summary = stringResource(R.string.settings_full_screen_summary),
        checked = on,
        description = stringResource(R.string.settings_full_screen_hint),
    ) {
        on = it
        settings.fullScreen = it
    }
}
