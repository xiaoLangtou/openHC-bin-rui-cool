package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.common.hexToBytes

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.codec.Crc16Arc
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.nearby.WifiConnectionInfo
import io.github.gcbond.openhc.core.nearby.WifiMode
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 用正式的 nearby 实现走一遍无线编排（蓝牙控制通道和 WiFi 控制通道都是真的 NearbySession，认证、设备会话、投屏用假的）。
 * 手机一侧的控制帧在这里按协议格式另写一份（帧头、EA、标志段、CRC-16/ARC、按种子派生密钥的 AES-CFB），与 core:nearby 互相独立。
 */
class RealWirelessFlowTest {

    /** 手机一侧的控制帧。密钥材料取合成身份资产里的那三段。 */
    private object Phone {
        private val root: ByteArray = run {
            val k = Synthetic.IDENTITY.nearbyKeys
            val a = k.constA
            val b = k.constB
            val r = k.rootKey
            val n = r.size
            val h1 = ByteArray(n) { i -> (a[(i + 3) % n].toInt() xor b[i].toInt()).toByte() }
            ByteArray(n) { i -> (h1[(i - 5 + n) % n].toInt() xor r[i].toInt()).toByte() }
        }
        private var counter = 0

        fun tlv(tag: Int, value: ByteArray = ByteArray(0)): ByteArray = ByteWriter().ea(tag).ea(value.size).bytes(value).toByteArray()

        fun plain(outer: Int, payload: ByteArray): ByteArray = frame(outer, byteArrayOf(0x80.toByte(), 0x80.toByte()), payload)

        fun sealed(outer: Int, payload: ByteArray): ByteArray {
            val seed = ByteArray(8) { (0x30 + counter * 8 + it).toByte() }
            counter++
            val flag = byteArrayOf(0x81.toByte(), 0x8A.toByte(), 0x81.toByte(), 0x88.toByte()) + seed
            return frame(outer, flag, crypt(Cipher.ENCRYPT_MODE, seed, payload))
        }

        private fun frame(outer: Int, flag: ByteArray, payload: ByteArray): ByteArray {
            val body = ByteWriter().u8(0x7F).ea(outer).bytes(flag).ea(payload.size).bytes(payload).toByteArray()
            return ByteWriter().bytes(body).u16Le(Crc16Arc.compute(body)).toByteArray()
        }

        /** 拆车机发来的一帧：外层类型、是否加密、明文载荷。 */
        fun open(frame: ByteArray): Triple<Int, Boolean, ByteArray> {
            val r = ByteReader(frame)
            assertEquals(0x7F, r.u8())
            val outer = r.ea()
            val flag = r.ea()
            val flagValue = r.bytes(r.ea())
            val payload = r.bytes(r.ea())
            val crc = r.u16Le()
            assertEquals(0, r.remaining)
            assertEquals(Crc16Arc.compute(frame, 0, frame.size - 2), crc)
            if (flag and 1 == 0) return Triple(outer, false, payload)
            assertEquals("8188", flagValue.copyOfRange(0, 2).toHex())
            return Triple(outer, true, crypt(Cipher.DECRYPT_MODE, flagValue.copyOfRange(2, 10), payload))
        }

        private fun crypt(mode: Int, seed: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(root, "HmacSHA256"))
            val e = mac.doFinal(seed)
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(mode, SecretKeySpec(e.copyOfRange(0, 16), "AES"), IvParameterSpec(e.copyOfRange(16, 32)))
            return cipher.doFinal(data)
        }

