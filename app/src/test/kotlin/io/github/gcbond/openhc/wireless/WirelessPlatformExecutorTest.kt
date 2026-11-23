package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WifiNetwork
import io.github.gcbond.openhc.core.session.WirelessAction
import io.github.gcbond.openhc.core.session.WirelessChannel
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.platform.bluetooth.RfcommClient
import io.github.gcbond.openhc.platform.bluetooth.RfcommEndpoint
import io.github.gcbond.openhc.platform.bluetooth.RfcommEvents
import io.github.gcbond.openhc.platform.wifi.LocalHotspotEvents
import io.github.gcbond.openhc.platform.wifi.LocalHotspotHandle
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * 平台动作执行器：会话的蓝牙、WiFi 动作接到（假的）平台对象上，平台结果按 id 交回。测试数据都是合成值。
 */
class WirelessPlatformExecutorTest {

    private val controlUuid = UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB")
    private val authUuid = UUID.fromString("0000FE36-0000-1000-8000-00805F9B34FB")

    private class FakeEndpoint(val name: String, val uuid: UUID, val events: RfcommEvents) : RfcommEndpoint {
        val calls = ArrayList<String>()
        val writes = ArrayList<ByteArray>()

        override fun start() {
            calls += "start"
        }

        override fun write(bytes: ByteArray) {
            writes += bytes
        }

        override fun closeListener() {
            calls += "closeListener"
        }

        override fun closeConnection() {
            calls += "closeConnection"
        }
    }

    /** 车机主动连手机（车机主动回连）的假连接：记下地址、UUID、通道号和调用。 */
    private class FakeDial(val address: String, val uuid: UUID, val channel: Int?, val events: RfcommClient.Events) : RfcommEndpoint {
        val calls = ArrayList<String>()
        val writes = ArrayList<ByteArray>()

        override fun start() {
            calls += "start"
        }

        override fun write(bytes: ByteArray) {
            writes += bytes
        }

        override fun closeListener() {
            calls += "closeListener"
        }

        override fun closeConnection() {
            calls += "closeConnection"
        }
    }

    private class FakeHotspot(val events: LocalHotspotEvents) : LocalHotspotHandle {
        val calls = ArrayList<String>()

        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    /** 记下交回会话的结果，一条一行。 */
    private class Results : WirelessResults {
        val lines = ArrayList<String>()
        var network: WifiNetwork? = null

        override fun onListening(id: Int, port: Int) {
            lines += "listening $id $port"
        }

        override fun onAccepted(id: Int, remote: String) {
            lines += "accepted $id $remote"
        }

        override fun onConnected(id: Int) {
            lines += "connected $id"
        }

        override fun onData(id: Int, data: ByteArray) {
            lines += "data $id ${String(data, Charsets.US_ASCII)}"
        }

        override fun onClosed(id: Int) {
            lines += "closed $id"
        }

        override fun onIoError(id: Int, message: String) {
            lines += "error $id"
        }

        override fun onWifiNetworkReady(id: Int, network: WifiNetwork) {
            lines += "ready $id"
            this.network = network
        }
    }

    private class Harness {
        val endpoints = ArrayList<FakeEndpoint>()
        val dials = ArrayList<FakeDial>()
        val hotspots = ArrayList<FakeHotspot>()
        val results = Results()
        val events = ArrayList<WirelessEvent>()
        val logs = ArrayList<String>()
        val executor = WirelessPlatformExecutor(
            openRfcomm = { name, uuid, events -> FakeEndpoint(name, uuid, events).also { endpoints += it } },
            dialRfcomm = { address, uuid, channel, events -> FakeDial(address, uuid, channel, events).also { dials += it } },
            startHotspot = { events -> FakeHotspot(events).also { hotspots += it } },
            results = results,
            onEvent = { events += it },
            log = { logs += it },
        )
    }

    private fun listen(id: Int, accept: Int, channel: WirelessChannel, uuid: UUID, needChannel: Boolean) =
        WirelessAction.ListenRfcomm(id, channel, uuid, accept, needChannel)

