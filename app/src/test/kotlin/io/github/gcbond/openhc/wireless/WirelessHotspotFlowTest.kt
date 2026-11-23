package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.WifiNetwork
import io.github.gcbond.openhc.core.session.WirelessAction
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.platform.wifi.HotspotFrequencySource
import io.github.gcbond.openhc.platform.wifi.HotspotMacSource
import io.github.gcbond.openhc.platform.wifi.HotspotSecurity
import io.github.gcbond.openhc.platform.wifi.LocalHotspot
import io.github.gcbond.openhc.platform.wifi.LocalHotspotEvents
import io.github.gcbond.openhc.platform.wifi.LocalHotspotHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 车机开热点在应用这一层：界面状态怎么走（热点在握手、认证途中开好，不打断当前这一步），平台执行器怎么接本地热点。
 * 测试数据都是合成值。
 */
class WirelessHotspotFlowTest {

    // ---------------------------------------------------------------- 界面状态

    private fun run(vararg signals: WirelessSignal, from: WirelessUiState = WirelessUiState()): WirelessUiState =
        signals.fold(from) { state, signal -> WirelessReducer.reduce(state, signal) }

    private fun wireless(event: WirelessEvent) = WirelessSignal.Wireless(event)
    private fun session(event: SessionEvent) = WirelessSignal.Session(event)

    private val qr = "hicar://car/connect?devicename=OpenHC-Test&mac=0A:1B:2C:3D:4E:5F&moduleid=000000&submoduleid=00&pin=135790&ability=6"

    @Test
    fun hotspotReadyBeforeNegotiationGoesStraightToSending() {
        var state = run(
            WirelessSignal.AttemptStarted(1),
            wireless(WirelessEvent.PairingReady(qr, "135790")),
        )
        assertEquals(WirelessPhase.WAITING_FOR_SCAN, state.phase)
        state = run(
            wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")),
            wireless(WirelessEvent.BluetoothHandshakeCompleted(true)),
            session(SessionEvent.AuthStarted(AuthMode.TRUSTED_RECONNECT)),
            session(SessionEvent.Authenticated(AuthMode.TRUSTED_RECONNECT)),
            from = state,
        )
        assertEquals(WirelessStep.NEGOTIATING_WIFI, state.step)
        // 认证通过就开始开热点，常在手机发协商请求之前开好：界面还停在「商量 WiFi」。
        state = run(wireless(WirelessEvent.WifiNetworkReady(2437)), from = state)
        assertEquals(WirelessStep.NEGOTIATING_WIFI, state.step)
        // 协商完：热点早已开好，直接到「发网络信息」。
        state = run(wireless(WirelessEvent.DataChannelNegotiated(2437)), from = state)
        assertEquals(WirelessStep.SENDING_NETWORK, state.step)
        state = run(wireless(WirelessEvent.ConnectionInfoSent(41000, "02:aa:bb:cc:dd:ee", 6)), from = state)
        assertEquals(WirelessStep.WAITING_PHONE_JOIN, state.step)
    }

    @Test
    fun aNetworkReadyEventNeverHijacksAnEarlierStep() {
        // 热点开好不打断当前这一步（例如还在认证）。
        val state = run(
            WirelessSignal.AttemptStarted(1),
            wireless(WirelessEvent.PairingReady(qr, "135790")),
            wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")),
            session(SessionEvent.AuthStarted(AuthMode.PIN_PAIRING)),
            wireless(WirelessEvent.WifiNetworkReady(2437)),
        )
        assertEquals(WirelessStep.AUTH_PAIRING, state.step)
        assertEquals(WirelessStep.SENDING_NETWORK, run(wireless(WirelessEvent.DataChannelNegotiated(2437)), from = state).step)
    }

    // ---------------------------------------------------------------- 平台执行器

    private class FakeHotspot(val events: LocalHotspotEvents) : LocalHotspotHandle {
        val calls = ArrayList<String>()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private class Results : WirelessResults {
        val lines = ArrayList<String>()
        var network: WifiNetwork? = null

        override fun onListening(id: Int, port: Int) {
            lines += "listening $id $port"
        }

        override fun onAccepted(id: Int, remote: String) {
            lines += "accepted $id"
        }

        override fun onConnected(id: Int) {
            lines += "connected $id"
        }

        override fun onData(id: Int, data: ByteArray) {
            lines += "data $id"
        }

        override fun onClosed(id: Int) {
            lines += "closed $id"
        }

        override fun onIoError(id: Int, message: String) {
            lines += "error $id $message"
        }

        override fun onWifiNetworkReady(id: Int, network: WifiNetwork) {
            lines += "ready $id"
            this.network = network
        }
    }

