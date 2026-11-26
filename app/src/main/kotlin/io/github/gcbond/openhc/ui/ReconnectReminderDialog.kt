package io.github.gcbond.openhc.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.PendingSetting
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wifi.CarHotspotAutoPreferences
import io.github.gcbond.openhc.wifi.ScanPausePreferences

/** 读出「下次连接才生效」的各项设置现在的值，进出设置页各读一次，对比看改了什么（见 [ReconnectReminder]）。 */
internal fun pendingSettingsSnapshot(context: Context, settings: AppSettings): Map<PendingSetting, String> {
    val audio = AudioPreferences(context)
    val wireless = WirelessPreferences(context)
    return PendingSetting.values().associateWith { key ->
        when (key) {
            PendingSetting.FPS -> settings.fps.toString()
            PendingSetting.UI_SCALE -> settings.uiScalePercent.toString()
            PendingSetting.CLARITY -> ClarityPreferences(context).clarity.key
            PendingSetting.FULL_SCREEN -> settings.fullScreen.toString()
            PendingSetting.MEDIA_KEYS -> MediaKeyPreferences(context).enabled.toString()
            PendingSetting.BLUETOOTH_ADDRESS -> settings.manualBluetoothAddress.orEmpty()
            PendingSetting.AUDIO_ENABLED -> audio.enabled.toString()
            PendingSetting.AUDIO_DELAY -> audio.delayMillis.toString()
            PendingSetting.NAVIGATION_STREAM -> audio.navigationStream.toString()
            PendingSetting.MEDIA_STREAM -> audio.mediaStream.toString()
            PendingSetting.WIRELESS_MODE -> "${wireless.wifiDirect}/${wireless.carHotspot}"
            PendingSetting.CAR_HOTSPOT_NAME -> wireless.carHotspotName
            PendingSetting.CAR_HOTSPOT_PASSWORD -> wireless.carHotspotPassword
            PendingSetting.CAR_HOTSPOT_BAND -> wireless.carHotspotFiveGhz.toString()
            PendingSetting.CAR_HOTSPOT_AUTO -> CarHotspotAutoPreferences(context).enabled.toString()
            PendingSetting.SCAN_PAUSE -> ScanPausePreferences(context).enabled.toString()
            PendingSetting.HOTSPOT_5G -> wireless.hotspot5g.toString()
        }
    }
}

/** 设置页上这一项的名字。 */
private fun PendingSetting.label(): Int = when (this) {
    PendingSetting.FPS -> R.string.settings_fps
    PendingSetting.UI_SCALE -> R.string.settings_ui_scale
    PendingSetting.CLARITY -> R.string.settings_clarity
    PendingSetting.FULL_SCREEN -> R.string.settings_full_screen
    PendingSetting.MEDIA_KEYS -> R.string.settings_media_keys
    PendingSetting.BLUETOOTH_ADDRESS -> R.string.settings_bt
    PendingSetting.AUDIO_ENABLED -> R.string.settings_audio_enabled
    PendingSetting.AUDIO_DELAY -> R.string.settings_audio_delay
    PendingSetting.NAVIGATION_STREAM -> R.string.settings_audio_navigation_stream
    PendingSetting.MEDIA_STREAM -> R.string.settings_audio_media_stream
    PendingSetting.WIRELESS_MODE -> R.string.settings_wireless_mode
    PendingSetting.CAR_HOTSPOT_NAME -> R.string.settings_car_hotspot_name
    PendingSetting.CAR_HOTSPOT_PASSWORD -> R.string.settings_car_hotspot_password
    PendingSetting.CAR_HOTSPOT_BAND -> R.string.settings_car_hotspot_band
    PendingSetting.CAR_HOTSPOT_AUTO -> R.string.settings_car_hotspot_auto
    PendingSetting.SCAN_PAUSE -> R.string.settings_scan_pause
    PendingSetting.HOTSPOT_5G -> R.string.settings_wireless_hotspot_5g
}

/** 从设置页回到首页时：改了的这几项，正在进行的连接用不上，要重新连接。[projecting] 为真时说「断开后重连」，否则说「停止再开始」。 */
@Composable
internal fun ReconnectReminderDialog(changed: List<PendingSetting>, projecting: Boolean, onDismiss: () -> Unit) {
    val names = changed.map { stringResource(it.label()) }.joinToString("、")
    Dialog(onDismissRequest = onDismiss) {
        val compact = LocalCompact.current
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Raised)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.reconnect_reminder_title), style = UiType.status, color = Palette.Text)
            Text(
                stringResource(if (projecting) R.string.reconnect_reminder_projecting else R.string.reconnect_reminder_waiting, names),
                style = UiType.rowTitle(compact).copy(lineHeight = 1.5.em, fontWeight = FontWeight.Normal),
                color = Palette.Text,
            )
            ActionButton(
                stringResource(R.string.reconnect_reminder_ok),
                onClick = onDismiss,
                kind = ActionKind.PRIMARY,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