    @Test
    fun dialedConnectionsUseTheSessionsIdAndParameters() {
        val h = Harness()
        h.executor.execute(WirelessAction.ConnectRfcomm(7, WirelessChannel.BLUETOOTH_CONTROL, "02:66:77:88:99:AA", controlUuid, null))
        h.executor.execute(WirelessAction.ConnectRfcomm(8, WirelessChannel.BLUETOOTH_AUTH, "02:66:77:88:99:AA", authUuid, 8))
        val (control, auth) = h.dials
        assertEquals(listOf("02:66:77:88:99:AA", "02:66:77:88:99:AA"), h.dials.map { it.address })
        assertEquals(listOf(controlUuid, authUuid), h.dials.map { it.uuid })
        assertEquals(listOf(null, 8), h.dials.map { it.channel })
        assertEquals(listOf("start"), control.calls)
        assertTrue(h.endpoints.isEmpty(), "主动连不开监听")

        control.events.onConnected()
        control.events.onData("hs".toByteArray())
        auth.events.onConnectFailed("合成：连不上")
        control.events.onNote("合成：说明")
        assertEquals(listOf("connected 7", "data 7 hs", "error 8"), h.results.lines)
        assertTrue(h.logs.any { "合成：说明" in it })
        assertTrue(h.logs.none { "02:66:77:88:99:AA" in it }, "日志里地址要打码")

        // 写和关按连接 id 走；关掉之后迟到的回调丢掉。
        h.executor.execute(WirelessAction.RfcommWrite(7, byteArrayOf(1, 2)))
        assertEquals(1, control.writes.size)
        h.executor.execute(WirelessAction.RfcommClose(7))
        assertEquals(listOf("start", "closeConnection"), control.calls)
        control.events.onData("late".toByteArray())
        control.events.onClosed()
        assertEquals("error 8", h.results.lines.last())
    }

    @Test
    fun closeAllClosesDialedConnectionsAndLaterDialsDoNotStart() {
        val h = Harness()
        h.executor.execute(WirelessAction.ConnectRfcomm(7, WirelessChannel.BLUETOOTH_CONTROL, "02:66:77:88:99:AA", controlUuid, null))
        h.executor.closeAll()
        assertEquals(listOf("start", "closeListener", "closeConnection"), h.dials.single().calls)
        h.executor.execute(WirelessAction.ConnectRfcomm(9, WirelessChannel.BLUETOOTH_AUTH, "02:66:77:88:99:AA", authUuid, 8))
        assertEquals(listOf("closeConnection"), h.dials.last().calls, "收尾之后来的不再开始")
    }

    @Test
    fun rfcommListenerResultsComeBackUnderTheSessionsIds() {
        val h = Harness()
        h.executor.execute(listen(1, 2, WirelessChannel.BLUETOOTH_CONTROL, controlUuid, needChannel = false))
        val ep = h.endpoints.single()
        assertEquals(WirelessPlatformExecutor.CONTROL_SERVICE_NAME, ep.name)
        assertEquals(controlUuid, ep.uuid)
        assertEquals(listOf("start"), ep.calls)

        ep.events.onListening(8)
        ep.events.onAccepted("02:66:77:88:99:AA")
        ep.events.onData("hello".toByteArray())
        ep.events.onClosed()
        assertEquals(listOf("listening 1 8", "accepted 2 02:66:77:88:99:AA", "data 2 hello", "closed 2"), h.results.lines)
    }

    @Test
    fun anUnusableChannelNumberIsReportedAsZero() {
        val h = Harness()
        h.executor.execute(listen(5, 6, WirelessChannel.BLUETOOTH_AUTH, authUuid, needChannel = true))
        val ep = h.endpoints.single()
        assertEquals(WirelessPlatformExecutor.AUTH_SERVICE_NAME, ep.name)
        ep.events.onListening(-1)
        assertEquals(listOf("listening 5 0"), h.results.lines)
        assertTrue(h.logs.any { "拿不到通道号" in it })
    }

