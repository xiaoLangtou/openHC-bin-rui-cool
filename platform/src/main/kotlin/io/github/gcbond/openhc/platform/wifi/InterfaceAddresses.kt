package io.github.gcbond.openhc.platform.wifi

import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/** 读本机网卡上的地址。安卓 11 起普通应用读不到网卡硬件地址，但网卡上的 IP 地址还读得到。 */
internal object InterfaceAddresses {
    /**
     * 网卡 [name] 上按 EUI-64 生成的 IPv6 链路本地地址反推出的 MAC（[MacText.fromLinkLocal]）；网卡不在、没有这种地址
     * （例如按隐私规则随机生成）返回 null。热点、直连网卡的链路本地地址多数按网卡 MAC 生成（2026-11 比亚迪安卓 10 实车核对一致），
     * 车机自己连 WiFi 的网卡则多半按隐私规则生成，推不出。
     */
    fun linkLocalMac(name: String): String? = try {
        NetworkInterface.getByName(name)?.inetAddresses?.let { Collections.list(it) }
            ?.filterIsInstance<Inet6Address>()
            ?.filter { it.isLinkLocalAddress }
            ?.firstNotNullOfOrNull { MacText.fromLinkLocal(it.address) }
    } catch (e: SocketException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** 两种来源都有时对一下，写进日志：一致说明反推可靠。 */
    fun crossCheck(hardware: String?, linkLocal: String?): String? = when {
        hardware == null || linkLocal == null -> null
        hardware == linkLocal -> "IPv6 链路本地地址反推的 MAC 和硬件地址一致"
        else -> "IPv6 链路本地地址反推的 MAC（${MacText.mask(linkLocal)}）和硬件地址（${MacText.mask(hardware)}）不一致"
    }
}
