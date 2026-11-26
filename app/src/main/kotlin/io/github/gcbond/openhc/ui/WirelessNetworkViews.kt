package io.github.gcbond.openhc.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.WirelessNetworkPreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wifi.CarHotspotAutoPreferences
import io.github.gcbond.openhc.wireless.WirelessUiState

// 无线连接里 WiFi 那一段（车机开热点）：设置页「连接方式」组里的「退出后恢复车机 WiFi」、
// 首页卡片上的一行说明，以及首页顶上的提示卡片。

/** 「连接方式」组里的一行：退出后恢复车机 WiFi（默认关）。连接结束时读。 */
@Composable
internal fun RestoreWifiSetting() {
    val context = LocalContext.current
    val prefs = remember(context) { WirelessNetworkPreferences(context) }
    var restore by remember { mutableStateOf(prefs.restoreWifiOnExit) }
    SwitchRow(
        title = stringResource(R.string.settings_wireless_restore),
        summary = stringResource(R.string.settings_wireless_restore_summary),
        checked = restore,
        description = stringResource(R.string.settings_wireless_restore_hint),
    ) {
        restore = it
        prefs.restoreWifiOnExit = it
    }
}

/**
 * 无线连接怎么给手机准备 WiFi 的一句说明：车机开热点（车机原来连着的 WiFi 会断开）、5 GHz 直连，或者车机自带热点。
 * 直连按这次连接实际用的（进行中）或设置（空闲时）；车机自带热点按设置（设置页和首页不同时显示，回到首页时重新组合，
 * 读到的是新值）。
 */
@Composable
internal fun wirelessNetworkText(state: WirelessUiState): String {
    val context = LocalContext.current
    val prefs = remember(context) { WirelessPreferences(context) }
    val direct = if (state.active) state.direct else prefs.usesWifiDirect
    return stringResource(
        when {
            direct -> R.string.wireless_network_line_direct
            prefs.carHotspot -> if (CarHotspotAutoPreferences(context).enabled) {
                R.string.wireless_network_line_car_auto
            } else {
                R.string.wireless_network_line_car
            }
            else -> R.string.wireless_network_line
        },
    )
}

/**
 * 「退出后恢复车机 WiFi」要车主自己打开 WiFi 时（安卓 10 起应用打不开）首页顶上的提示卡片；不需要时什么都不显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WifiRestoreCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wireless = remember(context) { (context.applicationContext as? OpenHcApp)?.wireless } ?: return
    val needed by wireless.wifiRestore.needsUser.collectAsState()
    if (!needed) return
    CardBox(modifier.fillMaxWidth(), color = Palette.WarnBackground, border = Palette.WarnBorder) {
        Text(stringResource(R.string.wireless_restore_title), style = UiType.status, color = Palette.Warn)
        Text(
            stringResource(R.string.wireless_restore_text),
            style = UiType.hint,
            color = Palette.Text,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PillButton(stringResource(R.string.wireless_restore_open_panel), { openWifi(context, panel = true) })
            }
            PillButton(stringResource(R.string.wireless_restore_open_settings), { openWifi(context, panel = false) }, primary = false)
            PillButton(stringResource(R.string.wireless_restore_dismiss), { wireless.wifiRestore.dismiss() }, primary = false)
        }
    }
}

/**
 * 打开系统的 WiFi 开关：安卓 10 起先试系统的 WiFi 面板，没有就退到 WiFi 设置页；都打不开提示车主自己去车机设置里开。
 * 车机系统常常把标准设置页换成自己的，能不能打开、打开的是不是能用的页面，因车而异。
 */
private fun openWifi(context: Context, panel: Boolean) {
    val intents = ArrayList<Intent>(2)
    if (panel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intents += Intent(Settings.Panel.ACTION_WIFI)
    intents += Intent(Settings.ACTION_WIFI_SETTINGS)
    for (intent in intents) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // 换下一个。
        } catch (e: SecurityException) {
            // 同上。
        }
    }
    Toast.makeText(context, R.string.wireless_restore_unavailable, Toast.LENGTH_LONG).show()
}
