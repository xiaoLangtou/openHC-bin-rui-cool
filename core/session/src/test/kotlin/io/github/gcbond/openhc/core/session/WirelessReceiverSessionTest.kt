package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.nearby.WifiMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用假的层和假的平台结果驱动无线编排器：交接顺序、各步的拒绝与超时、结束时的关闭顺序。 */
class WirelessReceiverSessionTest {

    private class Driver(timeouts: WirelessTimeouts = WirelessTimeouts()) {
        val layers = FakeWirelessLayers()
        val config = WirelessSynthetic.config(timeouts)
        val session = WirelessReceiverSession(config, AccessUnitSink { }, layers)
        var now = 1_000L
        val rfcomm = HashMap<WirelessChannel, WirelessAction.ListenRfcomm>()
        val rfcommConnects = HashMap<WirelessChannel, WirelessAction.ConnectRfcomm>()
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        val connects = HashMap<SessionChannel, SessionAction.Connect>()
        var network: WirelessAction.StartWifiNetwork? = null
        val outputs = ArrayList<WirelessOutput>()

        /** 每个动作分配到的 id，用来核对蓝牙、WiFi 和 TCP 的 id 不重复。 */
        val ids = ArrayList<Int>()
        private val names = HashMap<Int, String>()

        fun record(list: List<WirelessOutput>): List<String> {
            for (o in list) {
                outputs += o
                when (o) {
                    is WirelessAction.ListenRfcomm -> {
                        rfcomm[o.channel] = o
                        name(o.id, "${o.channel}.listener")
                        name(o.acceptId, o.channel.name)
                    }
                    is WirelessAction.ConnectRfcomm -> {
                        rfcommConnects[o.channel] = o
                        name(o.id, o.channel.name)
                    }
                    is WirelessAction.StartWifiNetwork -> {
                        network = o
                        name(o.id, "network")
                    }
                    is WirelessOutput.Common -> when (val c = o.output) {
                        is SessionAction.Listen -> {
                            listens[c.channel] = c
                            name(c.id, "${c.channel}.listener")
                            name(c.acceptId, c.channel.name)
                        }
                        is SessionAction.Connect -> {
                            connects[c.channel] = c
                            name(c.id, c.channel.name)
                        }
                        is SessionAction.OpenUdp -> name(c.id, c.channel.name)
                        else -> Unit
                    }
                    else -> Unit
                }
            }
            return list.map { describe(it) }
        }

        private fun name(id: Int, text: String) {
            ids += id
            names[id] = text
        }

        fun describe(o: WirelessOutput): String = when (o) {
            is WirelessOutput.Common -> when (val c = o.output) {
                is SessionAction.Listen -> "listen ${c.channel} ${c.host}:${c.port}"
                is SessionAction.Connect -> "connect ${c.channel} ${c.host}:${c.port}"
                is SessionAction.Write -> "write ${names[c.id]} ${c.bytes.text()}"
                is SessionAction.Close -> "close ${names[c.id] ?: c.id}"
                is SessionAction.OpenUdp -> "udp ${c.localHost} -> ${c.remoteHost}:${c.remotePort}"
                is SessionAction.BindUdp -> "bind-udp ${c.channel} ${c.localHost}:${c.localPort}"
                is SessionAction.SendDatagram -> "datagram ${c.bytes.text()}"
                is SessionEvent.Diagnostic -> "diag"
                is SessionEvent -> "event $c"
            }
            is WirelessAction.ListenRfcomm -> "rfcomm-listen ${o.channel} ${o.uuid}"
            is WirelessAction.ConnectRfcomm -> "rfcomm-connect ${o.channel} ${o.uuid} ${o.rfcommChannel?.let { "通道 $it" } ?: "按 SDP"}"
            is WirelessAction.RfcommWrite -> "rfcomm-write ${names[o.id]} ${o.bytes.text()}"
            is WirelessAction.RfcommClose -> "rfcomm-close ${names[o.id]}"
            is WirelessAction.StartWifiNetwork -> "wifi-start"
            is WirelessAction.StopWifiNetwork -> "wifi-stop"
            is WirelessEvent -> "event $o"
        }

        fun start() = record(session.start(now))
        fun btListening(channel: WirelessChannel, port: Int) = record(session.onListening(rfcomm.getValue(channel).id, port, now))
        fun btAccepted(channel: WirelessChannel) =
            record(session.onAccepted(rfcomm.getValue(channel).acceptId, WirelessSynthetic.PHONE_BLUETOOTH, now))
        /** 蓝牙连接的 id：车机主动连的用连接动作的 id，监听接受的用监听给的 id。 */
        fun btId(channel: WirelessChannel): Int = rfcommConnects[channel]?.id ?: rfcomm.getValue(channel).acceptId
        fun btData(channel: WirelessChannel, text: String): List<String> {
            val bytes = "xx$text".bytes()
            return record(session.onData(btId(channel), bytes, 2, bytes.size - 2, now))
        }
        fun btClosed(channel: WirelessChannel) = record(session.onClosed(btId(channel), now))
        fun btError(channel: WirelessChannel) = record(session.onIoError(btId(channel), "测试错误", now))
        fun startReconnect(name: String = "Phone") = record(session.startReconnect(WirelessSynthetic.PHONE_BLUETOOTH, name, now))
        fun startWithDial(name: String = "Phone") = record(session.startWithDial(WirelessSynthetic.PHONE_BLUETOOTH, name, now))

        /** 扫码监听接受的那条控制连接（[startWithDial] 时监听和拨号同在一个通道上，[btId] 指向拨号那条）。 */
        fun acceptedControlId(): Int = rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL).acceptId
        fun dataOnAccepted(text: String): List<String> {
            val bytes = text.bytes()
            return record(session.onData(acceptedControlId(), bytes, 0, bytes.size, now))
        }
        fun btConnected(channel: WirelessChannel) = record(session.onConnected(rfcommConnects.getValue(channel).id, now))
        fun btConnectError(channel: WirelessChannel) = record(session.onIoError(rfcommConnects.getValue(channel).id, "测试错误", now))

