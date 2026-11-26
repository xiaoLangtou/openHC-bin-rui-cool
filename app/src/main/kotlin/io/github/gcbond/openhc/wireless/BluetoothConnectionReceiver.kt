package io.github.gcbond.openhc.wireless

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.IntentCompat
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.platform.log.Log

/**
 * 「蓝牙连上自动连接」的入口：手机的经典蓝牙连上 / 断开车机时（系统广播 ACL_CONNECTED / ACL_DISCONNECTED），以及
 * HFP/A2DP 等 Profile 连接状态变化（只有 [BluetoothProfile.STATE_CONNECTED] 才算「连上」，且带 profileReady = true，
 * 让车机跳过建链防抖立即拨；Profile 断开不当作手机断开——投屏期间系统会切换音频链路），交给
 * [WirelessController.onPhoneBluetooth]。清单里静态登记，本应用没在运行时系统也会把它拉起来
 * （车机系统要允许本应用在后台被拉起）。
 *
 * 清单里默认不启用，只在开关开着、且没开「只用数据线连接」时启用（[sync]），免得每连一个蓝牙设备都拉起本应用。
 * 只认蓝牙设备类是「手机」的（[BluetoothTrigger.isPhone]），耳机、音箱、手表不算。
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val (connected, profileReady) = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true to false
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false to false
            // HFP/A2DP 等 Profile 的连接状态：只有连上（STATE_CONNECTED）才算，断开忽略。
            in PROFILE_ACTIONS -> {
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                    true to true
                } else {
                    return
                }
            }
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        // 读设备类在安卓 12 起要「附近的设备」权限；没有时读不到，当作不是手机（没这个权限无线本来也连不了）。
        val major = try {
            device.bluetoothClass?.majorDeviceClass
        } catch (e: SecurityException) {
            null
        }
        if (!BluetoothTrigger.isPhone(major)) return
        val address = try {
            device.address
        } catch (e: RuntimeException) {
            null
        } ?: return
        val app = context.applicationContext as? OpenHcApp ?: return
        app.wireless.onPhoneBluetooth(connected, address, profileReady)
    }

    companion object {
        private const val TAG = "OpenHC-Wireless"

        /** HFP/A2DP 等 Profile 的连接状态广播（ACL 之外用来判断建链已稳定）。 */
        private val PROFILE_ACTIONS = setOf(
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
        )

        /** 按设置启用或停用清单里的这个接收器。立即生效，不影响正在运行的进程。 */
        fun sync(context: Context, enabled: Boolean) {
            val component = ComponentName(context, BluetoothConnectionReceiver::class.java)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            try {
                val pm = context.packageManager
                if (pm.getComponentEnabledSetting(component) == state) return
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "蓝牙连上自动连接的系统广播入口：${if (enabled) "启用" else "停用"}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "改不了蓝牙广播入口的启用状态：${e.message}")
            }
        }
    }
}

/** 「蓝牙连上自动连接」的判断（纯逻辑）。 */
object BluetoothTrigger {
    /** 蓝牙设备大类「手机」（蓝牙规范的设备类取值，与 BluetoothClass.Device.Major.PHONE 相同）。 */
    const val MAJOR_PHONE = 0x0200

    /** 连上的是不是手机；读不到设备类时不算。 */
    fun isPhone(majorDeviceClass: Int?): Boolean = majorDeviceClass == MAJOR_PHONE

    /** 要不要听手机蓝牙连上：开关开着、没开「只用数据线连接」。 */
    fun listens(connectOnBluetooth: Boolean, wiredOnly: Boolean): Boolean = connectOnBluetooth && !wiredOnly
}
