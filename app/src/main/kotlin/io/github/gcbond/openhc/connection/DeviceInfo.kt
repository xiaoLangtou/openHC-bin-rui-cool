package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate

/** 从系统读车机的蓝牙地址属性、本机蓝牙适配器地址和屏幕参数。 */
internal object DeviceInfo {

    /**
     * 按顺序读系统属性 [BluetoothAddressPolicy.SYSTEM_PROPERTY_KEYS]，取第一个有效的地址，都读不到返回 null。
     * 系统属性的读取接口没有公开，只能反射。安卓 9 起非公开接口受限，这个接口属于允许调用、系统只记一行警告的那一类；
     * 将来的系统要是拦了，这里返回 null，退回手填地址。
     */
    @SuppressLint("PrivateApi")
    fun systemBluetoothAddress(): String? = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        BluetoothAddressPolicy.firstValid(BluetoothAddressPolicy.SYSTEM_PROPERTY_KEYS.map { get.invoke(null, it) as? String })
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /**
     * 读「本机」蓝牙适配器的地址：先问 [BluetoothManager]（安卓 6.0 起普通应用多半只拿到
     * `02:00:00:00:00:00` 掩码值，[BluetoothAddressPolicy.normalize] 会拒掉），再试系统设置里的
     * `bluetooth_address`（有的厂商把真地址放在这儿，普通应用可读；华为系有 ACL 挡 shell，应用自身能不能读要实测）。
     * 读不到或都是掩码值返回 null。别在界面线程上调（读设置要跨进程）。
     */
    fun localBluetoothAddress(context: Context): String? {
        // 掩码值（02:00:00:00:00:00）当没读到：安卓 6.0 起普通应用问适配器要地址多半只拿到它，不能因此跳过后面的来源。
        val masked = BluetoothAddress.UNAVAILABLE
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.address
        } catch (e: SecurityException) {
            null // 安卓 12+ 没有 BLUETOOTH_CONNECT 运行时权限。
        } catch (e: RuntimeException) {
            null
        }
        if (adapter != null && adapter.isNotBlank() && adapter != masked) return adapter
        return try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")?.takeIf { it.isNotBlank() }
        } catch (e: RuntimeException) {
            null // 厂商 ACL 拦读（华为 HwSettingsAcl）。
        }
    }

    /** 默认屏幕（车机主屏）的实际像素、DPI 和按刷新率算出的帧率。 */
    fun screen(context: Context): ScreenSpec {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        val size = Point()
        if (display != null) {
            // getRealSize/getRealMetrics 在安卓 12 被标为过时（新接口要界面 Context），但仍然返回整块屏幕的实际像素，
            // 应用 Context 上也能用。
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        if (size.x <= 0 || size.y <= 0) {
            val fallback = context.resources.displayMetrics
            size.set(fallback.widthPixels, fallback.heightPixels)
            metrics.densityDpi = fallback.densityDpi
        }
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        // 车机投屏画布一律按横屏（宽 >= 高）声明：避免用手机当测试车机（如 Magic 2）时，点开始或弹 USB 授权框瞬间
        // 处于竖屏（1080x2340），导致向华为手机声明了竖屏画布，转回横屏后画面变成中间一条竖屏。
        val width = maxOf(size.x, size.y)
        val height = minOf(size.x, size.y)
        return ScreenSpec(width, height, dpi, DisplayRefreshRate.declaredFps(context))
    }
}