        /** 主动回连到手机同意开认证子通道、车机连上它为止。 */
        fun reconnectToAuthChannel() {
            startReconnect()
            btConnected(WirelessChannel.BLUETOOTH_CONTROL)
            btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
            btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-ok")
            btConnected(WirelessChannel.BLUETOOTH_AUTH)
        }
        fun networkReady(net: WifiNetwork) = record(session.onWifiNetworkReady(network!!.id, net, now))
        fun networkError() = record(session.onIoError(network!!.id, "测试错误", now))
        fun networkLost() = record(session.onClosed(network!!.id, now))
        fun listening(channel: SessionChannel, port: Int) = record(session.onListening(listens.getValue(channel).id, port, now))
        fun accepted(channel: SessionChannel, remote: String = WirelessSynthetic.PHONE_WIFI_IP) =
            record(session.onAccepted(listens.getValue(channel).acceptId, remote, now))
        fun idOf(channel: SessionChannel): Int = listens[channel]?.acceptId ?: connects.getValue(channel).id
        fun data(channel: SessionChannel, text: String): List<String> {
            val bytes = text.bytes()
            return record(session.onData(idOf(channel), bytes, 0, bytes.size, now))
        }
        fun closed(channel: SessionChannel) = record(session.onClosed(idOf(channel), now))
        fun tick(at: Long) = record(session.onTick(at))
        fun stop() = record(session.stop(now))

        fun hotspotNetwork(frequency: Int = 5745) = WirelessSynthetic.hotspot(frequency)

        fun connectBluetooth() {
            btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
            btAccepted(WirelessChannel.BLUETOOTH_CONTROL)
        }

        fun authenticate() {
            btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
            btListening(WirelessChannel.BLUETOOTH_AUTH, 5)
            btAccepted(WirelessChannel.BLUETOOTH_AUTH)
            btData(WirelessChannel.BLUETOOTH_AUTH, "hello")
            btData(WirelessChannel.BLUETOOTH_AUTH, "done")
        }

        /** 到认证通过为止：这时热点已经在开（还没开好）。 */
        fun toAuthenticated() {
            start()
            connectBluetooth()
            authenticate()
        }

        /** 热点开好、协商完：内嵌的有线编排器已经在热点地址上开控制监听。 */
        fun toWifiListening(frequency: Int = 5745) {
            toAuthenticated()
            networkReady(hotspotNetwork(frequency))
            btData(WirelessChannel.BLUETOOTH_CONTROL, "nego")
            btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm")
        }

        fun toWifiConnected() {
            toWifiListening()
            listening(SessionChannel.CONTROL, 40100)
            btData(WirelessChannel.BLUETOOTH_CONTROL, "mac")
            accepted(SessionChannel.CONTROL)
        }

