package io.github.gcbond.openhc.wireless

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.platform.log.Log
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.WirelessPreferences

/**
 * 从界面开始无线连接：先拿运行时权限（见 [WirelessPermissions]），拿到了再交给调度；被拒时把原因交给调度显示。
 * 开着「无线自动回连」时顺带申请蓝牙广播权限（安卓 12 起）；它被拒不挡扫码，只是不发广播。
 * 另外提供两个跳系统页面的入口：本应用的系统设置（权限被永久拒绝时）、请系统打开蓝牙。
 *
 * 必须在 Activity 构造时创建（结果回调要在 onCreate 之前注册）。
 */
internal class WirelessStartFlow(
    private val activity: ComponentActivity,
    private val controller: () -> WirelessController,
) {
    private val settings by lazy { AppSettings(activity) }
    private val wirelessPrefs by lazy { WirelessPreferences(activity) }

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionResult()
    }

    /** 「开始无线连接」。 */
    fun start() {
        val sdk = Build.VERSION.SDK_INT
        // 只用数据线连接开着、或系统太老开不了本地热点：不用申请任何权限，交给调度直接说明原因。
        if (wirelessPrefs.wiredOnly || !WirelessNetworkPlan.hotspotSupported(sdk)) {
            controller().start()
            return
        }
        val missing = WirelessPermissions.missing(sdk, ::granted)
        // 通知权限只问一次（和有线共用这个记号），拒绝了也照常连接。
        val askNotifications = sdk >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            !granted(WirelessPermissions.POST_NOTIFICATIONS)
        // 广播权限被拒过也照样再问（系统拒绝两次后自己就不再弹框）：没有它就不能自动回连。
        val askAdvertise = wirelessPrefs.autoReconnect && WirelessPermissions.advertise(sdk).any { !granted(it) }
        if (missing.isEmpty() && !askNotifications && !askAdvertise) {
            controller().start()
            return
        }
        if (askNotifications) settings.notificationPermissionAsked = true
        val request = if (missing.isEmpty()) {
            (if (askAdvertise) WirelessPermissions.advertise(sdk) else emptyList()) +
                (if (askNotifications) listOf(WirelessPermissions.POST_NOTIFICATIONS) else emptyList())
        } else {
            WirelessPermissions.toRequest(sdk, askNotifications, askAdvertise)
        }
        Log.i(TAG, "申请权限：${request.joinToString()}")
        try {
            launcher.launch(request.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            controller().reportBlocked(WirelessNotice.StartFailed("系统没有权限申请页"))
        }
    }

    private fun onPermissionResult() {
        val denied = WirelessPermissions.afterRequest(Build.VERSION.SDK_INT, ::granted) {
            activity.shouldShowRequestPermissionRationale(it)
        }
        if (denied == null) {
            controller().start()
        } else {
            Log.i(TAG, "权限没有给全：$denied")
            controller().reportBlocked(denied)
        }
    }

    /** 打开本应用的系统设置页，让用户手动开权限。 */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        launchQuietly(intent, "应用设置页")
    }

    /**
     * 请系统打开蓝牙（系统弹框）。安卓 12 起要先有「附近的设备」权限：没有就先走 [start] 申请。
     * 车机系统没有这个页面时只记日志，界面上的提示仍然让用户自己去打开。
     */
    // 上面先判断了权限，lint 认不出这种写法。
    @SuppressLint("MissingPermission")
    fun requestEnableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(WirelessPermissions.BLUETOOTH_CONNECT)) {
            start()
            return
        }
        launchQuietly(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "打开蓝牙的系统框")
    }

    private fun launchQuietly(intent: Intent, what: String) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "系统没有$what")
        } catch (e: SecurityException) {
            Log.w(TAG, "打不开$what：${e.message}")
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "OpenHC-Wireless"
    }
}
