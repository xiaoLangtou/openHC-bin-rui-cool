package io.github.gcbond.openhc.startup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.gcbond.openhc.MainActivity
import io.github.gcbond.openhc.connection.UsbAutoConnect
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.platform.usb.PhoneUsbMode
import io.github.gcbond.openhc.platform.usb.UsbDescriber
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessPermissions

/**
 * 设置「开机自动打开 OpenHC」（默认关）的安卓接线。开关的状态就是开机广播接收器的启用状态，不另存：
 * 关着时接收器停用，开机时系统根本不会为它拉起进程。它下面的「开机静默连接」（默认关）另存一个值（[isSilent]），
 * 只在开机自动打开开着时起作用（[silentEffective]），接线见 SilentConnect.kt。
 *
 * 开着时能不能真打开，还要过车机系统的两道门（设置页给出说明和跳转）：
 * - 安卓 10 起后台应用打开界面要悬浮窗权限（[canDrawOverlays]），没有的话打开请求不报错、但界面不出来；
 * - 有的车机系统默认禁止第三方应用开机自启动、每次更新应用还会重新禁止，要车主在系统的自启动管理里放行。
 *   应用自己查不到这个设置，只能按收没收到开机广播推断（[BootStartPolicy.bootSeenSinceInstall]）。
 */
internal object BootStart {

    fun isEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(receiver(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: RuntimeException) {
        false
    }

    /** 关 = 回到清单里的默认（停用）。立即生效。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        try {
            context.packageManager.setComponentEnabledSetting(receiver(context), state, PackageManager.DONT_KILL_APP)
            Log.i(TAG, "开机自动打开：${if (enabled) "开" else "关"}")
        } catch (e: RuntimeException) {
            Log.e(TAG, "切换开机广播接收器失败", e)
        }
    }

    /** 开机时打开界面的 Intent：界面已经在了就带到前台（singleTop，经 onNewIntent）。 */
    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(BootStartPolicy.ACTION_BOOT_LAUNCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isBootLaunch(intent: Intent?): Boolean = intent != null &&
        BootStartPolicy.isBootLaunch(intent.action, intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0)

    /** 开机静默连接把界面带到前台用的 Intent：界面已经在了就带到前台（singleTop，经 onNewIntent），不开始任何连接。 */
    fun surfaceIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(BootStartPolicy.ACTION_SHOW_PROJECTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 设置「开机静默连接」（默认关，与开机自动打开分开存）。 */
    fun isSilent(context: Context): Boolean = prefs(context).getBoolean(KEY_SILENT, false)

    fun setSilent(context: Context, silent: Boolean) {
        prefs(context).edit { putBoolean(KEY_SILENT, silent) }
        Log.i(TAG, "开机静默连接：${if (silent) "开" else "关"}")
    }

    /** 开机静默连接真的起作用：开机自动打开也开着（开机广播接收器启用着）。 */
    fun silentEffective(context: Context): Boolean = isSilent(context) && isEnabled(context)

    /** 无线等回连要的运行时权限都有（开始无线连接必须的，加上发 BLE 广播的）。 */
    fun wirelessPermissionsGranted(context: Context): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val granted = { permission: String ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        return WirelessPermissions.missing(sdk, granted).isEmpty() && WirelessPermissions.advertise(sdk).all(granted)
    }

    /** 开机打开界面以后要不要进入等插线，见 [BootStartPolicy.waitForCable]。 */
    fun waitForCable(context: Context): Boolean = BootStartPolicy.waitForCable(
        phonePlugged = phonePlugged(context),
        usbAutoConnect = UsbAutoConnect.isEnabled(context),
        wirelessWanted = wirelessWanted(context),
    )

    /** 开机后无线要不要自动连手机（只看设置）。 */
    fun wirelessWanted(context: Context): Boolean {
        val wireless = WirelessPreferences(context)
        return BootStartPolicy.wirelessWanted(wireless.wiredOnly, wireless.connectOnBluetooth)
    }

    /** 车机上插着能走有线的华为 / 荣耀手机（普通状态，或开着 USB 调试、等用户关掉）。 */
    fun phonePlugged(context: Context): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return try {
            usb.deviceList.values.any { UsbDescriber.phoneMode(it) in PLUGGED_MODES }
        } catch (e: RuntimeException) {
            false
        }
    }

    /** 有悬浮窗权限（安卓 10 起开机时打开界面要它）。 */
    fun canDrawOverlays(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (e: RuntimeException) {
        false
    }

    /** 本应用的悬浮窗权限页。 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())

    /** 本应用的系统设置页（有的车机把自启动、悬浮窗开关放在这里）。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /**
     * 比亚迪车机的「选择禁止后台启动项」对话框：开关开着的应用收不到开机广播、蓝牙连上的广播（没在运行时），
     * 车主要把本应用的开关关掉才放行；比亚迪每次更新安装应用都会把它重新打开。没有这个对话框的车机返回 null。
     */
    fun bydAutostartIntent(context: Context): Intent? {
        val intent = Intent(ACTION_BYD_APP_START_MANAGEMENT)
        val pm = context.packageManager
        val found = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
        } catch (e: RuntimeException) {
            null
        }
        return if (found != null) intent else null
    }

    fun lastRecord(context: Context): BootRecord? = BootRecord.decode(prefs(context).getString(KEY_LAST_BOOT, null))

    fun saveRecord(context: Context, record: BootRecord) {
        prefs(context).edit { putString(KEY_LAST_BOOT, record.encode()) }
    }

    /** 本次安装或更新的时间（墙上时间，毫秒，安装那一刻记下的，之后不变）；读不到返回 0。 */
    fun installedAtMillis(context: Context): Long = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    private fun receiver(context: Context) = ComponentName(context, BootStartReceiver::class.java)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val PLUGGED_MODES = setOf(PhoneUsbMode.NORMAL, PhoneUsbMode.USB_DEBUGGING)
    private const val PREFS_FILE = "openhc-boot"
    private const val KEY_LAST_BOOT = "last_boot"
    private const val KEY_SILENT = "silent_connect"

    /** 比亚迪「选择禁止后台启动项」对话框的动作（清单里的 queries 也要声明，安卓 11 起才查得到）。 */
    private const val ACTION_BYD_APP_START_MANAGEMENT = "android.intent.action.BYD_APPSTARTMANAGEMENT"
    const val TAG = "OpenHC-Boot"
}
