package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wifi.ScanPause
import io.github.gcbond.openhc.wifi.ScanPausePreferences
import io.github.gcbond.openhc.wifi.AdbSwitchResult

/**
 * 直连时暂停车机 WiFi 扫描（默认关，见 [ScanPause]）。打开前先弹框写清用途和风险，车主点「打开」才打开并检查一遍
 * （车机弹授权框）；关掉时马上恢复。提示显示正在做什么或上一次的结果：检查中和失败时直接显示，其他放进「?」里。
 */
@Composable
internal fun ScanPauseSetting() {
    val context = LocalContext.current
    val app = context.applicationContext as? OpenHcApp ?: return
    val prefs = remember(context) { ScanPausePreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var confirming by remember { mutableStateOf(false) }
    val status by app.scanPause.status.collectAsState()
    val note = when (status.phase) {
        ScanPause.Phase.CHECKING -> R.string.settings_adb_checking
        ScanPause.Phase.PAUSING, ScanPause.Phase.PAUSED -> R.string.settings_scan_pause_paused
        ScanPause.Phase.RESTORING -> R.string.settings_scan_pause_restoring
        ScanPause.Phase.IDLE -> if (!enabled) null else when (status.last) {
            null -> null
            AdbSwitchResult.DONE, AdbSwitchResult.READY, AdbSwitchResult.ALREADY_ON -> R.string.settings_scan_pause_ready
            AdbSwitchResult.ADB_OFF -> R.string.settings_adb_adb_off
            AdbSwitchResult.NOT_APPROVED -> R.string.settings_adb_not_approved
            AdbSwitchResult.TLS_ONLY -> R.string.settings_adb_tls_only
            AdbSwitchResult.UNSUPPORTED -> R.string.settings_scan_pause_unsupported
            AdbSwitchResult.DENIED -> R.string.settings_scan_pause_denied
            AdbSwitchResult.FAILED -> R.string.settings_adb_failed
        }
    }
    // 「请在车机上点允许」和失败原因要一眼看到；暂停中、检查通过放进「?」里。
    val warn = status.phase == ScanPause.Phase.CHECKING ||
        status.phase == ScanPause.Phase.IDLE && status.last != AdbSwitchResult.DONE && status.last != AdbSwitchResult.READY
    SwitchRow(
        title = stringResource(R.string.settings_scan_pause),
        summary = stringResource(R.string.settings_scan_pause_summary),
        checked = enabled,
        description = stringResource(R.string.settings_scan_pause_hint),
        note = note?.let { stringResource(it) },
        noteWarn = warn,
    ) {
        if (it) {
            confirming = true
        } else {
            enabled = false
            prefs.enabled = false
            app.scanPause.settingChanged(false)
        }
    }
    if (confirming) {
        AdbConfirmDialog(
            title = stringResource(R.string.settings_scan_pause),
            text = stringResource(R.string.settings_scan_pause_confirm_text),
            onCancel = { confirming = false },
            onConfirm = {
                confirming = false
                enabled = true
                prefs.enabled = true
                app.scanPause.check()
            },
        )
    }
}

/** 用到车机网络 ADB 的功能打开前的确认：用途、要车主自己做的事、风险。点框外不关，免得误触。 */
@Composable
internal fun AdbConfirmDialog(title: String, text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false)) {
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
            Text(title, style = UiType.status, color = Palette.Text)
            Text(
                text,
                style = UiType.rowTitle(compact).copy(lineHeight = 1.5.em, fontWeight = FontWeight.Normal),
                color = Palette.Text,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(stringResource(R.string.settings_adb_cancel), onClick = onCancel)
                ActionButton(stringResource(R.string.settings_adb_confirm), onClick = onConfirm, kind = ActionKind.PRIMARY)
            }
        }
    }
}
