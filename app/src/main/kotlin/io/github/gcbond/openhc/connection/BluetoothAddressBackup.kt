package io.github.gcbond.openhc.connection

import android.content.Context
import android.provider.Settings
import io.github.gcbond.openhc.settings.AppSettings

/**
 * 手填车机蓝牙地址的持久备份：存进系统全局设置表（`Settings.Global`，键 [KEY]）。
 *
 * 为什么要备份：手填地址记在本应用的 SharedPreferences 里，「清除数据」就没了；而地址一变，
 * 手机把车机当成另一台车，之前的配对记录对不上，要重新输入连接码。全局设置表归系统管
 * （/data/system 下的 settings_global.xml），清本应用数据、卸载重装都不动它，只有恢复出厂才清。
 *
 * 行为：手填保存时自动镜像进备份；清除时连备份一起清（车主明确不要了）；
 * 清过数据之后备份还在，设置页出现「从备份恢复」，要不要恢复车主自己选。
 *
 * 权限：`WRITE_SECURE_SETTINGS` 是 signature 权限，系统签名版拿得到；普通签名版写不进也读不到，
 * 这里的读写都静默失败（备份只是保险，不能影响正常功能）。adb 也能直接读写（shell 自带这个权限），
 * 应急找回：
 *
 * ```
 * adb shell settings get global openhc_bt_addr
 * adb shell settings put global openhc_bt_addr AA:BB:CC:DD:EE:FF
 * ```
 */
internal object BluetoothAddressBackup {
    const val KEY = "openhc_bt_addr"

    /** 手填地址保存后调，把值镜像进全局设置表。别在界面线程上调（写设置要跨进程）；失败静默。 */
    fun save(context: Context, address: String) {
        try {
            Settings.Global.putString(context.applicationContext.contentResolver, KEY, address)
        } catch (e: RuntimeException) {
            // 没有写权限（普通签名版）：跳过备份。
        }
    }

    /** 手填地址清除后调，备份也一起清掉（车主明确不要了）。别在界面线程上调；失败静默。 */
    fun clear(context: Context) {
        try {
            Settings.Global.putString(context.applicationContext.contentResolver, KEY, "")
        } catch (e: RuntimeException) {
            // 没有写权限（普通签名版）：跳过。
        }
    }

    /**
     * 读备份的手填地址；没有备份（没存过或清除过）或清过应用数据后备份读不到返回 null。
     * 别在界面线程上调（读设置要跨进程）；失败当没有。
     */
    fun saved(context: Context): String? = try {
        Settings.Global.getString(context.applicationContext.contentResolver, KEY)
            ?.takeIf { BluetoothAddressPolicy.normalize(it) != null }
    } catch (e: RuntimeException) {
        null // 没有读权限（普通签名版）：当没有备份。
    }

    /** 把备份的地址恢复回手填值（设置页「从备份恢复」用）。 */
    fun restore(context: Context, settings: AppSettings): String? {
        val normalized = BluetoothAddressPolicy.normalize(saved(context)) ?: return null
        settings.manualBluetoothAddress = normalized
        return normalized
    }
}
