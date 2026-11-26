package io.github.gcbond.openhc.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.platform.wifi.LocalHotspotOwner
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
    var carHotspot by mutableStateOf(prefs.carHotspot)
        private set
    var carHotspotName by mutableStateOf(prefs.carHotspotName)
        private set
    var carHotspotPassword by mutableStateOf(prefs.carHotspotPassword)
        private set
    var carHotspotFiveGhz by mutableStateOf(prefs.carHotspotFiveGhz)
        private set
    // 本地：系统热点强制 5 GHz 信道 36（v27，照抄 CarLink 路线），与上游 carHotspotFiveGhz（车机自带热点频段）并存。
    var hotspot5g by mutableStateOf(prefs.hotspot5g)
        private set

    fun changeWiredOnly(value: Boolean) {
        wiredOnly = value
        prefs.wiredOnly = value
        controller?.onWiredOnlyChanged()
    }

    /** 无线方式：0 车机开热点、1 直连、2 车机自带热点。下一次无线连接生效，正在进行的连接照旧。 */
    fun changeWirelessMode(mode: Int) {
        wifiDirect = mode == 1
        carHotspot = mode == 2
        prefs.wifiDirect = wifiDirect
        prefs.carHotspot = carHotspot
    }

    fun changeCarHotspotName(value: String) {
        carHotspotName = value
        prefs.carHotspotName = value
    }

    fun changeCarHotspotPassword(value: String) {
        carHotspotPassword = value
        prefs.carHotspotPassword = value
    }

    fun changeCarHotspotFiveGhz(value: Boolean) {
        carHotspotFiveGhz = value
        prefs.carHotspotFiveGhz = value
    }

    /** 热点频段立即生效（下一次申请热点时写系统配置），设置同步给热点所有者。 */
    fun changeHotspot5g(value: Boolean) {
        hotspot5g = value
        prefs.hotspot5g = value
        LocalHotspotOwner.forcedChannel = if (value) 36 else null
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
        summary = stringResource(R.string.settings_wireless_wired_only_summary),
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
        summary = stringResource(R.string.settings_wireless_connect_on_bt_summary),
        checked = state.connectOnBluetooth,
        description = stringResource(R.string.settings_wireless_connect_on_bt_hint),
        onCheckedChange = state::changeConnectOnBluetooth,
    )
}

/** 无线方式（默认车机开热点）：「5 GHz 直连」是试验功能；系统不支持时不显示这一行。 */
@Composable
internal fun WirelessModeSetting(state: WirelessSettingsState) {
    if (!WirelessPreferences.wifiDirectSupported) return
    CardChoiceRow(
        title = stringResource(R.string.settings_wireless_mode),
        summary = stringResource(R.string.settings_wireless_mode_summary),
        options = listOf(
            CardOption(stringResource(R.string.settings_wireless_mode_hotspot), stringResource(R.string.settings_wireless_mode_hotspot_text)),
            CardOption(stringResource(R.string.settings_wireless_mode_direct), stringResource(R.string.settings_wireless_mode_direct_text)),
            CardOption(stringResource(R.string.settings_wireless_mode_car), stringResource(R.string.settings_wireless_mode_car_text)),
        ),
        selected = when {
            state.carHotspot -> 2
            state.wifiDirect -> 1
            else -> 0
        },
        description = stringResource(R.string.settings_wireless_mode_hint),
        // 选中的方式自己的设置放在卡片下面的面板里（见 CardChoiceRow）。
        detail = { mode ->
            when (mode) {
                1 -> {
                    {
                        DetailNote(stringResource(R.string.settings_wireless_mode_direct_note), warn = true)
                        ScanPauseSetting()
                    }
                }
                2 -> {
                    {
                        DetailNote(stringResource(R.string.settings_wireless_mode_car_note))
                        CarHotspotFields(state)
                        CarHotspotAutoSetting()
                        ScanPauseSetting()
                    }
                }
                else -> null
            }
        },
    ) { state.changeWirelessMode(it) }
}


/** 车机自带热点的名称、密码（输入即保存，密码显示成圆点；宽屏时并排）和频段。 */
@Composable
private fun CarHotspotFields(state: WirelessSettingsState) {
    GroupRow(spacing = 8.dp) {
        val name: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = state.carHotspotName,
                onValueChange = { state.changeCarHotspotName(it) },
                label = { Text(stringResource(R.string.settings_car_hotspot_name)) },
                singleLine = true,
                modifier = modifier,
            )
        }
        val password: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedTextField(
                value = state.carHotspotPassword,
                onValueChange = { state.changeCarHotspotPassword(it) },
                label = { Text(stringResource(R.string.settings_car_hotspot_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = modifier,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= FIELDS_ACROSS_MIN) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    name(Modifier.weight(1f))
                    password(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    name(Modifier.fillMaxWidth())
                    password(Modifier.fillMaxWidth())
                }
            }
        }
        if (state.carHotspotName.isEmpty() || state.carHotspotPassword.isEmpty()) {
            RowNote(stringResource(R.string.settings_car_hotspot_missing))
        }
    }
    ChoiceRow(
        title = stringResource(R.string.settings_car_hotspot_band),
        summary = stringResource(R.string.settings_car_hotspot_band_summary),
        options = listOf("2.4 GHz", "5 GHz"),
        selected = if (state.carHotspotFiveGhz) 1 else 0,
        description = stringResource(R.string.settings_car_hotspot_band_hint),
    ) { state.changeCarHotspotFiveGhz(it == 1) }
}

/** 热点频段（默认强制 5 GHz 信道 36）：只在「车机开热点」方式下有用；直连方式自带指定信道。 */
@Composable
internal fun Hotspot5gSetting(state: WirelessSettingsState) {
    SwitchRow(
        title = stringResource(R.string.settings_wireless_hotspot_5g),
        checked = state.hotspot5g,
        description = stringResource(R.string.settings_wireless_hotspot_5g_hint),
        note = if (state.hotspot5g) stringResource(R.string.settings_wireless_hotspot_5g_note) else null,
        onCheckedChange = state::changeHotspot5g,
    )
}

/** 热点名和密码并排放的最小宽度。 */
private val FIELDS_ACROSS_MIN = 560.dp
