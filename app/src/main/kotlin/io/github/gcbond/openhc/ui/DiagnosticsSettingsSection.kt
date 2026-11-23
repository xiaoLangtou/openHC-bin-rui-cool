package io.github.gcbond.openhc.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.diagnostics.DiagnosticsExport

/**
 * 设置页「诊断」一组：导出诊断日志（[DiagnosticsExport]）。导出要读写几 MB 文件，放在后台线程上做，
 * 完成后在行下面显示存到了哪里（或者为什么没存成）。
 */
@Composable
internal fun DiagnosticsSettingsSection() {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    SettingsGroup(stringResource(R.string.settings_group_diagnostics)) {
        GroupRow(spacing = 8.dp) {
            RowHeader(stringResource(R.string.settings_diag_export), stringResource(R.string.settings_diag_export_hint))
            ActionButton(
                text = stringResource(if (busy) R.string.settings_diag_exporting else R.string.settings_diag_export_button),
                enabled = !busy,
                onClick = {
                    busy = true
                    note = null
                    val app = context.applicationContext
                    val main = Handler(Looper.getMainLooper())
                    Thread({
                        val result = DiagnosticsExport.export(app)
                        main.post {
                            busy = false
                            note = when (result) {
                                is DiagnosticsExport.Result.Saved -> app.getString(R.string.settings_diag_saved, result.location) to false
                                is DiagnosticsExport.Result.Failed -> app.getString(R.string.settings_diag_failed, result.reason) to true
                            }
                        }
                    }, "OpenHC-DiagExport").start()
                },
            )
            note?.let { (text, warn) -> RowNote(text, warn) }
        }
    }
}