    private class Harness {
        val hotspots = ArrayList<FakeHotspot>()
        val results = Results()
        val logs = ArrayList<String>()
        val executor = WirelessPlatformExecutor(
            openRfcomm = { _, _, _ -> error("这些测试不开蓝牙") },
            dialRfcomm = { _, _, _, _ -> error("这些测试不开蓝牙") },
            startHotspot = { events -> FakeHotspot(events).also { hotspots += it } },
            results = results,
            onEvent = { },
            log = { logs += it },
        )
    }

    private fun hotspot(frequency: Int = 2437, source: HotspotFrequencySource = HotspotFrequencySource.ASSUMED_DEFAULT) = LocalHotspot(
        localIp = "192.168.43.1",
        networkName = "OpenHC-Test",
        bssid = "02:12:34:56:78:9a",
        passphrase = "abcd1234",
        frequencyMhz = frequency,
        interfaceName = "wlan0",
        bssidSource = HotspotMacSource.INTERFACE,
        frequencySource = source,
        security = HotspotSecurity.OK,
    )

    @Test
    fun hotspotIsStartedAndItsActualValuesGoBackToTheSession() {
        val h = Harness()
        h.executor.execute(WirelessAction.StartWifiNetwork(9))
        val run = h.hotspots.single()
        assertEquals(listOf("start"), run.calls)
        run.events.onReady(hotspot())
        assertEquals(listOf("ready 9"), h.results.lines)
        val net = h.results.network!!
        assertEquals("192.168.43.1", net.localIp)
        assertEquals("OpenHC-Test", net.ssid)
        assertEquals("02:12:34:56:78:9a", net.bssid)
        assertEquals("abcd1234", net.passphrase)
        assertEquals(2437, net.frequencyMhz)
        assertTrue(h.logs.any { "推测值" in it }, "推测的频率要在日志里说清")

        // 连接串发出后，日志说明热点方式以 TCP 连入为准，并写明端口后那一段的信道号和它的来源。
        h.executor.execute(WirelessEvent.ConnectionInfoSent(41000, "02:aa:bb:cc:dd:ee", channel = 6))
        assertTrue(h.logs[h.logs.size - 2].contains("车机热点"))
        assertTrue(h.logs.last().contains("信道号 6"), h.logs.last())
        assertTrue(h.logs.last().contains("2437 MHz"), h.logs.last())
        assertTrue(h.logs.last().contains(HotspotFrequencySource.ASSUMED_DEFAULT.label), h.logs.last())
        assertTrue(h.logs.none { it.contains("bb:cc:dd") }, "日志里的 MAC 要打码")

        // 会话拆网：关热点；之后热点那边再来的结果不交回。
        h.executor.execute(WirelessAction.StopWifiNetwork(9))
        assertEquals(listOf("start", "stop"), run.calls)
        assertEquals("关掉热点", h.logs.last())
        run.events.onLost("合成：关掉以后")
        assertEquals(listOf("ready 9"), h.results.lines)
    }

    @Test
    fun hotspotFailureAndLossGoBackUnderTheSessionsId() {
        val h = Harness()
        h.executor.execute(WirelessAction.StartWifiNetwork(4))
        h.hotspots.single().events.onFailed("合成：系统拒绝")
        assertEquals(listOf("error 4 合成：系统拒绝"), h.results.lines)

        h.executor.execute(WirelessAction.StartWifiNetwork(5))
        val second = h.hotspots[1]
        second.events.onReady(hotspot(5180, HotspotFrequencySource.CONFIGURED))
        second.events.onLost("合成：热点被收回")
        assertEquals(listOf("error 4 合成：系统拒绝", "ready 5", "closed 5"), h.results.lines)
        assertTrue(h.logs.none { "推测值" in it }, "系统写明的信道不是推测")
    }

    @Test
    fun closeAllStopsTheHotspotAndRefusesLaterOnes() {
        val h = Harness()
        h.executor.execute(WirelessAction.StartWifiNetwork(3))
        h.executor.closeAll()
        assertEquals(listOf("start", "stop"), h.hotspots.single().calls)
        assertTrue(h.logs.any { "1 个热点" in it })
        h.executor.execute(WirelessAction.StartWifiNetwork(6))
        assertEquals(listOf("stop"), h.hotspots[1].calls, "收尾之后不再开新热点")
    }
}
