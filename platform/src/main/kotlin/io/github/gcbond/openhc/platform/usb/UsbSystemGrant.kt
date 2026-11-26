package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Process
import io.github.gcbond.openhc.platform.log.Log

/**
 * 系统签名进程里静默自授 USB 设备权限，免掉每次插线的 USB 授权框（有的车机授权框压根不弹）。
 *
 * 两条路，先走哪条都行：
 * - `UsbManager.grantPermission(UsbDevice, String)`（隐藏接口，framework 进程内直接可用）；
 * - binder 直连 `IUsbManager.grantDevicePermission(UsbDevice, int)`，传自己的 uid。
 *
 * 所有反射异常安全捕获，失败返回 false，调用方回退既有的 `requestPermission` 弹窗流程。
 */
internal object UsbSystemGrant {

    private const val TAG = "OpenHC-Wired"

    /** 尝试静默授予 [device] 给 [packageName] 使用的权限，成功与否看调用方随后再查一次 [UsbManager.hasPermission]。 */
    fun grant(usb: UsbManager, device: UsbDevice, packageName: String): Boolean {
        // 路一：UsbManager.grantPermission(UsbDevice, String)。
        try {
            val m = UsbManager::class.java.getMethod(
                "grantPermission",
                UsbDevice::class.java,
                String::class.java,
            )
            m.invoke(usb, device, packageName)
            Log.i(TAG, "USB 权限已静默自授（UsbManager.grantPermission）")
            return true
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "UsbManager.grantPermission 不存在，改走 IUsbManager")
        } catch (e: Throwable) {
            Log.w(TAG, "UsbManager.grantPermission 调用失败", e)
        }
        // 路二：IUsbManager.grantDevicePermission(UsbDevice, int uid)。
        try {
            val binder = systemUsbBinder()
            val usbService = iUsbManager(binder)
            val m = usbService.javaClass.getMethod(
                "grantDevicePermission",
                UsbDevice::class.java,
                Int::class.javaPrimitiveType,
            )
            m.invoke(usbService, device, Process.myUid())
            Log.i(TAG, "USB 权限已静默自授（IUsbManager.grantDevicePermission）")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "IUsbManager.grantDevicePermission 不可用", e)
        }
        return false
    }

    private fun systemUsbBinder(): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        val getService = sm.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "usb") as? IBinder
        if (binder == null || !binder.pingBinder()) {
            throw IllegalStateException("系统服务 usb 不可用")
        }
        return binder
    }

    private fun iUsbManager(binder: IBinder): Any {
        val stub = Class.forName("android.hardware.usb.IUsbManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }
}
