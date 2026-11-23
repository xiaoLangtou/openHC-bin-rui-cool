package io.github.gcbond.openhc.platform.wifi

import kotlin.random.Random

/** 直连组主的 MAC（写进连接串的组主 MAC）取自哪里。 */
enum class DirectMacSource(val label: String) {
    INTERFACE("直连网卡的硬件地址"),
    GROUP_OWNER("系统组信息里的组主地址"),
    PLACEHOLDER("读不到真实地址，用占位值"),
}

/**
 * 车机建 WiFi 直连组的纯规则（不引用安卓类，JVM 单元测试覆盖）：按什么顺序试频率、组名和口令怎么生成、组主 MAC 取哪个。
 *
 * 选频顺序（参考 DiPlay 在比亚迪车机上的实测经验，见 [plan]）：
 * - 只给明确频率、不按频段建：有的比亚迪车机驱动按频段自己挑不了信道。
 * - 车机自己作为终端连着 WiFi 时，组尽量和它在同一个信道（同信道并发，无线电不用来回切）。
 * - 车机 WiFi 连在 5 GHz 时，不在另一个 5 GHz 信道上建：两个 5 GHz 信道来回切，DiPlay 在比亚迪唐上实测
 *   （车机 5200、组 5745）画面一卡 0.6～2 秒、音乐断粮；这时退到 2.4 GHz（和 5 GHz 并行不用切）。
 * - 其余情况先 5 GHz：投屏和手机、车机的蓝牙都挤在 2.4 GHz 上会卡（2026-11-22 实车，手机蓝牙刚连上时最明显）。
 *   5745（149）和 5180（36）不需要雷达避让。车机 WiFi 连在 2.4 GHz 时也先试 5 GHz（和 2.4 GHz 并行不用切），
 *   这一点和 DiPlay（先对齐 2.4 GHz）不同。
 */
object DirectGroupRules {
    /** 先试的 5 GHz 频率：149、36 信道。 */
    val FIVE_GHZ = listOf(5745, 5180)

    /** 2.4 GHz 频率：6、1、11 信道。 */
    val TWO_GHZ = listOf(2437, 2412, 2462)

    /** 车机 WiFi 在这些 5 GHz 频率上时，组建在同一频率（不需要雷达避让的信道）。 */
    private val ALIGNABLE_5G = setOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)

    /**
     * 依次尝试的频率（MHz），最后一个 null 表示不指定、交给系统自己定。[stationMhz] 是车机作为终端正连着的
     * WiFi 的频率，没连为 null 或 0。
     */
    fun plan(stationMhz: Int?): List<Int?> {
        val station = stationMhz?.takeIf { it > 0 }
        val out = LinkedHashSet<Int?>()
        when {
            station == null -> out += FIVE_GHZ
            isFiveGhz(station) -> if (station in ALIGNABLE_5G) out += station
            else -> {
                out += FIVE_GHZ
                if (isAlignable24(station)) out += station
            }
        }
        out += TWO_GHZ
        out += null
        return out.toList()
    }

    fun isFiveGhz(mhz: Int): Boolean = mhz in 5150..5895

    private fun isAlignable24(mhz: Int): Boolean = mhz in 2412..2462 && (mhz - 2412) % 5 == 0

    /** 组名：系统要求 `DIRECT-` 加两个字符开头，最长 32 字节。 */
    fun networkName(random: Random): String = "DIRECT-" + token(random, 2) + "-OpenHC"

    /** 口令：16 个字母数字（WPA2-PSK 要 8～63 个可打印 ASCII 字符）。 */
    fun passphrase(random: Random): String = token(random, 16)

    /**
     * 写进连接串的组主 MAC：直连网卡的硬件地址（手机按它认组主）；读不到时用系统组信息里的组主地址
     * （直连设备地址，和网卡地址可能不同）；都没有用占位值 [MacText.PLACEHOLDER]。
     */
    fun pickOwnerMac(interfaceMac: ByteArray?, ownerAddress: String?): Pair<String, DirectMacSource> {
        MacText.of(interfaceMac)?.let { return it to DirectMacSource.INTERFACE }
        MacText.normalize(ownerAddress)?.let { return it to DirectMacSource.GROUP_OWNER }
        return MacText.PLACEHOLDER to DirectMacSource.PLACEHOLDER
    }

    private fun token(random: Random, length: Int): String =
        buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
