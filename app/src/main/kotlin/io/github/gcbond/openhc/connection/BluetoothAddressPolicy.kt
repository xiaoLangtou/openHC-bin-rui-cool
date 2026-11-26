package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.BluetoothAddress

/** 车机蓝牙地址取自哪里。 */
enum class BluetoothSource {
    /** 车机系统属性。 */
    SYSTEM_PROPERTY,

    /** 用户在设置里手填。 */
    MANUAL,

    /** 本机蓝牙适配器 / 系统设置里读到的（手机上跑时它是「车机」的蓝牙芯片本身）。 */
    LOCAL_ADAPTER,

    /** 都没有：按取不到处理（线上发 `02:00:00:00:00:00`，设备 ID 按退化规则算），有线照样能连。 */
    UNAVAILABLE,
}

/** 选定的车机蓝牙地址。[address] 是大写、冒号分隔的文本；取不到时为 null。 */
data class BluetoothChoice(val address: String?, val source: BluetoothSource)

/**
 * 车机蓝牙地址怎么定。纯逻辑。
 *
 * 安卓 6.0 起普通应用读不到本机蓝牙地址（系统只给 `02:00:00:00:00:00`）。有的车机系统把它放在一个普通应用可读的
 * 系统属性里（[SYSTEM_PROPERTY_KEYS]：部分车机写在 `persist.sys.bt_addr`，德赛西威一类车机写在
 * `persist.vehicle.bt.mac_addr`），按顺序取第一个有效的。用户在设置里手填了有效地址时优先用手填的
 * （车主可以借此覆盖系统的值，例如让本应用和车机上另一个投屏应用用不同的身份）；没填就用系统属性；都没有按取不到处理。
 *
 * 地址参与 carInfo、控制通道握手、认证身份和设备信息里的本机标识：**地址变了，手机会把车机当成另一台车**，
 * 之前的配对记录对不上，要重新输入连接码。
 */
object BluetoothAddressPolicy {
    /** 存车机蓝牙地址的系统属性，按顺序读，取第一个有效的。 */
    val SYSTEM_PROPERTY_KEYS = listOf("persist.sys.bt_addr", "persist.vehicle.bt.mac_addr")

    /** 几个系统属性读到的值（按 [SYSTEM_PROPERTY_KEYS] 的顺序）里第一个有效的地址；都无效返回 null。 */
    fun firstValid(values: List<String?>): String? = values.firstNotNullOfOrNull { normalize(it) }

    /** 全 0、全 F：属性没初始化或读错了。 */
    private val INVALID = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", BluetoothAddress.UNAVAILABLE)

    fun choose(systemProperty: String?, localAdapter: String?, manual: String?): BluetoothChoice {
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        normalize(localAdapter)?.let { return BluetoothChoice(it, BluetoothSource.LOCAL_ADAPTER) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /**
     * 无线连接用的地址：二维码里的地址手机要拿去直接连车机蓝牙，必须是真实地址。
     * 顺序：车机系统属性（车机上最可靠）→ 本机蓝牙适配器（手机上跑时它就是「车机」的蓝牙芯片，
     * 安卓 10 及更早或拿过 BLUETOOTH_CONNECT 的能读到真地址，读到的多半是 `02:00…` 掩码值会被 [normalize] 拒掉）→ 手填。
     */
    fun chooseForWireless(systemProperty: String?, localAdapter: String?, manual: String?): BluetoothChoice {
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        normalize(localAdapter)?.let { return BluetoothChoice(it, BluetoothSource.LOCAL_ADAPTER) }
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /** 第一个字节的「本地管理」位为 1：这类地址是软件编出来的，不会是蓝牙芯片的出厂地址。 */
    fun isLocallyAdministered(address: String): Boolean =
        BluetoothAddress.parse(address)[0].toInt() and 0x02 != 0

    /**
     * 整理成 `AA:BB:CC:DD:EE:FF`：接受冒号或 `-` 分隔、大小写，也接受不带分隔的 12 位十六进制；
     * 前后空白去掉。格式不对，或是全 0、全 F、`02:00:00:00:00:00` 这类占位值，返回 null。
     */
    fun normalize(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        val withSeparators = if (trimmed.length == 12 && trimmed.all { it.isAsciiHex() }) {
            trimmed.chunked(2).joinToString(":")
        } else {
            trimmed
        }
        val bytes = try {
            BluetoothAddress.parse(withSeparators)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val formatted = BluetoothAddress.format(bytes)
        return formatted.takeIf { it !in INVALID }
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
