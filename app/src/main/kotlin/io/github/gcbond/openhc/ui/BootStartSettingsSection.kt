package io.github.gcbond.openhc.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.UsbAutoConnect
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootOutcome
import io.github.gcbond.openhc.startup.BootRecord
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.wireless.WirelessNetworkPlan
import java.text.DateFormat
import java.util.Date

/**
 * 设置页「启动」一组：开机自动打开 OpenHC（默认关，立即生效），[extraRows] 排在它下面（「自动连接手机」），
 * 再下面是开机静默连接（默认关，只在开机自动打开开着时起作用）；开机自动打开或自动连接手机（[autoConnect]）开着时，
 * 再往下列出还要车主做的系统设置：安卓 10 起的悬浮窗权限（带跳转；后台打开界面要靠它，自动连接时开热点也要界面在前台）、
 * 车机系统的自启动管理（比亚迪直接打开它的对话框；别的车机只能说明，带本应用系统设置页的跳转），以及上次开机的结果。
 */
@Composable
internal fun BootStartSettingsSection(autoConnect: Boolean = false, extraRows: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(BootStart.isEnabled(context)) }
    var silent by remember(context) { mutableStateOf(BootStart.isSilent(context)) }
    var notifications by remember(context) { mutableStateOf(notificationsGranted(context)) }
    val vpnGranted = remember(context) { VpnConsent.isGranted(context) }
    var overlay by remember(context) { mutableStateOf(BootStart.canDrawOverlays(context)) }
    val record = remember(context) { BootStart.lastRecord(context) }
    val installedAt = remember(context) { BootStart.installedAtMillis(context) }
    // 自动连接手机在同一页里能切换：跟着 [autoConnect] 重算。
    val wirelessWanted = remember(context, autoConnect) { BootStart.wirelessWanted(context) }
    val usbChanges by UsbAutoConnect.changes.collectAsState()
    val usbAutoConnect = remember(context, usbChanges) { UsbAutoConnect.isEnabled(context) }
    // 从系统设置页回来时重查悬浮窗权限。
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlay = BootStart.canDrawOverlays(context)
        notifications = notificationsGranted(context)
    }
    // 静默时等待的常驻通知要通知权限（安卓 13 起）：打开静默时没有就问一次，之后在这一行提示、点提示去系统设置开。
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = notificationsGranted(context)
    }
    val noSettingsPage = stringResource(R.string.settings_boot_no_settings_page)
    val openFirstAvailable = { intents: List<Intent> ->
        val opened = intents.any { intent ->
            try {
                settingsPage.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) Toast.makeText(context, noSettingsPage, Toast.LENGTH_LONG).show()
    }
    // 静默时系统没有本地热点（安卓 8.0 以下，用不了无线）就只等插线，见 SilentConnectPolicy.route。
    val hotspotSupported = WirelessNetworkPlan.hotspotSupported(Build.VERSION.SDK_INT)
    // 开机后等不等插线（见 BootStartPolicy.waitForCable，开机时插着手机的情况不在这里说）、无线怎么自动连；静默时在后台。
    val afterBoot = when {
        silent && (!wirelessWanted || !hotspotSupported) -> R.string.settings_boot_silent_wired
        !wirelessWanted -> R.string.settings_boot_wired
        usbAutoConnect -> if (silent) R.string.settings_boot_silent_wireless_both else R.string.settings_boot_wireless_both
        else -> if (silent) R.string.settings_boot_silent_wireless_blocked else R.string.settings_boot_wireless_blocked
    }
    // 静默那一行的提示：开机自动打开关着时它不起作用；开着时说还缺什么（VPN 授权只影响先等插线的情况）。
    val waitsForCable = !hotspotSupported ||
        BootStartPolicy.waitForCable(
            phonePlugged = false,
            usbAutoConnect = usbAutoConnect,
            wirelessWanted = wirelessWanted,
        )
    val silentNote = when {
        !enabled -> stringResource(R.string.settings_boot_silent_needs_boot)
        !silent -> null
        waitsForCable && !vpnGranted -> stringResource(R.string.settings_boot_silent_needs_vpn)
        !notifications -> stringResource(R.string.settings_boot_silent_needs_notifications)
        else -> null
    }

    SettingsGroup(stringResource(R.string.settings_boot_title)) {
        SwitchRow(
            title = stringResource(R.string.settings_boot),
            checked = enabled,
            description = stringResource(R.string.settings_boot_hint),
            note = if (enabled) stringResource(afterBoot) else null,
            noteWarn = false,
        ) {
            BootStart.setEnabled(context, it)
            // 以系统里的实际状态为准（切换失败时开关弹回去）。
            enabled = BootStart.isEnabled(context)
        }
        extraRows()
        SwitchRow(
            title = stringResource(R.string.settings_boot_silent),
            checked = silent,
            description = stringResource(R.string.settings_boot_silent_hint),
            note = silentNote,
            onNoteClick = if (enabled && silent && !notifications) {
                { openFirstAvailable(listOf(notificationSettingsIntent(context), BootStart.appDetailsIntent(context))) }
            } else {
                null
            },
            enabled = enabled,
        ) {
            BootStart.setSilent(context, it)
            silent = it
            if (it && !notifications) {
                try {
                    notificationPermission.launch(POST_NOTIFICATIONS)
                } catch (e: ActivityNotFoundException) {
                    // 系统没有权限申请页：这一行的提示会让车主去系统设置里开。
                }
            }
        }
        // 比亚迪车机：开机自动打开、自动连接手机都要在它的「选择禁止后台启动项」里放行，开关没开也给入口。
        val bydAutostart = remember(context) { BootStart.bydAutostartIntent(context) }
        if (!enabled && !autoConnect) {
            if (bydAutostart != null) BydAutostartRow { openFirstAvailable(listOf(bydAutostart, BootStart.appDetailsIntent(context))) }
            return@SettingsGroup
        }

        // 悬浮窗权限
        when {
            !BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT) -> GroupRow {
                RowDescription(stringResource(R.string.settings_boot_overlay_not_needed, Build.VERSION.RELEASE))
            }
            overlay -> GroupRow { RowDescription(stringResource(R.string.settings_boot_overlay_granted)) }
            else -> GroupRow(
                Modifier.clickable(role = Role.Button) {
                    openFirstAvailable(listOf(BootStart.overlaySettingsIntent(context), BootStart.appDetailsIntent(context)))
                },
            ) {
                RowNote(stringResource(if (silent) R.string.settings_boot_overlay_missing_silent else R.string.settings_boot_overlay_missing))
            }
        }
        // 车机系统的自启动管理：比亚迪直接打开它的对话框；别的车机只能说明，给个本应用系统设置页的入口
        if (bydAutostart != null) {
            BydAutostartRow { openFirstAvailable(listOf(bydAutostart, BootStart.appDetailsIntent(context))) }
        } else {
            GroupRow(spacing = 8.dp) {
                RowHeader(stringResource(R.string.settings_boot_autostart_title), stringResource(R.string.settings_boot_autostart_note))
                ActionButton(
                    stringResource(R.string.settings_boot_open_app_settings),
                    onClick = { openFirstAvailable(listOf(BootStart.appDetailsIntent(context))) },
                )
            }
        }
        // 上次开机的结果
        val seen = BootStartPolicy.bootSeenSinceInstall(record, installStamp = installedAt)
        if (enabled && (record != null || !seen)) {
            GroupRow(spacing = 4.dp) {
                if (record != null) RowDescription(lastBootText(record), color = Palette.Text)
                if (!seen) RowDescription(stringResource(R.string.settings_boot_not_seen))
            }
        }
    }
}

