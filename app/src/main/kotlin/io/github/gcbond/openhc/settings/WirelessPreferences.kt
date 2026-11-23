package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 无线连接方式与自动连接手机的设置（单独一个 SharedPreferences 文件）。开关切换立即生效。
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
     * 自动连接手机（默认开；存储的键沿用旧名「蓝牙连上自动连接」）：手机和车机的经典蓝牙一连上（打电话、听歌用的那种连接），
     * 或者打开本应用时（含开机自动打开），车机主动去拨用无线连成功过的手机（[knownPhones]）。本应用没在运行时由系统拉起来。
     */
    var connectOnBluetooth: Boolean
        get() = prefs.getBoolean(KEY_CONNECT_ON_BLUETOOTH, true)
        set(value) = prefs.edit { putBoolean(KEY_CONNECT_ON_BLUETOOTH, value) }

    /**
     * 无线方式选「WiFi 直连」（默认关，即车机开热点；试验）：手机认证通过后车机建 WiFi 直连组让手机加入、不开本地热点，
     * 优先 5 GHz。下一次无线连接生效（见 [usesWifiDirect]）。
     */
    var wifiDirect: Boolean
        get() = prefs.getBoolean(KEY_WIFI_DIRECT, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_DIRECT, value) }

    /** 下一次无线连接真的走 WiFi 直连：选了直连，而且系统支持（[wifiDirectSupported]）。 */
    val usesWifiDirect: Boolean get() = wifiDirect && wifiDirectSupported

    /** 用无线连成功过（投屏开始过）的手机的蓝牙地址（大写），最近连成功的在最后。「自动连接手机」只拨这些手机。 */
    val knownPhoneList: List<String>
        get() = prefs.getString(KEY_KNOWN_PHONES, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val knownPhones: Set<String> get() = knownPhoneList.toSet()

    /** 最近一次用无线连成功的手机（大写的蓝牙地址）：打开本应用时拨它。 */
    val lastPhone: String? get() = knownPhoneList.lastOrNull()

    /** 记下一台连成功的手机，当作最近那台；最多记 [MAX_KNOWN_PHONES] 台，多了丢掉最早的。返回这台手机是不是第一次记下。 */
    @Synchronized
    fun rememberPhone(address: String): Boolean {
        val key = address.trim().uppercase()
        val known = knownPhoneList
        val next = (known - key + key).takeLast(MAX_KNOWN_PHONES)
        prefs.edit { putString(KEY_KNOWN_PHONES, next.joinToString(",")) }
        return key !in known
    }

    /**
     * 旧设置「无线自动回连」：车机空闲时发 BLE 广播、开蓝牙监听，等手机自己连上来。已不再自动进入，设置页去掉了这一项，
     * 相关代码待删。
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
        private const val KEY_WIFI_DIRECT = "wifi_direct"
        private const val KEY_CONNECT_ON_BLUETOOTH = "connect_on_bluetooth"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_KNOWN_PHONES = "known_phones"

        /** 「连成功过的手机」最多记几台。 */
        const val MAX_KNOWN_PHONES = 5

        /**
         * 系统支持「WiFi 直连」这种无线方式：要按指定频率建组。安卓 10 起有公开接口；安卓 6～9 靠系统内部的设信道接口，
         * 有的车机不认，建组时会明说（见 WifiDirectGroupOwner）。所以 OpenHC 支持的系统（安卓 6 起）都算支持。
         */
        val wifiDirectSupported: Boolean get() = true
        private const val KEY_REPUBLISH_SECONDS = "debug_beacon_republish_seconds"
        private const val KEY_RANDOM_ADDRESS = "debug_beacon_random_address"
        private const val KEY_GATT = "debug_beacon_gatt"
    }
}