        /** 手机首包：版本 5、摘要（12 位十六进制加 NUL）、要求加密。 */
        val FIRST = tlv(1, byteArrayOf(5)) + tlv(3, "0123456789AB\u0000".toByteArray()) + tlv(5, byteArrayOf(2))
    }

    /** 蓝牙 nearby 和 WiFi 控制通道上的 nearby 用正式实现，其余用假的。 */
    private class HybridLayers(config: WirelessReceiverConfig) : WirelessLayers {
        private val real = ProductionWirelessLayers(config)
        val fake = FakeWirelessLayers()

        override fun bluetoothNearby(initiator: Boolean) = real.bluetoothNearby(initiator)
        override fun auth(pinCode: String) = fake.auth(pinCode)
        override fun wifiPhase(config: ReceiverConfig): Layers = object : Layers by fake.wifi {
            override fun nearby() = WifiPhaseLayers(config).nearby()
        }
    }

    private class Ids {
        val rfcomm = HashMap<WirelessChannel, WirelessAction.ListenRfcomm>()
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        var network: WirelessAction.StartWifiNetwork? = null

        fun note(outputs: List<WirelessOutput>): List<WirelessOutput> {
            for (o in outputs) {
                when (o) {
                    is WirelessAction.ListenRfcomm -> rfcomm[o.channel] = o
                    is WirelessAction.StartWifiNetwork -> network = o
                    is WirelessOutput.Common -> (o.output as? SessionAction.Listen)?.let { listens[it.channel] = it }
                    else -> Unit
                }
            }
            return outputs
        }
    }

    private fun List<WirelessOutput>.rfcommWrites() = filterIsInstance<WirelessAction.RfcommWrite>().map { it.bytes }

    private fun List<WirelessOutput>.tcpWrites() =
        filterIsInstance<WirelessOutput.Common>().mapNotNull { (it.output as? SessionAction.Write)?.bytes }

    @Test
    fun bluetoothAndWifiControlChannelsWithRealNearby() {
        val config = WirelessSynthetic.config()
        val layers = HybridLayers(config)
        val session = WirelessReceiverSession(config, AccessUnitSink { }, layers)
        val ids = Ids()
        var now = 1_000L
        fun run(outputs: List<WirelessOutput>) = ids.note(outputs)
        fun bt(channel: WirelessChannel, bytes: ByteArray) =
            run(session.onData(ids.rfcomm.getValue(channel).acceptId, bytes, 0, bytes.size, now))
        fun tcp(channel: SessionChannel, bytes: ByteArray) =
            run(session.onData(ids.listens.getValue(channel).acceptId, bytes, 0, bytes.size, now))

        // 蓝牙控制通道：连上后车机先发明文 connectId。等扫码、手机连上蓝牙、握手时都不开热点。
        assertTrue(run(session.start(now)).none { it is WirelessAction.StartWifiNetwork })
        run(session.onListening(ids.rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL).id, 0, now))
        val opened = run(session.onAccepted(ids.rfcomm.getValue(WirelessChannel.BLUETOOTH_CONTROL).acceptId, WirelessSynthetic.PHONE_BLUETOOTH, now))
        val (outer0, encrypted0, connectId) = Phone.open(opened.rfcommWrites().single())
        assertEquals(0, outer0)
        assertFalse(encrypted0)
        assertTrue(Regex("8f86(3[0-9]){6}").matches(connectId.toHex()))

        // 手机首包 → 车机只回一帧加密的 TLV2/4/6（蓝牙上不报 TLV7）。
        val handshake = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.plain(0, Phone.FIRST))
        val (_, encrypted1, reply) = Phone.open(handshake.rfcommWrites().single())
        assertTrue(encrypted1)
        assertEquals(listOf(2, 4, 6), tags(reply))
        assertIs<WirelessEvent.BluetoothHandshakeCompleted>(handshake.filterIsInstance<WirelessEvent>().single())

        // 认证子通道：消息层请求（通道 2、业务 19、协议 0、不带 uuid）→ 开 RFCOMM 监听 → 回通道号和默认 UUID。
        val authRequest = Phone.tlv(3, ByteWriter().ea(2).ea(19).ea(9).bytes("hicarAuth".toByteArray()).ea(0).toByteArray())
        bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(2, authRequest))
        val authListen = ids.rfcomm.getValue(WirelessChannel.BLUETOOTH_AUTH)
        assertEquals("0000fe36-0000-1000-8000-00805f9b34fb", authListen.uuid.toString())
        val answered = run(session.onListening(authListen.id, 6, now))
        val (outer2, _, answer) = Phone.open(answered.rfcommWrites().single())
        assertEquals(2, outer2)
        assertEquals("84a0808682938968696361724175746880900000fe3600001000800000805f9b34fb", answer.toHex())
        run(session.onAccepted(authListen.acceptId, WirelessSynthetic.PHONE_BLUETOOTH, now))
        bt(WirelessChannel.BLUETOOTH_AUTH, "hello".bytes())
        assertEquals(null, ids.network)
        // 认证通过后才开热点（名称、密码、频率由系统定）。
        bt(WirelessChannel.BLUETOOTH_AUTH, "done".bytes())
        val start = ids.network!!

        // 热点开好（合成值，2.4 GHz 第 6 信道）。
        val hotspot = WirelessSynthetic.hotspot(2437)
        run(session.onWifiNetworkReady(start.id, hotspot, now))

        // 数据通道协商：能力串（组主、组成员、2.4G、5G、信道 36/149、当前没连 WiFi）。手机能用 5G 也照样报热点的频率：
        // 命令 2 = 车机当组主（1）+ EA(2437) = 05 93。
        val capability = ByteWriter().ea(1).ea(1).ea(2).ea(1).ea(3).ea(1).ea(4).ea(1).ea(5).bytes(byteArrayOf(0x11, 0x80.toByte())).ea(7).ea(0).toByteArray()
        val negotiation = ByteWriter().ea(3).bytes(Phone.tlv(3, capability)).toByteArray()
        val offer = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(4, Phone.tlv(1, negotiation)))
        val (outer4, _, offered) = Phone.open(offer.rfcommWrites().single())
        assertEquals(4, outer4)
        assertEquals("828783818383010593", offered.toHex())
        // 命令 7：手机当组成员（2）、同一频率。热点早已开好，确认后不再建网，直接开控制监听。
        val confirmed = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(4, Phone.tlv(7, byteArrayOf(0x83.toByte(), 0x81.toByte(), 0x83.toByte(), 0x83.toByte(), 0x02, 0x05, 0x93.toByte()))))
        assertTrue(confirmed.none { it is WirelessAction.StartWifiNetwork })

        // 控制监听开好 → 组网层 tag 1 要 MAC → 手机回 MAC → tag 3 连接串（热点格式）。
        val ask = run(session.onListening(ids.listens.getValue(SessionChannel.CONTROL).id, 40100, now))
        val (outer33, _, askPayload) = Phone.open(ask.rfcommWrites().single())
        assertEquals(33, outer33)
        assertEquals("8180", askPayload.toHex())
        val infoOut = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(33, Phone.tlv(2, WirelessSynthetic.PHONE_MAC.toByteArray())))
        val (_, _, infoPayload) = Phone.open(infoOut.rfcommWrites().single())
        val tlv = ByteReader(infoPayload)
        assertEquals(3, tlv.ea())
        val text = tlv.bytes(tlv.ea()).toString(Charsets.UTF_8)
        assertEquals(0, tlv.remaining)
        assertEquals(
            "${WirelessSynthetic.PHONE_WIFI_IP}##OpenHC-5678\n0a:1b:2c:3d:4e:5f\n${WirelessSynthetic.HOTSPOT_PASSPHRASE}\n2437\n1" +
                "##version:3##40100##6##hicarApIp:${WirelessSynthetic.HOTSPOT_IP}",
            text,
        )
        val info = WifiConnectionInfo.parse(text)
        assertEquals(WifiMode.HOTSPOT, info.mode)
        assertEquals(listOf(2437, 40100), listOf(info.frequencyMhz, info.port))

        // 手机连上热点上的控制端口：车机先发 connectId，再当一次发起方（明文 TLV1{02} TLV3(deviceId) TLV5{01}）。
        val wifiOpened = run(session.onAccepted(ids.listens.getValue(SessionChannel.CONTROL).acceptId, WirelessSynthetic.PHONE_WIFI_IP, now))
        val starts = wifiOpened.tcpWrites().map { Phone.open(it) }
        assertEquals(2, starts.size)
        assertTrue(starts.none { it.second })
        assertEquals(
            "818102" + "83c0" + config.deviceId.toByteArray().toHex() + "858101",
            starts[1].third.toHex(),
        )
        // 手机首包 → 车机应答（不报 TLV7）；手机回应车机的请求 → 不用再回。
        val (_, encrypted3, reply3) = Phone.open(tcp(SessionChannel.CONTROL, Phone.plain(0, Phone.FIRST)).tcpWrites().single())
        assertTrue(encrypted3)
        assertEquals(listOf(2, 4, 6), tags(reply3))
        val back = Phone.tlv(2, byteArrayOf(5)) + Phone.tlv(4, "0123456789AB\u0000".toByteArray()) + Phone.tlv(6, byteArrayOf(1))
        assertEquals(emptyList(), tcp(SessionChannel.CONTROL, Phone.sealed(0, back)).tcpWrites())

        // 业务 20 在通道 3 上：认证已在蓝牙上做完，直接开监听、回端口。
        val dmsdp = Phone.tlv(3, ByteWriter().ea(3).ea(20).ea(5).bytes("DMSDP".toByteArray()).ea(1).toByteArray())
        tcp(SessionChannel.CONTROL, Phone.sealed(4, dmsdp))
        val accepted = run(session.onListening(ids.listens.getValue(SessionChannel.DMSDP_SESSION).id, 40200, now))
        val (outer5, _, port) = Phone.open(accepted.tcpWrites().single())
        assertEquals(4, outer5)
        assertEquals("848d" + "80083a82" + "839485" + "444d534450" + "81", port.toHex())
        run(session.onAccepted(ids.listens.getValue(SessionChannel.DMSDP_SESSION).acceptId, WirelessSynthetic.PHONE_WIFI_IP, now))
        assertContentEquals(Synthetic.KEY, layers.fake.wifi.dmsdpKey)

        now += 1_000
        val end = run(session.stop(now))
        assertIs<WirelessEvent.Ended>(end.last())
        assertEquals(1, end.count { it is WirelessAction.StopWifiNetwork })
    }

    /**
     * 车机主动回连，用真的蓝牙 nearby：车机拨号 → 当握手发起方 → 请手机开认证子通道 → 按手机给的通道号连 → 认证（假的）
     * → 实车抓到的那条数据通道协商（2026-11-22，Mate 70，解密后 33 字节）→ 车机按热点频率回应。
     */
    @Test
    fun reconnectWithRealNearbyUpToDataChannelAnswer() {
        val config = WirelessSynthetic.config()
        val layers = HybridLayers(config)
        val session = WirelessReceiverSession(config, AccessUnitSink { }, layers)
        val now = 1_000L
        val connects = HashMap<WirelessChannel, WirelessAction.ConnectRfcomm>()
        var network: WirelessAction.StartWifiNetwork? = null
        fun run(outputs: List<WirelessOutput>): List<WirelessOutput> {
            for (o in outputs) {
                when (o) {
                    is WirelessAction.ConnectRfcomm -> connects[o.channel] = o
                    is WirelessAction.StartWifiNetwork -> network = o
                    else -> Unit
                }
            }
            return outputs
        }
        fun bt(channel: WirelessChannel, bytes: ByteArray) = run(session.onData(connects.getValue(channel).id, bytes, 0, bytes.size, now))
        val phoneName = "车主的Mate 70 Pro"
        val nameBytes = phoneName.toByteArray()

        // 车机连手机的蓝牙控制服务（FE35，按 SDP 查），不开监听、不出二维码。
        assertTrue(run(session.startReconnect(WirelessSynthetic.PHONE_BLUETOOTH, phoneName, now)).none { it is WirelessEvent.PairingReady })
        val control = connects.getValue(WirelessChannel.BLUETOOTH_CONTROL)
        assertEquals("0000fe35-0000-1000-8000-00805f9b34fb", control.uuid.toString())
        assertEquals(null, control.rfcommChannel)

        // 连上：车机当发起方，明文 connectId，再明文 TLV1{02} TLV3(deviceId) TLV5{01}。
        val opened = run(session.onConnected(control.id, now)).rfcommWrites().map { Phone.open(it) }
        assertEquals(2, opened.size)
        assertTrue(opened.none { it.second })
        assertEquals("818102" + "83c0" + config.deviceId.toByteArray().toHex() + "858101", opened[1].third.toHex())

        // 手机回 TLV2/4/6（加密）→ 握手完成，车机接着发命令 3（加密）：通道 2、业务 19、标签 = 手机名、协议 0、不带 UUID。
        val back = Phone.tlv(2, byteArrayOf(5)) + Phone.tlv(4, "0123456789AB\u0000".toByteArray()) + Phone.tlv(6, byteArrayOf(1))
        val asked = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(0, back))
        assertIs<WirelessEvent.BluetoothHandshakeCompleted>(asked.filterIsInstance<WirelessEvent>().single())
        val (outer2, encrypted2, cmd3) = Phone.open(asked.rfcommWrites().single())
        assertEquals(2, outer2)
        assertTrue(encrypted2)
        assertEquals(Phone.tlv(3, ByteWriter().ea(2).ea(19).ea(nameBytes.size).bytes(nameBytes).ea(0).toByteArray()).toHex(), cmd3.toHex())

        // 手机回命令 4：同意、通道 8、原样回送、带 FE36 → 车机按通道 8 连过去（FE36 备用于查 SDP）。
        val fe36 = "0000fe3600001000800000805f9b34fb".hexToBytes()
        val cmd4 = Phone.tlv(4, ByteWriter().ea(0).ea(8).ea(2).ea(19).ea(nameBytes.size).bytes(nameBytes).ea(0).ea(16).bytes(fe36).toByteArray())
        bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(2, cmd4))
        val auth = connects.getValue(WirelessChannel.BLUETOOTH_AUTH)
        assertEquals(8, auth.rfcommChannel)
        assertEquals("0000fe36-0000-1000-8000-00805f9b34fb", auth.uuid.toString())
        assertEquals(WirelessSynthetic.PHONE_BLUETOOTH, auth.address)

        // 认证（假的，免码）通过才开热点。
        run(session.onConnected(auth.id, now))
        bt(WirelessChannel.BLUETOOTH_AUTH, "hello-trusted".bytes())
        assertEquals(null, network)
        bt(WirelessChannel.BLUETOOTH_AUTH, "done-trusted".bytes())
        run(session.onWifiNetworkReady(network!!.id, WirelessSynthetic.hotspot(5745), now))

        // 实车那条数据通道协商：车机当组主、报热点频率（5745 = EA 0x2C 0xF1 → ad f1）。
        val sample = "819f83839c818182818381848186818c7fbf857f83878089808b808a8091819280".hexToBytes()
        val offer = bt(WirelessChannel.BLUETOOTH_CONTROL, Phone.sealed(4, sample))
        assertTrue(offer.none { it is WirelessEvent.Ended }, offer.toString())
        val (outer4, _, offered) = Phone.open(offer.rfcommWrites().single())
        assertEquals(4, outer4)
        val answer = ByteReader(offered)
        assertEquals(2, answer.ea(), "命令 2：车机回协商应答")
        val value = ByteReader(answer.bytes(answer.ea()))
        assertEquals(3, value.ea(), "数据通道 3（WiFi）")
    }

    private fun tags(payload: ByteArray): List<Int> {
        val r = ByteReader(payload)
        val out = ArrayList<Int>()
        while (r.remaining > 0) {
            out += r.ea()
            r.skip(r.ea())
        }
        return out
    }
}