    @Test
    fun listenAndConnectionErrorsGoToTheirOwnIds() {
        val h = Harness()
        h.executor.execute(listen(1, 2, WirelessChannel.BLUETOOTH_CONTROL, controlUuid, needChannel = false))
        h.executor.execute(listen(3, 4, WirelessChannel.BLUETOOTH_AUTH, authUuid, needChannel = true))
        h.endpoints[0].events.onListenFailed("合成：开不了")
        h.endpoints[1].events.onAccepted("02:66:77:88:99:AA")
        h.endpoints[1].events.onConnectionError("合成：读失败")
        assertEquals(listOf("error 1", "accepted 4 02:66:77:88:99:AA", "error 4"), h.results.lines)
    }

    @Test
    fun writesAreCopiedAndRoutedByConnectionId() {
        val h = Harness()
        h.executor.execute(listen(1, 2, WirelessChannel.BLUETOOTH_CONTROL, controlUuid, needChannel = false))
        val frame = byteArrayOf(0x7F, 1, 2, 3)
        h.executor.execute(WirelessAction.RfcommWrite(2, frame))
        val written = h.endpoints.single().writes.single()
        assertContentEquals(frame, written)
        assertNotSame(frame, written, "平台层异步写：交出去的是副本")

        // 写到监听的 id、不存在的 id：丢掉。
        h.executor.execute(WirelessAction.RfcommWrite(1, frame))
        h.executor.execute(WirelessAction.RfcommWrite(99, frame))
        assertEquals(1, h.endpoints.single().writes.size)
    }

    @Test
    fun closingTheListenerKeepsTheConnectionAndLateEventsAreDropped() {
        val h = Harness()
        h.executor.execute(listen(1, 2, WirelessChannel.BLUETOOTH_CONTROL, controlUuid, needChannel = false))
        val ep = h.endpoints.single()
        ep.events.onListening(8)
        ep.events.onAccepted("02:66:77:88:99:AA")

        // 会话接受连接后就关监听：连接照常收数据。
        h.executor.execute(WirelessAction.RfcommClose(1))
        assertEquals(listOf("start", "closeListener"), ep.calls)
        ep.events.onData("abc".toByteArray())
        assertEquals("data 2 abc", h.results.lines.last())

        // 关了连接之后再来的数据不交回。
        h.executor.execute(WirelessAction.RfcommClose(2))
        assertEquals(listOf("start", "closeListener", "closeConnection"), ep.calls)
        val before = h.results.lines.size
        ep.events.onData("late".toByteArray())
        ep.events.onConnectionError("合成：关掉后的错误")
        assertEquals(before, h.results.lines.size)

        // 重复关、关不存在的 id：什么都不做。
        h.executor.execute(WirelessAction.RfcommClose(2))
        h.executor.execute(WirelessAction.RfcommClose(42))
        assertEquals(3, ep.calls.size)
    }

    @Test
    fun eventsArePassedOnAndThePhoneMacIsMasked() {
        val h = Harness()
        h.executor.execute(WirelessAction.StartWifiNetwork(7))
        val sent = WirelessEvent.ConnectionInfoSent(41000, "02:AA:BB:CC:DD:EE", 149)
        h.executor.execute(sent)
        assertEquals(listOf<WirelessEvent>(sent), h.events)
        assertTrue(h.logs.any { it.contains("02:**:**:**:DD:EE") }, h.logs.toString())
        assertTrue(h.logs.none { it.contains("BB:CC:DD", ignoreCase = true) }, "日志里的 MAC 要打码")
    }

    @Test
    fun closeAllShutsEverythingAndRefusesLaterActions() {
        val h = Harness()
        h.executor.execute(listen(1, 2, WirelessChannel.BLUETOOTH_CONTROL, controlUuid, needChannel = false))
        h.executor.execute(WirelessAction.StartWifiNetwork(3))
        h.executor.closeAll()
        assertEquals(listOf("start", "closeListener", "closeConnection"), h.endpoints.single().calls)
        assertEquals(listOf("start", "stop"), h.hotspots.single().calls)

        h.executor.execute(listen(4, 5, WirelessChannel.BLUETOOTH_AUTH, authUuid, needChannel = true))
        assertEquals(listOf("closeListener", "closeConnection"), h.endpoints.last().calls, "收尾之后不再开新监听")
        h.endpoints.last().events.onListening(9)
        assertTrue(h.results.lines.isEmpty())
    }
}
