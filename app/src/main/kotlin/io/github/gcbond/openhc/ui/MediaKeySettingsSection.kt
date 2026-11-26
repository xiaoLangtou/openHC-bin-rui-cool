package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.MediaKeyPreferences

/** 设置页「方向盘按键」一组：方向盘、媒体键切歌开关（默认开）。改动在下次连接时生效。 */
@Composable
internal fun MediaKeySettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { MediaKeyPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    SettingsGroup(stringResource(R.string.settings_media_keys)) {
        SwitchRow(
            title = stringResource(R.string.settings_media_keys_enabled),
            summary = stringResource(R.string.settings_media_keys_summary),
            checked = enabled,
            description = stringResource(R.string.settings_media_keys_hint),
        ) {
            enabled = it
            prefs.enabled = it
        }
    }
}
