package io.github.gcbond.openhc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.BluetoothAddressBackup
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.settings.AppSettings

/**
 * 「连接方式」组里的车机蓝牙地址：一行「车机蓝牙地址 [当前地址 ›]」，说明里写来源；点它在下面展开手动填写（保存、清除）。
 * 没取到地址时一进来就是展开的。
 *
 * 手填地址会自动备份进系统设置表（[BluetoothAddressBackup]）：清除数据丢了手填值之后，
 * 这里有「从备份恢复」按钮，要不要恢复车主自己选。
 */
@Composable
internal fun BluetoothAddressSection(settings: AppSettings, systemBluetoothAddress: String?) {
    var savedManual by remember { mutableStateOf(settings.manualBluetoothAddress) }
    var manualText by remember { mutableStateOf(savedManual ?: "") }
    // 本机蓝牙适配器 / 系统设置里的地址：手机上跑时它就是「车机」的蓝牙芯片本身，读到了就不用手填。
    val context = LocalContext.current
    val localAdapterAddress = remember(context) { DeviceInfo.localBluetoothAddress(context) }
    val current = BluetoothAddressPolicy.choose(systemBluetoothAddress, localAdapterAddress, savedManual)
    var editing by rememberSaveable { mutableStateOf(current.source == BluetoothSource.UNAVAILABLE) }
    var restoredNote by remember { mutableStateOf<String?>(null) }

    val address = current.address
    ValueRow(
        title = stringResource(R.string.settings_bt),
        summary = stringResource(R.string.settings_bt_summary),
        value = address ?: stringResource(R.string.settings_bt_value_missing),
        description = when (current.source) {
            BluetoothSource.SYSTEM_PROPERTY -> stringResource(R.string.settings_bt_current_property, address ?: "")
            BluetoothSource.LOCAL_ADAPTER -> stringResource(R.string.settings_bt_current_adapter, address ?: "")
            BluetoothSource.MANUAL -> stringResource(R.string.settings_bt_current_manual, address ?: "")
            BluetoothSource.UNAVAILABLE -> stringResource(R.string.settings_bt_current_unavailable)
        } + "\n" + stringResource(R.string.settings_bt_hint),
        valueColor = if (address == null) Palette.Warn else Palette.Text,
        monospace = address != null,
    ) { editing = !editing }
    if (!editing) return

    GroupRow(spacing = 8.dp) {
        val normalized = BluetoothAddressPolicy.normalize(manualText)
        val invalid = manualText.isNotBlank() && normalized == null
        OutlinedTextField(
            value = manualText,
            onValueChange = { manualText = it },
            label = { Text(stringResource(R.string.settings_bt_manual_label)) },
            isError = invalid,
            supportingText = if (invalid) {
                { Text(stringResource(R.string.settings_bt_invalid)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        // 手填地址空着且系统设置表里有备份（清过数据）：给一个「从备份恢复」，要不要恢复车主自己选。
        val backupAddress = remember { BluetoothAddressBackup.saved(context) }
        restoredNote?.let { RowNote(it, warn = false) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                stringResource(R.string.action_save),
                onClick = {
                    settings.manualBluetoothAddress = normalized
                    savedManual = normalized
                    manualText = normalized ?: ""
                    restoredNote = null
                    normalized?.let { value ->
                        Thread({ BluetoothAddressBackup.save(context, value) }, "OpenHC-BtBackup").start()
                    }
                },
                kind = ActionKind.PRIMARY,
                enabled = normalized != null && normalized != savedManual,
            )
            ActionButton(
                stringResource(R.string.action_clear),
                onClick = {
                    settings.manualBluetoothAddress = null
                    savedManual = null
                    manualText = ""
                    restoredNote = null
                    Thread({ BluetoothAddressBackup.clear(context) }, "OpenHC-BtBackup").start()
                },
                enabled = savedManual != null,
            )
            if (savedManual == null && backupAddress != null) {
                ActionButton(
                    stringResource(R.string.settings_bt_restore_backup, backupAddress),
                    onClick = {
                        val restored = BluetoothAddressBackup.restore(context, settings)
                        if (restored != null) {
                            savedManual = restored
                            manualText = restored
                            restoredNote = context.getString(R.string.settings_bt_restored, restored)
                        }
                    },
                )
            }
        }
    }
}