@Composable
private fun lastBootText(record: BootRecord): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.atMillis))
    val kind = if (BootStartPolicy.isFreshBoot(record.sinceBootMillis)) {
        stringResource(R.string.settings_boot_fresh)
    } else {
        stringResource(R.string.settings_boot_resent, record.sinceBootMillis / 60_000)
    }
    val outcome = stringResource(
        when (record.outcome) {
            BootOutcome.PENDING -> R.string.settings_boot_outcome_pending
            BootOutcome.OPENED -> R.string.settings_boot_outcome_opened
            BootOutcome.COVERED -> R.string.settings_boot_outcome_covered
            BootOutcome.BLOCKED_NO_OVERLAY -> R.string.settings_boot_outcome_no_overlay
            BootOutcome.NOT_SHOWN -> R.string.settings_boot_outcome_not_shown
            BootOutcome.FAILED -> R.string.settings_boot_outcome_failed
            BootOutcome.SILENT_WIRED -> R.string.settings_boot_outcome_silent_wired
            BootOutcome.SILENT_WIRELESS -> R.string.settings_boot_outcome_silent_wireless
            BootOutcome.SILENT_STANDBY -> R.string.settings_boot_outcome_silent_standby
        },
    )
    return stringResource(R.string.settings_boot_last, time, kind, outcome)
}

/** 比亚迪车机的后台启动放行：说明 + 直接打开它的「选择禁止后台启动项」对话框。 */
@Composable
private fun BydAutostartRow(onOpen: () -> Unit) {
    GroupRow(spacing = 8.dp) {
        RowHeader(stringResource(R.string.settings_boot_byd_title), stringResource(R.string.settings_boot_byd_note))
        ActionButton(stringResource(R.string.settings_boot_open_byd), onClick = onOpen)
    }
}

/** 能显示通知：安卓 13 以前不用申请。 */
private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * 本应用的通知设置页（安卓 8.0 起有；车机没有这一页时退到应用详情页）。只在安卓 13 起缺通知权限时用到；
 * 动作名、参数名直接写字符串：常量只在新版本 SDK 里有，值本身只是字符串。
 */
private fun notificationSettingsIntent(context: Context): Intent =
    Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", context.packageName)

/** 直接写权限名：常量只在新版本 SDK 里有，值本身只是字符串。 */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
