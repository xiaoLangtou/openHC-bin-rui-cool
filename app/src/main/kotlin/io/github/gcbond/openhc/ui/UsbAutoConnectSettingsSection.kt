package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.UsbAutoConnect

/** 「连接方式」组里的一行：插线时不再弹窗（含插线就连），默认关，立即生效。机制见 [UsbAutoConnect]。 */
@Composable
internal fun UsbAutoConnectSettingsSection() {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(UsbAutoConnect.isEnabled(context)) }
    SwitchRow(
        title = stringResource(R.string.settings_usb_auto),
        summary = stringResource(R.string.settings_usb_auto_summary),
        checked = enabled,
        description = stringResource(if (enabled) R.string.settings_usb_auto_hint_on else R.string.settings_usb_auto_hint_off),
    ) {
        UsbAutoConnect.setEnabled(context, it)
        // 以系统里的实际状态为准（切换失败时开关弹回去）。
        enabled = UsbAutoConnect.isEnabled(context)
    }
}
