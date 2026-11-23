package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 无线连接方式与自动回连的设置（单独一个 SharedPreferences 文件）。开关切换立即生效；几个调试项在下一次进入等待时生效。
 * 任意线程可读写。
 */
class WirelessPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 只用数据线连接（默认关）：开着时完全不做无线——不发 BLE 广播、不等手机回连、不理会手机蓝牙连上，
     * 「开始无线连接」也开始不了。只影响无线，有线照常。
     */
    var wiredOnly: Boolean
        get() = prefs.getBoolean(KEY_WIRED_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIRED_ONLY, value) }

    /**
     * 蓝牙连上自动连接（默认开）：手机和车机的经典蓝牙一连上（打电话、听歌用的那种连接），就进入「等已配对的手机回连」，
     * 本应用没在运行时由系统拉起来；已经在等的，换一个序列号重新发布一次广播，让手机再看一眼。
     * 和「无线自动回连」相互独立：那个关着时，蓝牙连上触发的这一轮等到手机蓝牙断开、用户停止或手机连上为止。
     */
    var connectOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_CONNECT_ON_BLUETOOTH, true)
        set(value) = prefs.edit { putBoolean(KEY_CONNECT_ON_BLUETOOTH, value) }

    /**
     * 无线自动回连（默认开）：有配对过的手机时，车机在空闲时发 BLE 广播、开蓝牙监听，等手机自己连上来，不用再扫码。
     * 开着时，用户点开始的无线连接也发广播。
     */
    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECONNECT, value) }

    /**
     * 调试：每隔多少秒换一个序列号重新发布广播，0 表示不重发。默认 120 秒：手机把序列号变了的广播当成新的发现，
     * 第一次没连上时会再处理一次；代价是旁边没配对过的手机每次都会再弹一次「发现车机」。
     */
    var republishSeconds: Int
        get() = prefs.getInt(KEY_REPUBLISH_SECONDS, DEFAULT_REPUBLISH_SECONDS).coerceIn(0, MAX_REPUBLISH_SECONDS)
        set(value) = prefs.edit { putInt(KEY_REPUBLISH_SECONDS, value.coerceIn(0, MAX_REPUBLISH_SECONDS)) }

    /**
     * 调试：广播里声明「车机用随机地址广播」（默认关）。只在确认车机的广播发送地址不是真实蓝牙地址时试，
     * 手机看到它以后怎么找车机没有验证。
     */
    var declareRandomAddress: Boolean
        get() = prefs.getBoolean(KEY_RANDOM_ADDRESS, false)
        set(value) = prefs.edit { putBoolean(KEY_RANDOM_ADDRESS, value) }

    /** 调试：和广播一起挂 GATT 服务（默认开）。实测的手机回连不用它；关掉用来对照。 */
    var gattServer: Boolean
        get() = prefs.getBoolean(KEY_GATT, true)
        set(value) = prefs.edit { putBoolean(KEY_GATT, value) }

    companion object {
        const val DEFAULT_REPUBLISH_SECONDS = 120
        const val MAX_REPUBLISH_SECONDS = 3600

        /** 设置页给的几个重新发布间隔（秒）。 */
        val REPUBLISH_CHOICES = intArrayOf(0, 30, 120, 600)

        private const val FILE = "openhc-wireless"
        private const val KEY_WIRED_ONLY = "wired_only"
        private const val KEY_CONNECT_ON_BLUETOOTH = "connect_on_bluetooth"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_REPUBLISH_SECONDS = "debug_beacon_republish_seconds"
        private const val KEY_RANDOM_ADDRESS = "debug_beacon_random_address"
        private const val KEY_GATT = "debug_beacon_gatt"
    }
}