        fun ended(): WirelessEndReason = assertIs<WirelessEvent.Ended>(outputs.last()).reason
    }

    private val controlUuid = NearbySession.BLUETOOTH_SERVICE_UUID.toString()
    private val subchannelUuid = NearbySession.DEFAULT_SUBCHANNEL_UUID.toString()

    @Test
    fun handoffOrder() {
        val d = Driver()
        // 蓝牙：开监听 → 显示二维码 → 手机连上 → 控制握手 → 认证子通道 → 认证。这些步骤里都不开热点。
        assertEquals(listOf("rfcomm-listen BLUETOOTH_CONTROL $controlUuid"), d.start())
        assertTrue(d.rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL).let { !it.needChannelNumber })
        assertEquals(listOf("event PairingReady(内容不显示)"), d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0))
        val pairing = assertIs<WirelessEvent.PairingReady>(d.outputs.last())
        assertEquals(WirelessSynthetic.PIN, pairing.pinCode)
        assertEquals(d.config.qrCode(WirelessSynthetic.PIN), pairing.qrCode)
        // 控制通道的监听这时不关：关了它的通道号就空出来，接着开的认证子通道监听会分到同一个号，手机连不上（真车实测）。
        assertEquals(
            listOf(
                "event PhoneBluetoothConnected(地址不显示)",
                "rfcomm-write BLUETOOTH_CONTROL bt-connect-id",
            ),
            d.btAccepted(WirelessChannel.BLUETOOTH_CONTROL),
        )
        assertEquals(
            listOf("event BluetoothHandshakeCompleted(加密=true)", "rfcomm-listen BLUETOOTH_AUTH $subchannelUuid"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello"),
        )
        assertTrue(d.rfcomm.getValue(WirelessChannel.BLUETOOTH_AUTH).needChannelNumber)
        // 拿到通道号再回给手机（先监听、后通告）。
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL rfcomm:5"), d.btListening(WirelessChannel.BLUETOOTH_AUTH, 5))
        // 认证子通道连上之后，两个监听一起关。
        assertEquals(
            listOf("rfcomm-close BLUETOOTH_AUTH.listener", "rfcomm-close BLUETOOTH_CONTROL.listener"),
            d.btAccepted(WirelessChannel.BLUETOOTH_AUTH),
        )
        assertEquals(WirelessSynthetic.PIN, d.layers.authPin)
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_AUTH hello-reply", "event AuthStarted(PIN_PAIRING)"),
            d.btData(WirelessChannel.BLUETOOTH_AUTH, "hello"),
        )
        assertNull(d.network)
        // 认证通过：这时才开热点（名称、密码、频率都由系统定）。
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_AUTH done-reply", "event Authenticated(PIN_PAIRING)", "wifi-start"),
            d.btData(WirelessChannel.BLUETOOTH_AUTH, "done"),
        )
        // 热点在手机发协商请求之前开好：只出事件。
        assertEquals(listOf("event WifiNetworkReady(2437 MHz)"), d.networkReady(d.hotspotNetwork(2437)))
        // 手机也支持 5G，但热点就在 2437：报热点的频率，车机当组主。
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL dc:2437"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        // 热点早已开好：确认之后不再建网，直接在热点地址上开随机端口的控制监听（内嵌的有线编排器）。
        assertEquals(
            listOf("event DataChannelNegotiated(2437 MHz)", "listen CONTROL ${WirelessSynthetic.HOTSPOT_IP}:0"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm"),
        )
        assertEquals(1, d.outputs.count { it is WirelessAction.StartWifiNetwork })
        assertEquals(WirelessSynthetic.HOTSPOT_IP, d.layers.wifiConfig!!.localIp)
        // 监听开好后才向手机要 MAC。
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL mac?"), d.listening(SessionChannel.CONTROL, 40100))
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_CONTROL info", "event ConnectionInfoSent(40100，信道 6)"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac"),
        )
        // 热点格式：网络段多一行加入标记 1，端口后多一段热点的信道号（2437 → 6）；手机地址取热点网段里的第一个空闲地址。
        assertEquals(
            "${WirelessSynthetic.PHONE_WIFI_IP}##OpenHC-5678\n0a:1b:2c:3d:4e:5f\n${WirelessSynthetic.HOTSPOT_PASSPHRASE}\n2437\n1" +
                "##version:3##40100##6##hicarApIp:${WirelessSynthetic.HOTSPOT_IP}",
            d.layers.bluetooth.lastInfo!!.encode(),
        )
        assertEquals(WifiMode.HOTSPOT, d.layers.bluetooth.lastInfo!!.mode)
        val sent = assertIs<WirelessEvent.ConnectionInfoSent>(d.outputs.last())
        assertEquals(WirelessSynthetic.PHONE_MAC, sent.phoneMac)
        assertEquals(6, sent.channel)

        // 手机连上热点上的控制端口：之后与有线相同，只是认证已做完、业务 20 在通道 3 上。
        assertEquals(
            listOf("event PhoneConnected(${WirelessSynthetic.PHONE_WIFI_IP})", "write CONTROL connect-id"),
            d.accepted(SessionChannel.CONTROL),
        )
        assertEquals(
            listOf("event ControlHandshakeCompleted(加密=true)", "listen DMSDP_SESSION ${WirelessSynthetic.HOTSPOT_IP}:0"),
            d.data(SessionChannel.CONTROL, "dmsdp?"),
        )
        assertEquals(listOf("write CONTROL accept:20:40200"), d.listening(SessionChannel.DMSDP_SESSION, 40200))
        assertEquals(emptyList(), d.accepted(SessionChannel.DMSDP_SESSION))
        assertContentEquals(Synthetic.KEY, d.layers.wifi.dmsdpKey)
        assertEquals(WirelessSynthetic.PHONE_WIFI_IP, d.layers.wifi.dmsdpPhoneIp)
        assertFalse("auth.create" in d.layers.wifi.calls)
        assertEquals(listOf("listen DMSDP_CONTROL ${WirelessSynthetic.HOTSPOT_IP}:0"), d.data(SessionChannel.DMSDP_SESSION, "nego"))
        d.listening(SessionChannel.DMSDP_CONTROL, 40300)
        d.accepted(SessionChannel.DMSDP_CONTROL)
        assertEquals(
            listOf(
                "write DMSDP_CONTROL crypto-ok",
                "udp ${WirelessSynthetic.HOTSPOT_IP} -> ${WirelessSynthetic.PHONE_WIFI_IP}:50000",
                "event DeviceSessionReady(UDP)",
            ),
            d.data(SessionChannel.DMSDP_CONTROL, "crypto"),
        )
        assertEquals(
            listOf("event ProjectionRequested(7000)", "connect HISIGHT_CONTROL ${WirelessSynthetic.PHONE_WIFI_IP}:7000"),
            d.data(SessionChannel.DMSDP_CONTROL, "display"),
        )
        assertContentEquals(Synthetic.KEY, d.layers.wifi.hisightKey)

        // 热点上的控制通道接上之后手机关掉两条蓝牙连接（关闭或读出错）是正常的。
        assertEquals(listOf("rfcomm-close BLUETOOTH_CONTROL", "diag"), d.btClosed(WirelessChannel.BLUETOOTH_CONTROL))
        assertEquals(listOf("rfcomm-close BLUETOOTH_AUTH", "diag"), d.btError(WirelessChannel.BLUETOOTH_AUTH))
        assertFalse(d.session.isEnded)

        // 本机结束：先由内嵌的有线编排器按它的顺序收尾，再关热点，最后 Ended。
        assertEquals(
            listOf(
                "close HISIGHT_CONTROL", "close KEEPALIVE", "close DMSDP_CONTROL", "close DMSDP_CONTROL.listener",
                "close DMSDP_SESSION", "close DMSDP_SESSION.listener", "close CONTROL", "close CONTROL.listener",
                "wifi-stop", "event Ended(本机停止)",
            ),
            d.stop(),
        )
        assertTrue(d.session.isEnded)
        assertNull(d.session.nextTickAtMillis)
        assertEquals(emptyList(), d.stop())
        // 蓝牙、WiFi 和 TCP 资源共用一套 id，不重复。
        assertEquals(d.ids.size, d.ids.toSet().size)
    }

    @Test
    fun noHotspotForAPhoneThatDoesNotAuthenticate() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        d.btListening(WirelessChannel.BLUETOOTH_AUTH, 5)
        d.btAccepted(WirelessChannel.BLUETOOTH_AUTH)
        d.btData(WirelessChannel.BLUETOOTH_AUTH, "hello")
        // 认证没做完手机就断了：从头到尾都不碰车机的 WiFi。
        d.btClosed(WirelessChannel.BLUETOOTH_AUTH)
        assertIs<WirelessEndReason.AuthFailed>(d.ended())
        assertTrue(d.outputs.none { it is WirelessAction.StartWifiNetwork || it is WirelessAction.StopWifiNetwork })
    }

    @Test
    fun noHotspotWhileNobodyConnects() {
        val d = Driver()
        d.start()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        // 一直没有手机：建网的计时也不会开始。
        assertNull(d.session.nextTickAtMillis)
        assertEquals(emptyList(), d.tick(d.now + 3_600_000))
        // 停掉时也没有要拆的网络（还没连上的那条蓝牙连接照常发关闭，平台当空操作）。
        assertEquals(
            listOf("rfcomm-close BLUETOOTH_CONTROL", "rfcomm-close BLUETOOTH_CONTROL.listener", "event Ended(本机停止)"),
            d.stop(),
        )
        assertTrue(d.outputs.none { it is WirelessAction.StartWifiNetwork || it is WirelessAction.StopWifiNetwork })
    }

    @Test
    fun hotspotNegotiationWaitsForHotspot() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.authenticate()
        assertEquals(listOf("diag"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        // 手机重发的请求也先留着，热点开好后只回一次。
        assertEquals(listOf("diag"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        assertEquals(
            listOf("event WifiNetworkReady(5745 MHz)", "rfcomm-write BLUETOOTH_CONTROL dc:5745"),
            d.networkReady(d.hotspotNetwork(5745)),
        )
        assertEquals(
            listOf("event DataChannelNegotiated(5745 MHz)", "listen CONTROL ${WirelessSynthetic.HOTSPOT_IP}:0"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm"),
        )
    }

    @Test
    fun hotspotFrequencyThePhoneCannotUseEndsTheSession() {
        val d = Driver()
        d.layers.bluetooth.capability = WirelessSynthetic.capability(channels = listOf(149))
        d.start()
        d.connectBluetooth()
        d.authenticate()
        d.networkReady(d.hotspotNetwork(5180))
        assertEquals(
            listOf(
                "diag", "rfcomm-write BLUETOOTH_CONTROL dc-refused:255",
                "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL", "wifi-stop",
                "event Ended(数据通道协商失败（热点在 5180 MHz，手机报的能力用不了）)",
            ),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"),
        )
    }

    @Test
    fun hotspotFailureBeforeNegotiationEndsTheSession() {
        val d = Driver()
        d.toAuthenticated()
        // 平台开不了热点（例如系统拒绝）：不等协商，关掉蓝牙连接、拆网（平台照样要收到停止），结束。
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL",
                "wifi-stop", "event Ended(WiFi 组网失败（建网失败：测试错误）)",
            ),
            d.networkError(),
        )
        assertEquals(WirelessStep.WIFI_NETWORK, assertIs<WirelessEndReason.Failed>(d.ended()).step)
    }

    @Test
    fun hotspotStartLimitCountsFromAuthentication() {
        val d = Driver(timeouts = WirelessTimeouts(networkStartMillis = 15_000))
        d.start()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        // 等了很久手机才来：计时从认证通过、开始开热点时算。
        d.now += 600_000
        d.btAccepted(WirelessChannel.BLUETOOTH_CONTROL)
        d.authenticate()
        assertEquals(d.now + 15_000, d.session.nextTickAtMillis)
        assertEquals(emptyList(), d.tick(d.now + 14_999))
        d.tick(d.now + 15_000)
        assertEquals("WiFi 组网失败（建网超时）", d.ended().toString())
    }

    @Test
    fun hotspotLostAfterThePhoneJoinedEndsTheSession() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.authenticate()
        d.networkReady(d.hotspotNetwork(2437))
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego")
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm")
        d.listening(SessionChannel.CONTROL, 40100)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac")
        d.accepted(SessionChannel.CONTROL, "192.168.88.37")
        // 热点被系统收回：网络没了就结束（照常发一次停止，平台对已经没了的热点当空操作）。
        val out = d.networkLost()
        assertEquals("event Ended(WiFi 组网失败（车机的 WiFi 网络没了）)", out.last())
        assertEquals(1, out.count { it == "wifi-stop" })
        assertEquals(emptyList(), d.stop())
    }

    @Test
    fun negotiationIsRefusedWhenNotPossible() {
        val early = Driver()
        early.start()
        early.connectBluetooth()
        // 认证完成之前：回忙。
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL dc-refused:2"), early.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        early.authenticate()
        // 要的不是 WiFi：回没有可用数据通道。
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL dc-refused:255"), early.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego-ble"))
        // 还没回应答就来了结果：忽略。
        assertEquals(listOf("diag"), early.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm"))
        assertFalse(early.session.isEnded)
    }

    @Test
    fun repeatedNegotiationGetsSameFrequency() {
        val d = Driver()
        d.toAuthenticated()
        d.networkReady(d.hotspotNetwork(5745))
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego")
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL dc:5745"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm")
        assertEquals(listOf("diag"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm"))
        assertEquals(1, d.outputs.count { it is WirelessAction.StartWifiNetwork })
        // 重复确认不会再开一次控制监听。
        assertEquals(1, d.outputs.count { (it as? WirelessOutput.Common)?.output is SessionAction.Listen })
    }

    @Test
    fun phoneRefusingTheChannelEndsTheSession() {
        val d = Driver()
        d.toAuthenticated()
        d.networkReady(d.hotspotNetwork())
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego")
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL", "wifi-stop",
                "event Ended(数据通道协商失败（手机忙（结果 2））)",
            ),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "refuse"),
        )
    }

    @Test
    fun phoneAnsweringBusyForMacEndsTheSession() {
        val d = Driver()
        d.toWifiListening()
        d.listening(SessionChannel.CONTROL, 40100)
        assertEquals(
            listOf(
                "close CONTROL", "close CONTROL.listener", "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL",
                "wifi-stop", "event Ended(手机加入 WiFi失败（手机回了忙）)",
            ),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "busy"),
        )
    }

    /** 平台建的是直连组：连接串用直连格式（没有加入标记 1、没有信道号段），事件里的信道号为 null。 */
    @Test
    fun directGroupUsesTheDirectFormat() {
        val d = Driver()
        d.toAuthenticated()
        assertEquals(listOf("event WifiNetworkReady(5745 MHz)"), d.networkReady(WirelessSynthetic.direct(5745)))
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL dc:5745"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm")
        d.listening(SessionChannel.CONTROL, 40100)
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_CONTROL info", "event ConnectionInfoSent(40100，直连)"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac"),
        )
        val info = d.layers.bluetooth.lastInfo!!
        assertEquals(WifiMode.DIRECT, info.mode)
        assertEquals(
            "192.168.49.2##DIRECT-ab-OpenHC\n0a:1b:2c:3d:4e:5f\n${WirelessSynthetic.HOTSPOT_PASSPHRASE}\n5745" +
                "##version:3##40100##hicarApIp:192.168.49.1",
            info.encode(),
        )
        assertNull(assertIs<WirelessEvent.ConnectionInfoSent>(d.outputs.last()).channel)
    }

    @Test
    fun macBeforeAskingIsIgnoredAndSentOnlyOnce() {
        val d = Driver()
        d.toWifiListening()
        assertEquals(listOf("diag"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac"))
        d.listening(SessionChannel.CONTROL, 40100)
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_CONTROL info", "event ConnectionInfoSent(40100，信道 149)"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac"),
        )
        assertEquals(listOf("diag"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "mac"))
    }

    @Test
    fun authSubchannelRequests() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        // 监听还没开好时的重复请求先留着，开好后一起回同一个通道号。
        assertEquals(emptyList(), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "auth?"))
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_CONTROL rfcomm:5", "rfcomm-write BLUETOOTH_CONTROL rfcomm:5"),
            d.btListening(WirelessChannel.BLUETOOTH_AUTH, 5),
        )
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL rfcomm:5"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "auth?"))
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL reject:20"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "other?"))
        d.btAccepted(WirelessChannel.BLUETOOTH_AUTH)
        // 手机已经连上认证子通道（监听已关）：再要就拒绝。
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL reject:19"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "auth?"))
        assertEquals(listOf("bt.acceptRfcomm(19,5)", "bt.acceptRfcomm(19,5)", "bt.acceptRfcomm(19,5)"), d.layers.calls.filter { it.startsWith("bt.acceptRfcomm") })
    }

    @Test
    fun authSubchannelWithoutChannelNumberEnds() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        assertEquals(
            listOf(
                "diag", "rfcomm-write BLUETOOTH_CONTROL reject:19",
                "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_AUTH.listener",
                "rfcomm-close BLUETOOTH_CONTROL", "rfcomm-close BLUETOOTH_CONTROL.listener",
                "event Ended(认证失败（认证子通道拿不到 RFCOMM 通道号）)",
            ),
            d.btListening(WirelessChannel.BLUETOOTH_AUTH, 0),
        )
    }

    @Test
    fun authSubchannelAcceptTimeout() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        d.btListening(WirelessChannel.BLUETOOTH_AUTH, 5)
        assertEquals(6_000L, d.session.nextTickAtMillis)
        assertEquals(emptyList(), d.tick(5_999))
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_AUTH.listener", "rfcomm-close BLUETOOTH_AUTH",
                "rfcomm-close BLUETOOTH_CONTROL", "rfcomm-close BLUETOOTH_CONTROL.listener",
                "event Ended(认证失败（手机 5000 毫秒内没连上认证子通道）)",
            ),
            d.tick(6_000),
        )
    }

    @Test
    fun authTimeoutAndFailure() {
        val slow = Driver()
        slow.start()
        slow.connectBluetooth()
        slow.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        slow.btListening(WirelessChannel.BLUETOOTH_AUTH, 5)
        slow.btAccepted(WirelessChannel.BLUETOOTH_AUTH)
        assertEquals(121_000L, slow.session.nextTickAtMillis)
        slow.tick(121_000)
        assertEquals("认证失败（认证超时）", slow.ended().toString())

        val closed = Driver()
        closed.start()
        closed.connectBluetooth()
        closed.btData(WirelessChannel.BLUETOOTH_CONTROL, "hello")
        closed.btListening(WirelessChannel.BLUETOOTH_AUTH, 5)
        closed.btAccepted(WirelessChannel.BLUETOOTH_AUTH)
        assertEquals(
            listOf("rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL", "event Ended(认证失败（ConnectionClosed(incompleteMessage=false)）)"),
            closed.btClosed(WirelessChannel.BLUETOOTH_AUTH),
        )
        assertIs<WirelessEndReason.AuthFailed>(closed.ended())
    }

    @Test
    fun bluetoothControlClosedBeforeWifiEnds() {
        val d = Driver()
        d.toAuthenticated()
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_CONTROL", "rfcomm-close BLUETOOTH_AUTH", "wifi-stop",
                "event Ended(蓝牙控制通道失败（WiFi 数据通道接上之前手机关闭了蓝牙控制通道）)",
            ),
            d.btClosed(WirelessChannel.BLUETOOTH_CONTROL),
        )
    }

    @Test
    fun bluetoothHandshakeTimeout() {
        val d = Driver()
        d.start()
        d.connectBluetooth()
        d.layers.bluetooth.nextTickAtMillis = 6_000
        d.layers.bluetooth.onTickHandler = { now -> if (now >= 6_000) listOf(BluetoothOutput.HandshakeTimedOut) else emptyList() }
        assertEquals(6_000L, d.session.nextTickAtMillis)
        d.tick(6_000)
        assertEquals("蓝牙控制通道失败（连上后 5 秒内握手没完成）", d.ended().toString())
    }

    @Test
    fun networkFailures() {
        // 默认的开热点时限 10 秒，从认证通过算起。
        val slow = Driver()
        slow.toAuthenticated()
        assertEquals(11_000L, slow.session.nextTickAtMillis)
        slow.tick(11_000)
        assertEquals("WiFi 组网失败（建网超时）", slow.ended().toString())

        // 平台交回的频率不是信道频率：拼不出合法的连接串。
        val bad = Driver()
        bad.toAuthenticated()
        bad.networkReady(bad.hotspotNetwork(5746))
        val reason = assertIs<WirelessEndReason.Failed>(bad.ended())
        assertEquals(WirelessStep.WIFI_NETWORK, reason.step)
        assertTrue(reason.detail.startsWith("网络信息不合法"))
    }

    @Test
    fun phoneMustJoinInTime() {
        val d = Driver()
        d.toWifiListening()
        d.listening(SessionChannel.CONTROL, 40100)
        assertEquals(21_000L, d.session.nextTickAtMillis)
        assertEquals(emptyList(), d.tick(20_999))
        assertEquals(
            listOf(
                "close CONTROL", "close CONTROL.listener", "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL",
                "wifi-stop", "event Ended(手机加入 WiFi失败（手机没在时限内连上车机 WiFi 上的端口）)",
            ),
            d.tick(21_000),
        )
        // 手机按时连上就不再计时（只剩内嵌编排器的握手计时）。
        val ok = Driver()
        ok.toWifiConnected()
        ok.layers.wifi.nearby.nextTickAtMillis = 6_000
        assertEquals(6_000L, ok.session.nextTickAtMillis)
    }

    @Test
    fun wifiPhaseEndingEndsEverything() {
        val d = Driver()
        d.toWifiConnected()
        assertEquals(
            listOf(
                "close CONTROL", "close CONTROL.listener", "rfcomm-close BLUETOOTH_AUTH", "rfcomm-close BLUETOOTH_CONTROL",
                "wifi-stop", "event Ended(手机关闭了控制通道（设备会话还没开始）)",
            ),
            d.closed(SessionChannel.CONTROL),
        )
        val reason = assertIs<WirelessEndReason.Wired>(d.ended())
        assertIs<EndReason.PeerClosed>(reason.reason)
    }

    @Test
    fun wifiChannelOnlyServesDmsdpOnChannel3() {
        val auth = Driver()
        auth.toWifiConnected()
        assertEquals(
            listOf("event ControlHandshakeCompleted(加密=true)", "diag", "write CONTROL reject:19"),
            auth.data(SessionChannel.CONTROL, "auth?"),
        )
        val usb = Driver()
        usb.toWifiConnected()
        assertEquals(
            listOf("event ControlHandshakeCompleted(加密=true)", "diag", "write CONTROL reject:20"),
            usb.data(SessionChannel.CONTROL, "dmsdp-usb?"),
        )
    }

    @Test
    fun misuseAndQuietPaths() {
        val d = Driver()
        // 投屏还没开始：发输入什么也不做。
        assertEquals(emptyList(), d.session.sendInput(HiSightInput.Key(HiSightInput.Key.Action.DOWN, 4, 0)))
        d.start()
        assertFailsWith<IllegalStateException> { d.session.start(0) }
        // 不认识的 id 原样转给内嵌的编排器（还没建时忽略）。
        assertEquals(emptyList(), d.session.onData(999, ByteArray(1), 0, 1, 0).map { d.describe(it) })
        assertEquals(emptyList(), d.session.onWifiNetworkReady(999, d.hotspotNetwork(2437), 0))
    }

    @Test
    fun eventsDoNotLeakCodesOrAddresses() {
        val d = Driver()
        d.toWifiConnected()
        val text = d.outputs.joinToString(" ") + " " + d.session
        assertFalse(text.contains(WirelessSynthetic.PIN), text)
        assertFalse(text.contains("77:88"), text)
        assertFalse(text.contains("bb:cc:dd", ignoreCase = true), text)
        assertFalse(text.contains(WirelessSynthetic.HOTSPOT_PASSPHRASE), text)
        val network = d.hotspotNetwork().toString()
        assertFalse(network.contains(WirelessSynthetic.HOTSPOT_PASSPHRASE), network)
        assertFalse(network.contains("OpenHC-5678"), network)
    }

    @Test
    fun configIsValidatedUpFront() {
        val config = WirelessSynthetic.config()
        assertEquals(NearbySession.deviceId(Synthetic.IDENTITY.host, Synthetic.BLUETOOTH), config.deviceId)
        assertEquals("3", config.authDeviceInfo()["CONNECT_ABILITY_KEY"])
        assertEquals("1", WirelessSynthetic.config(supportsUsb = false).authDeviceInfo()["CONNECT_ABILITY_KEY"])
        assertTrue(config.qrCode(WirelessSynthetic.PIN).endsWith("&pin=${WirelessSynthetic.PIN}&ability=6"))
        assertTrue(WirelessSynthetic.config(supportsUsb = false).qrCode("000000").endsWith("&ability=4"))
        assertFalse(config.authConfig(WirelessSynthetic.PIN).usbConnection)
        val base = WirelessSynthetic.config()
        assertFailsWith<IllegalArgumentException> {
            WirelessReceiverConfig(Synthetic.IDENTITY, "02:00:00:00:00:00", base.display, base.trustStore)
        }
        assertFailsWith<IllegalArgumentException> {
            WirelessReceiverConfig(Synthetic.IDENTITY, Synthetic.BLUETOOTH, DisplaySettings(0, 720, 30, 160, 1.0), base.trustStore)
        }
        assertFailsWith<IllegalArgumentException> {
            WirelessReceiverConfig(Synthetic.IDENTITY, Synthetic.BLUETOOTH, base.display, base.trustStore, pin = "12345")
        }
        assertFailsWith<IllegalArgumentException> { WirelessTimeouts(phoneJoinMillis = 0) }
        // 不指定连接码时每个会话随机生成一个 6 位数。
        val random = WirelessReceiverSession(
            WirelessReceiverConfig(Synthetic.IDENTITY, Synthetic.BLUETOOTH, base.display, base.trustStore),
            AccessUnitSink { },
        )
        assertTrue(Regex("[0-9]{6}").matches(random.pinCode))
    }

    // ---------------- 车机主动回连 ----------------

    @Test
    fun reconnectHandoffOrder() {
        val d = Driver()
        // 车机连手机的蓝牙控制服务：不开监听、不出二维码。
        assertEquals(listOf("rfcomm-connect BLUETOOTH_CONTROL $controlUuid 按 SDP"), d.startReconnect("车主的手机"))
        assertTrue(d.outputs.none { it is WirelessEvent.PairingReady })
        // 连上后车机当握手发起方。
        assertEquals(
            listOf("event PhoneBluetoothConnected(地址不显示)", "rfcomm-write BLUETOOTH_CONTROL bt-connect-id"),
            d.btConnected(WirelessChannel.BLUETOOTH_CONTROL),
        )
        assertTrue("bt.create(发起方)" in d.layers.calls)
        // 握手完成：请手机开认证子通道，业务标签是手机名。
        assertEquals(
            listOf("event BluetoothHandshakeCompleted(加密=true)", "rfcomm-write BLUETOOTH_CONTROL authreq"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs"),
        )
        assertTrue("bt.requestAuthChannel(车主的手机)" in d.layers.calls)
        // 手机同意：按它给的通道号连过去（没带 UUID 时按默认的 FE36 备用）。
        assertEquals(
            listOf("rfcomm-connect BLUETOOTH_AUTH $subchannelUuid 通道 8"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-ok"),
        )
        assertEquals(WirelessSynthetic.PHONE_BLUETOOTH, d.rfcommConnects.getValue(WirelessChannel.BLUETOOTH_AUTH).address)
        // 连上认证子通道：等手机先发认证握手，车机什么都不发。
        assertEquals(emptyList(), d.btConnected(WirelessChannel.BLUETOOTH_AUTH))
        assertTrue("auth.create" in d.layers.calls)
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_AUTH hello-reply", "event AuthStarted(TRUSTED_RECONNECT)"),
            d.btData(WirelessChannel.BLUETOOTH_AUTH, "hello-trusted"),
        )
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_AUTH done-reply", "event Authenticated(TRUSTED_RECONNECT)", "wifi-start"),
            d.btData(WirelessChannel.BLUETOOTH_AUTH, "done-trusted"),
        )
        // 之后与手机连上来时相同。
        assertEquals(listOf("event WifiNetworkReady(2437 MHz)"), d.networkReady(d.hotspotNetwork(2437)))
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL dc:2437"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "nego"))
        assertEquals(
            listOf("event DataChannelNegotiated(2437 MHz)", "listen CONTROL ${WirelessSynthetic.HOTSPOT_IP}:0"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "confirm"),
        )
        assertNull(d.session.nextTickAtMillis?.takeIf { it < d.now + d.config.timeouts.networkStartMillis - 1 })
        assertTrue(d.outputs.none { it is WirelessEvent.Ended })
    }

    @Test
    fun reconnectUsesUuidFromAnswerForSdpFallback() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        assertEquals(listOf("rfcomm-connect BLUETOOTH_AUTH $RECONNECT_UUID 通道 9"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-uuid"))
    }

    @Test
    fun reconnectDeclinedKeepsControlAndAcceptsPhoneOwnRequest() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        // 手机暂时拒绝（例如在问用户要不要关热点）：不结束，控制通道留着。
        assertEquals(listOf("event ReconnectAwaitingPhone(状态=1)"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-no"))
        assertTrue(d.tick(d.now + d.config.timeouts.reconnectConfirmMillis - 1).isEmpty())
        // 手机自己来请求开认证子通道：照手机连上来时的做法开监听、回通道号。
        assertEquals(listOf("rfcomm-listen BLUETOOTH_AUTH $subchannelUuid"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "auth?"))
        assertEquals(listOf("rfcomm-write BLUETOOTH_CONTROL rfcomm:5"), d.btListening(WirelessChannel.BLUETOOTH_AUTH, 5))
        // 没有控制通道的监听要关，只关认证子通道的监听。
        assertEquals(listOf("rfcomm-close BLUETOOTH_AUTH.listener"), d.btAccepted(WirelessChannel.BLUETOOTH_AUTH))
        d.btData(WirelessChannel.BLUETOOTH_AUTH, "hello-trusted")
        assertEquals(
            listOf("rfcomm-write BLUETOOTH_AUTH done-reply", "event Authenticated(TRUSTED_RECONNECT)", "wifi-start"),
            d.btData(WirelessChannel.BLUETOOTH_AUTH, "done-trusted"),
        )
        // 等确认的时限已经撤掉（热点开好，免得建网超时混进来）。
        d.networkReady(d.hotspotNetwork())
        assertTrue(d.tick(d.now + d.config.timeouts.reconnectConfirmMillis + 1).none { it.startsWith("event Ended") })
    }

    @Test
    fun reconnectDeclinedEndsWhenPhoneNeverConfirms() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-no")
        d.tick(d.now + d.config.timeouts.reconnectConfirmMillis)
        val reason = assertIs<WirelessEndReason.ReconnectFailed>(d.ended())
        assertEquals("手机上一直没确认", reason.detail)
        assertFalse(reason.needsPairing)
        assertTrue("rfcomm-close BLUETOOTH_CONTROL" in d.outputs.map { d.describe(it) })
    }

    @Test
    fun reconnectDeclinedEndsWhenPhoneClosesControl() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-no")
        d.btClosed(WirelessChannel.BLUETOOTH_CONTROL)
        assertEquals("手机上没确认（关了连接）", assertIs<WirelessEndReason.ReconnectFailed>(d.ended()).detail)
    }

    @Test
    fun reconnectEndsWhenPhoneNeverAnswers() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        assertTrue(d.tick(d.now + d.config.timeouts.reconnectAnswerMillis - 1).isEmpty())
        d.tick(d.now + d.config.timeouts.reconnectAnswerMillis)
        assertEquals("手机一直没回应", assertIs<WirelessEndReason.ReconnectFailed>(d.ended()).detail)
    }

    @Test
    fun reconnectEndsWhenPhoneUnreachable() {
        val d = Driver()
        d.startReconnect()
        d.btConnectError(WirelessChannel.BLUETOOTH_CONTROL)
        assertEquals("连不上手机：测试错误", assertIs<WirelessEndReason.ReconnectFailed>(d.ended()).detail)
        assertTrue(d.layers.calls.none { it.startsWith("bt.create") })
    }

    @Test
    fun reconnectEndsWhenCarCannotReachAuthChannel() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-ok")
        // 车机去连的时限比「等手机连进来」长：到了旧的 5 秒还不算超时。
        assertTrue(d.tick(d.now + d.config.timeouts.authChannelAcceptMillis).isEmpty())
        d.tick(d.now + d.config.timeouts.reconnectAuthConnectMillis)
        val reason = assertIs<WirelessEndReason.Failed>(d.ended())
        assertEquals(WirelessStep.AUTH, reason.step)
        assertTrue(reason.detail.startsWith("车机"), reason.detail)
    }

    @Test
    fun reconnectNeedingPinEndsAndAsksForPairing() {
        val d = Driver()
        d.reconnectToAuthChannel()
        d.btData(WirelessChannel.BLUETOOTH_AUTH, "hello")
        val reason = assertIs<WirelessEndReason.ReconnectFailed>(d.ended())
        assertTrue(reason.needsPairing)
        assertTrue(d.outputs.none { it is WirelessOutput.Common && it.output is SessionEvent.AuthStarted })
    }

    @Test
    fun phoneOwnRequestWhileCarConnectsAuthChannelIsRejected() {
        val d = Driver()
        d.startReconnect()
        d.btConnected(WirelessChannel.BLUETOOTH_CONTROL)
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs")
        d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-ok")
        assertEquals(listOf("diag", "rfcomm-write BLUETOOTH_CONTROL reject:19"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "auth?"))
        assertTrue(d.rfcomm[WirelessChannel.BLUETOOTH_AUTH] == null, "不另开认证子通道的监听")
    }

    @Test
    fun startAndStartReconnectAreExclusiveAndAddressIsChecked() {
        val d = Driver()
        d.start()
        assertFailsWith<IllegalStateException> { d.startReconnect() }
        assertFailsWith<IllegalArgumentException> { Driver().session.startReconnect("不是地址", "Phone", 0L) }
        assertFailsWith<IllegalArgumentException> { Driver().session.startReconnect(WirelessSynthetic.PHONE_BLUETOOTH, " ", 0L) }
    }

    @Test
    fun connectedCallbackIgnoredInPassiveMode() {
        val d = Driver()
        d.start()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        // 手机连上来的连接只认 onAccepted；误报的 onConnected 不改变任何状态。
        assertTrue(d.record(d.session.onConnected(d.rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL).acceptId, d.now)).isEmpty())
        assertEquals(
            listOf("event PhoneBluetoothConnected(地址不显示)", "rfcomm-write BLUETOOTH_CONTROL bt-connect-id"),
            d.btAccepted(WirelessChannel.BLUETOOTH_CONTROL),
        )
    }

    @Test
    fun dialAndScanStartTogether() {
        val d = Driver()
        assertEquals(
            listOf("rfcomm-listen BLUETOOTH_CONTROL $controlUuid", "rfcomm-connect BLUETOOTH_CONTROL $controlUuid 按 SDP"),
            d.startWithDial(),
        )
        // 二维码照常出来。
        assertEquals(listOf("event PairingReady(内容不显示)"), d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0))
        assertEquals(WirelessSynthetic.PHONE_BLUETOOTH, d.rfcommConnects.getValue(WirelessChannel.BLUETOOTH_CONTROL).address)
    }

    @Test
    fun dialConnectingFirstClosesTheListenerAndContinuesAsReconnect() {
        val d = Driver()
        d.startWithDial("车主的手机")
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_CONTROL",
                "rfcomm-close BLUETOOTH_CONTROL.listener",
                "diag",
                "event PhoneBluetoothConnected(地址不显示)",
                "rfcomm-write BLUETOOTH_CONTROL bt-connect-id",
            ),
            d.btConnected(WirelessChannel.BLUETOOTH_CONTROL),
        )
        assertTrue("bt.create(发起方)" in d.layers.calls)
        // 之后和车机主动回连相同：握手完成后请手机开认证子通道，按手机给的通道号连过去。
        assertEquals(
            listOf("event BluetoothHandshakeCompleted(加密=true)", "rfcomm-write BLUETOOTH_CONTROL authreq"),
            d.btData(WirelessChannel.BLUETOOTH_CONTROL, "hs"),
        )
        assertTrue("bt.requestAuthChannel(车主的手机)" in d.layers.calls)
        assertEquals(listOf("rfcomm-connect BLUETOOTH_AUTH $subchannelUuid 通道 8"), d.btData(WirelessChannel.BLUETOOTH_CONTROL, "cmd4-ok"))
        // 监听已经关了：这时才到的「接受」不算数。
        assertTrue(d.record(d.session.onAccepted(d.acceptedControlId(), WirelessSynthetic.PHONE_BLUETOOTH, d.now)).isEmpty())
        assertTrue(d.outputs.none { it is WirelessEvent.Ended })
    }

    @Test
    fun phoneConnectingFirstStopsTheDialAndContinuesAsScan() {
        val d = Driver()
        d.startWithDial()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        assertEquals(
            listOf(
                "rfcomm-close BLUETOOTH_CONTROL",
                "diag",
                "event PhoneBluetoothConnected(地址不显示)",
                "rfcomm-write BLUETOOTH_CONTROL bt-connect-id",
            ),
            d.btAccepted(WirelessChannel.BLUETOOTH_CONTROL),
        )
        val dialId = d.rfcommConnects.getValue(WirelessChannel.BLUETOOTH_CONTROL).id
        assertTrue(d.outputs.any { it is WirelessAction.RfcommClose && it.id == dialId }, "拨号那条关掉")
        assertTrue(d.layers.calls.none { it == "bt.create(发起方)" })
        // 拨号那条这时才报连上：不理。
        assertTrue(d.record(d.session.onConnected(dialId, d.now)).isEmpty())
        // 之后和手机连上来时相同：车机是应答方，开认证子通道监听。
        assertEquals(
            listOf("event BluetoothHandshakeCompleted(加密=true)", "rfcomm-listen BLUETOOTH_AUTH $subchannelUuid"),
            d.dataOnAccepted("hello"),
        )
        assertTrue(d.outputs.none { it is WirelessEvent.Ended })
    }

    @Test
    fun dialFailureKeepsWaitingForScan() {
        val d = Driver()
        d.startWithDial()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        assertEquals(listOf("rfcomm-close BLUETOOTH_CONTROL", "diag"), d.btConnectError(WirelessChannel.BLUETOOTH_CONTROL))
        assertTrue(d.outputs.none { it is WirelessEvent.Ended })
        // 之后扫码连上来照常走。
        assertEquals(
            listOf("event PhoneBluetoothConnected(地址不显示)", "rfcomm-write BLUETOOTH_CONTROL bt-connect-id"),
            d.btAccepted(WirelessChannel.BLUETOOTH_CONTROL),
        )
    }

    @Test
    fun stopWhileDialingClosesListenerAndDial() {
        val d = Driver()
        d.startWithDial()
        d.btListening(WirelessChannel.BLUETOOTH_CONTROL, 0)
        val dialId = d.rfcommConnects.getValue(WirelessChannel.BLUETOOTH_CONTROL).id
        val listen = d.rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL)
        d.stop()
        val closed = d.outputs.filterIsInstance<WirelessAction.RfcommClose>().map { it.id }.toSet()
        assertTrue(setOf(dialId, listen.id, listen.acceptId).all { it in closed }, "监听、等接受的连接、拨号三条都关")
        assertIs<WirelessEvent.Ended>(d.outputs.last())
    }

    @Test
    fun startWithDialIsExclusiveAndChecksArguments() {
        val d = Driver()
        d.start()
        assertFailsWith<IllegalStateException> { d.startWithDial() }
        assertFailsWith<IllegalStateException> { Driver().also { it.startWithDial() }.startReconnect() }
        assertFailsWith<IllegalArgumentException> { Driver().session.startWithDial("不是地址", "Phone", 0L) }
        assertFailsWith<IllegalArgumentException> { Driver().session.startWithDial(WirelessSynthetic.PHONE_BLUETOOTH, " ", 0L) }
    }
}
