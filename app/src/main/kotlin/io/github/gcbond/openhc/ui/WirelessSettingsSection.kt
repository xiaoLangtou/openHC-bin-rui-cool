package io.github.gcbond.openhc.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.WirelessPreferences

/**
 * 无线连接的两项设置在设置页里分在两组：「连接方式」里是只用数据线连接，「启动」里是自动连接手机。切换立即生效。
 *
 * 这两行分在不同的组里、又互相影响（只用数据线连接开着时提示别的不起作用，自动连接手机开着时「启动」组要列出悬浮窗权限），
 * 所以状态放在这里，由设置页创建一份（[rememberWirelessSettingsState]）传给各行。BLE 广播的调试项随被动等待一起不再显示。
 */
@Stable
internal class WirelessSettingsState(context: Context) {
    private val prefs = WirelessPreferences(context)
    private val controller = (context.applicationContext as? OpenHcApp)?.wireless

    var wiredOnly by mutableStateOf(prefs.wiredOnly)
        private set
    var connectOnBluetooth by mutableStateOf(prefs.connectOnBluetooth)
        private set
    var wifiDirect by mutableStateOf(prefs.wifiDirect)
        private set

    fun changeWiredOnly(value: Boolean) {
        wiredOnly = value
        prefs.wiredOnly = value
        controller?.onWiredOnlyChanged()
    }

    /** 下一次无线连接生效，正在进行的连接照旧。 */
    fun changeWifiDirect(value: Boolean) {
        wifiDirect = value
        prefs.wifiDirect = value
    }

    fun changeConnectOnBluetooth(value: Boolean) {
        connectOnBluetooth = value
        prefs.connectOnBluetooth = value
        controller?.onConnectOnBluetoothChanged()
    }

}

@Composable
internal fun rememberWirelessSettingsState(): WirelessSettingsState {
    val context = LocalContext.current
    return remember(context) { WirelessSettingsState(context) }
}

/** 只用数据线连接（默认关）：开着时无线关掉，提示自动连接手机不起作用。 */
@Composable
internal fun WiredOnlySetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_wired_only),
        checked = state.wiredOnly,
        description = stringResource(R.string.settings_wireless_wired_only_hint),
        note = if (state.wiredOnly) stringResource(R.string.settings_wireless_wired_only_note) else null,
        onCheckedChange = state::changeWiredOnly,
    )
}

/** 自动连接手机（默认开；存储的键沿用旧名「蓝牙连上自动连接」）。 */
@Composable
internal fun ConnectOnBluetoothSetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_connect_on_bt),
        checked = state.connectOnBluetooth,
        description = stringResource(R.string.settings_wireless_connect_on_bt_hint),
        onCheckedChange = state::changeConnectOnBluetooth,
    )
}

/** 无线方式（默认车机开热点）：「5 GHz 直连」是试验功能；系统不支持时不显示这一行。 */
@Composable
internal fun WirelessModeSetting(state: WirelessSettingsState) {
    if (!WirelessPreferences.wifiDirectSupported) return
    ChoiceRow(
        title = stringResource(R.string.settings_wireless_mode),
        options = listOf(stringResource(R.string.settings_wireless_mode_hotspot), stringResource(R.string.settings_wireless_mode_direct)),
        selected = if (state.wifiDirect) 1 else 0,
        description = stringResource(R.string.settings_wireless_mode_hint),
        note = if (state.wifiDirect) stringResource(R.string.settings_wireless_mode_direct_note) else null,
    ) { state.changeWifiDirect(it == 1) }
}
