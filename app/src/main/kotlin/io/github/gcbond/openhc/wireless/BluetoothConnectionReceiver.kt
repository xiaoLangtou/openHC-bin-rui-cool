package io.github.gcbond.openhc.wireless

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.IntentCompat
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.platform.log.Log

/**
 * 「蓝牙连上自动连接」的入口：手机的经典蓝牙连上 / 断开车机时（系统广播 ACL_CONNECTED / ACL_DISCONNECTED），
 * 交给 [WirelessController.onPhoneBluetooth]。清单里静态登记，本应用没在运行时系统也会把它拉起来
 * （车机系统要允许本应用在后台被拉起）。
 *
 * 清单里默认不启用，只在开关开着、且没开「只用数据线连接」时启用（[sync]），免得每连一个蓝牙设备都拉起本应用。
 * 只认蓝牙设备类是「手机」的（[BluetoothTrigger.isPhone]），耳机、音箱、手表不算。
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
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
        app.wireless.onPhoneBluetooth(connected, address)
    }

    companion object {
        private const val TAG = "OpenHC-Wireless"

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
