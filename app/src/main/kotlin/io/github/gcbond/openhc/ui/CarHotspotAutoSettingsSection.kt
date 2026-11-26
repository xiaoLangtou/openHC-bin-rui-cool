package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wifi.AdbSwitchResult
import io.github.gcbond.openhc.wifi.CarHotspotAuto
import io.github.gcbond.openhc.wifi.CarHotspotAutoPreferences

/**
 * 自动打开车机热点（默认关，见 [CarHotspotAuto]），无线方式选「车机自带热点」时显示。打开前先弹框写清用途和风险，车主点
 * 「打开」才打开并检查一遍（车机弹授权框）。关掉从下一次连接起生效：正在用的热点不马上关，这次连接结束时照样关。
 * 提示显示正在做什么或上一次的结果：检查中和失败时直接显示，其他放进「?」里。
 */
@Composable
internal fun CarHotspotAutoSetting() {
    val context = LocalContext.current
    val app = context.applicationContext as? OpenHcApp ?: return
    val prefs = remember(context) { CarHotspotAutoPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var confirming by remember { mutableStateOf(false) }
    val status by app.carHotspotAuto.status.collectAsState()
    val note = when (status.phase) {
        CarHotspotAuto.Phase.CHECKING -> R.string.settings_adb_checking
        CarHotspotAuto.Phase.OPENING -> R.string.settings_car_hotspot_auto_opening
        CarHotspotAuto.Phase.OPEN -> R.string.settings_car_hotspot_auto_open
        CarHotspotAuto.Phase.CLOSING -> R.string.settings_car_hotspot_auto_closing
        CarHotspotAuto.Phase.IDLE -> if (!enabled) null else when (status.last) {
            null -> null
            AdbSwitchResult.DONE, AdbSwitchResult.READY, AdbSwitchResult.ALREADY_ON -> R.string.settings_car_hotspot_auto_ready
            AdbSwitchResult.ADB_OFF -> R.string.settings_adb_adb_off
            AdbSwitchResult.NOT_APPROVED -> R.string.settings_adb_not_approved
            AdbSwitchResult.TLS_ONLY -> R.string.settings_adb_tls_only
            AdbSwitchResult.UNSUPPORTED -> R.string.settings_car_hotspot_auto_unsupported
            AdbSwitchResult.DENIED -> R.string.settings_car_hotspot_auto_denied
            AdbSwitchResult.FAILED -> R.string.settings_adb_failed
        }
    }
    val ok = status.last == AdbSwitchResult.DONE || status.last == AdbSwitchResult.READY ||
        status.last == AdbSwitchResult.ALREADY_ON
    val warn = status.phase == CarHotspotAuto.Phase.CHECKING || status.phase == CarHotspotAuto.Phase.IDLE && !ok
    SwitchRow(
        title = stringResource(R.string.settings_car_hotspot_auto),
        summary = stringResource(R.string.settings_car_hotspot_auto_summary),
        checked = enabled,
        description = stringResource(R.string.settings_car_hotspot_auto_hint),
        note = note?.let { stringResource(it) },
        noteWarn = warn,
    ) {
        if (it) {
            confirming = true
        } else {
            enabled = false
            prefs.enabled = false
        }
    }
    if (confirming) {
        AdbConfirmDialog(
            title = stringResource(R.string.settings_car_hotspot_auto),
            text = stringResource(R.string.settings_car_hotspot_auto_confirm_text),
            onCancel = { confirming = false },
            onConfirm = {
                confirming = false
                enabled = true
                prefs.enabled = true
                app.carHotspotAuto.check()
            },
        )
    }
}
