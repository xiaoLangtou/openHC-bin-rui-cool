package io.github.gcbond.openhc.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.diagnostics.DiagnosticsExport
import io.github.gcbond.openhc.settings.AppSettings

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
            RowHeader(
                stringResource(R.string.settings_diag_export),
                stringResource(R.string.settings_diag_export_hint),
                summary = stringResource(R.string.settings_diag_export_summary),
            )
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
    DiagnosticsUploadSection()
}

/**
 * 「诊断上报」一组：把和导出一样的全文 POST 给日志服务器（[DiagnosticsExport.upload]），
 * 服务器地址和令牌在下面填（输入即保存）。上传走网络、要读几 MB 文件，也在后台线程上做。
 */
@Composable
private fun DiagnosticsUploadSection() {
    val context = LocalContext.current
    val app = context.applicationContext
    val settings = remember { AppSettings(app) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var url by remember { mutableStateOf(settings.logServerUrl) }
    var token by remember { mutableStateOf(settings.logServerToken) }
    SettingsGroup(stringResource(R.string.settings_group_diag_upload)) {
        GroupRow(spacing = 8.dp) {
            RowHeader(stringResource(R.string.settings_diag_upload), stringResource(R.string.settings_diag_upload_hint))
            ActionButton(
                text = stringResource(if (busy) R.string.settings_diag_uploading else R.string.settings_diag_upload_button),
                enabled = !busy,
                onClick = {
                    busy = true
                    note = null
                    val main = Handler(Looper.getMainLooper())
                    Thread({
                        val result = DiagnosticsExport.upload(context)
                        main.post {
                            busy = false
                            note = when (result) {
                                is DiagnosticsExport.Result.Saved -> result.location to false
                                is DiagnosticsExport.Result.Failed ->
                                    app.getString(R.string.settings_diag_upload_failed, result.reason) to true
                            }
                        }
                    }, "OpenHC-DiagUpload").start()
                },
            )
            note?.let { (text, warn) -> RowNote(text, warn) }
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    settings.logServerUrl = it
                },
                label = { Text(stringResource(R.string.settings_diag_upload_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    settings.logServerToken = it
                },
                label = { Text(stringResource(R.string.settings_diag_upload_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
    }
}
