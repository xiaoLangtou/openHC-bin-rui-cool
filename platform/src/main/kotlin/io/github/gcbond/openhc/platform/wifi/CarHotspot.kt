package io.github.gcbond.openhc.platform.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/** 车主在设置里填的车机自带热点：名称、密码、频段。toString 不含名称和密码。 */
class CarHotspotSettings(val networkName: String, val passphrase: String, val band: WifiBand) {
    override fun toString() = "CarHotspotSettings(${band.label}，网络名与密码不显示)"
}

/**
 * 用车机自带的热点（车主在车机设置里打开、在 OpenHC 里填名称和密码）给手机加入，不开本地热点。用法同 [LocalHotspotHandle]。
 *
 * 普通应用开不了、也读不到系统的热点配置，所以：
 * - 热点要车主自己先开着。[start] 后在 [FIND_WAIT_MS] 内找开着的热点网卡（[LocalHotspotRules.pickInterface]），找不到就明说；
 * - 名称、密码用车主填的；BSSID 读热点网卡的硬件地址，读不到（安卓 11 起）由它的 IPv6 链路本地地址反推
 *   （[LocalHotspotRules.pickBssid]）；手机按 BSSID 找网，地址不对不加入；
 * - 频率按车主选的频段报常用信道（[LocalHotspotRules.estimateFrequency]）：同一频段内信道报错了手机还能自己找到（2026-11 实车）。
 * - 热点是车主的，[stop] 不关它；开着期间每 [WATCH_MS] 看一次网卡上的地址还在不在，没了报 [LocalHotspotEvents.onLost]。
 *
 * 车机自带热点能开在 5 GHz（本地热点在不少车机上只能 2.4 GHz），开着时系统不扫描 WiFi，投屏不容易被打断。
 * 线程：自己的工作线程；[LocalHotspotEvents] 在这条线程上回调。
 */
class CarHotspot(
    context: Context,
    private val settings: CarHotspotSettings,
    private val events: LocalHotspotEvents,
    private val log: (String) -> Unit,
) : LocalHotspotHandle {
    private val app: Context = context.applicationContext ?: context
    private val thread = HandlerThread("OpenHC-CarHotspot").also { it.start() }
    private val handler = Handler(thread.looper)

    // 以下只在工作线程上访问。
    private var stopped = false
    private var deadline = 0L
    private var ready: LocalHotspot? = null
    private val find = Runnable { find() }
    private val watch = Runnable { watch() }

    override fun start() {
        handler.post {
            deadline = now() + FIND_WAIT_MS
            log("用车机自带热点（$settings）：找开着的热点网卡")
            find()
        }
    }

    /** 停止并退出工作线程。没找到热点、热点丢了时自己已经置了 [stopped]，这里照样要退线程。 */
    override fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }

    private fun find() {
        if (stopped || ready != null) return
        val picked = LocalHotspotRules.pickInterface(emptyMap(), InterfaceAddresses.ipv4Snapshot(), stationIpv4())
        if (picked == null) {
            if (now() < deadline) {
                handler.postDelayed(find, POLL_MS)
                return
            }
            stopped = true
            events.onFailed("没找到车机自带热点：请先在车机设置里打开热点，再连接（等了 ${FIND_WAIT_MS / 1000} 秒）")
            return
        }
        val (iface, ip) = picked
        val hardware = InterfaceAddresses.hardwareAddress(iface)
        val linkLocal = InterfaceAddresses.linkLocalMac(iface)
        val (bssid, macSource) = LocalHotspotRules.pickBssid(null, hardware, null, linkLocal)
        log(
            "热点网卡 $iface（$ip）：硬件地址 ${MacText.mask(MacText.of(hardware) ?: "读不到")}，" +
                "由 IPv6 链路本地地址反推 ${MacText.mask(linkLocal ?: "推不出")}；BSSID 用 ${macSource.label}",
        )
        InterfaceAddresses.crossCheck(MacText.of(hardware), linkLocal)?.let(log)
        if (macSource == HotspotMacSource.PLACEHOLDER) log("读不到热点的真实地址，连接串里用占位值：手机认不出这个热点，多半连不上")
        val (frequency, frequencySource) = LocalHotspotRules.estimateFrequency(HotspotBandHint(settings.band, 0))
        val hotspot = LocalHotspot(
            ip, settings.networkName, bssid, settings.passphrase, frequency, iface, macSource, frequencySource, HotspotSecurity.OK,
        )
        ready = hotspot
        log("车机自带热点就绪：$hotspot；频率：${frequencySource.label}")
        events.onReady(hotspot)
        handler.postDelayed(watch, WATCH_MS)
    }

    private fun watch() {
        val current = ready ?: return
        if (stopped) return
        val addresses = InterfaceAddresses.ipv4Snapshot()[current.interfaceName].orEmpty()
        if (current.localIp !in addresses) {
            stopped = true
            events.onLost("车机自带热点关了（网卡 ${current.interfaceName} 上没有地址了）")
            return
        }
        handler.postDelayed(watch, WATCH_MS)
    }

    /** 车机作为终端连着别的 WiFi 时的 IPv4：选热点网卡时排除它。 */
    @Suppress("DEPRECATION")
    private fun stationIpv4(): String? = try {
        val ip = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "${ip and 0xFF}.${ip ushr 8 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 24 and 0xFF}"
    } catch (e: RuntimeException) {
        null
    }

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        /** 车主刚打开热点时网卡可能还没地址，最多等这么久。 */
        const val FIND_WAIT_MS = 5_000L
        const val POLL_MS = 500L
        const val WATCH_MS = 2_000L
    }
}
