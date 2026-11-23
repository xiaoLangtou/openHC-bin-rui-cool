package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 蓝牙控制通道（通道 2）和 WiFi 控制通道（通道 3）上的会话。密钥、身份、手机报文全部是合成值。 */
class WirelessSessionTest {
    private val connectIdBytes = hex("00005ba0")
    private val cipher = NearbyFrameCipher(Vectors.KEYS_16)

    private fun session(link: NearbyLink, vararg seeds: ByteArray) =
        NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(connectIdBytes, *seeds), link)

    private fun sends(actions: List<NearbyAction>) = actions.filterIsInstance<NearbyAction.Send>().map { it.frame }

    private fun kinds(actions: List<NearbyAction>) = actions.map { it::class.simpleName }

    private fun phoneEncrypted(outerType: Int, payload: ByteArray, seed: ByteArray) = cipher.seal(outerType, payload, seed).encode()

    /** 拆开车机发出的一帧：外层类型、是否加密、明文载荷。 */
    private fun open(frame: ByteArray): Triple<Int, Boolean, ByteArray> {
        val decoder = NearbyFrameDecoder()
        decoder.feed(frame)
        val f = assertNotNull(decoder.next())
        assertNull(decoder.next())
        return Triple(f.outerType, f.isEncrypted, cipher.open(f))
    }

    /** 手机的认证子通道请求：消息层命令 3，通道 [channel]、业务 19、协议 0，可带 uuid。 */
    private fun authSubchannelRequest(channel: Int = 2, uuid: ByteArray? = null) = encodeTlv(
        SocketMessages.CMD_CREATE_REQUEST,
        SocketMessages.encodeRequest(SocketMessages.Request(channel, 19, "hicarAuth".toByteArray(), 0, uuid)),
    )

    private fun bluetoothHandshaken(vararg seeds: ByteArray): NearbySession {
        val s = session(NearbyLink.BLUETOOTH, Vectors.SEED_A, *seeds)
        s.start(0)
        assertIs<NearbyEvent.HandshakeCompleted>(s.feed(hex(Vectors.PHONE_FIRST)).last())
        return s
    }

    @Test
    fun bluetoothHandshakeDoesNotSendTlv7() {
        val s = session(NearbyLink.BLUETOOTH, Vectors.SEED_A)
        assertEquals(NearbyLink.BLUETOOTH, s.link)
        // 与有线一样先发明文 connectId。
        assertEquals(listOf(Vectors.CONNECT_ID_123456), sends(s.start(0)).map { it.toHex() })
        val out = s.feed(hex(Vectors.PHONE_FIRST))
        assertEquals(listOf("Unhandled", "Unhandled", "Send", "HandshakeCompleted"), kinds(out))
        val (outer, encrypted, payload) = open(sends(out).single())
        assertEquals(0, outer)
        assertTrue(encrypted)
        assertEquals(Vectors.PAYLOAD_REPLY, payload.toHex())
    }

    @Test
    fun authSubchannelIsAnsweredWithRfcommChannelAndUuid() {
        val s = bluetoothHandshaken(Vectors.SEED_B)
        val request = assertIs<NearbyEvent.SocketRequested>(
            s.feed(phoneEncrypted(2, authSubchannelRequest(), Vectors.SEED_D)).single(),
        )
        assertEquals(listOf(2, 19, 0), listOf(request.channel, request.businessId, request.protocol))
        assertNull(request.uuid)
        assertEquals(NearbySession.DEFAULT_SUBCHANNEL_UUID, NearbySession.rfcommServiceUuid(request))
        val (outer, encrypted, payload) = open(sends(s.acceptRfcommSocket(request, 4)).single())
        assertEquals(2, outer)
        assertTrue(encrypted)
        // 状态 0、端口位填通道号 4、通道 2、业务 19、标签、协议 0（照抄请求）、16 字节 UUID。
        assertEquals("84a0808482938968696361724175746880900000fe3600001000800000805f9b34fb", payload.toHex())
    }

    @Test
    fun subchannelRequestOnChannelZeroIsTreatedAsBluetooth() {
        val s = bluetoothHandshaken(Vectors.SEED_B)
        val uuid = incrementing(0x20, 16)
        val request = assertIs<NearbyEvent.SocketRequested>(
            s.feed(phoneEncrypted(2, authSubchannelRequest(channel = 0, uuid = uuid), Vectors.SEED_D)).single(),
        )
        assertEquals(2, request.channel)
        val response = SocketMessages.parseResponse(Tlv.parseAll(open(sends(s.acceptRfcommSocket(request, 7)).single()).third).single().value)
        assertEquals(listOf(0, 7, 2, 19, 0), listOf(response.status, response.port, response.channel, response.businessId, response.protocol))
        assertEquals(uuid.toHex(), response.uuid!!.toHex())
    }

    @Test
    fun badSubchannelRequestsAreRejectedAutomatically() {
        // 握手前：自动拒绝。
        val early = session(NearbyLink.BLUETOOTH)
        early.start(0)
        val beforeHandshake = early.feed(NearbyFrame.plain(2, authSubchannelRequest()).encode())
        assertEquals(listOf("Send", "SocketRequestRejected"), kinds(beforeHandshake))
        // uuid 不是 16 字节：自动拒绝。
        val s = bluetoothHandshaken(Vectors.SEED_B)
        val out = s.feed(phoneEncrypted(2, authSubchannelRequest(uuid = byteArrayOf(1, 2, 3)), Vectors.SEED_D))
        assertEquals(listOf("Send", "SocketRequestRejected"), kinds(out))
        val response = SocketMessages.parseResponse(Tlv.parseAll(open(sends(out).single()).third).single().value)
        assertEquals(1, response.status)
        // 蓝牙通道上要 TCP 的请求照常交出去，由调用方决定（编排器会拒绝）。
        val tcp = encodeTlv(3, SocketMessages.encodeRequest(SocketMessages.Request(2, 20, "DMSDP".toByteArray(), 1, null)))
        assertIs<NearbyEvent.SocketRequested>(bluetoothHandshaken().feed(phoneEncrypted(4, tcp, Vectors.SEED_D)).single())
    }

    @Test
    fun messageLayerProbeIsAnswered() {
        val s = bluetoothHandshaken(Vectors.SEED_B)
        val out = s.feed(phoneEncrypted(2, encodeTlv(1), Vectors.SEED_D))
        val (outer, _, payload) = open(sends(out).single())
        assertEquals(2, outer)
        assertEquals("8280", payload.toHex())
        // 有线通道上消息层的命令 1 照旧不处理。
        val wired = NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        wired.start(0)
        wired.feed(hex(Vectors.PHONE_FIRST))
        assertIs<NearbyEvent.Unhandled>(wired.feed(phoneEncrypted(2, encodeTlv(1), Vectors.SEED_D)).single())
    }

    @Test
    fun dataChannelNegotiation() {
        val s = bluetoothHandshaken(Vectors.SEED_B, Vectors.SEED_C)
        val capability = ByteWriter().ea(1).ea(1).ea(2).ea(1).ea(4).ea(1).ea(5).bytes(hex("1182")).ea(7).ea(0).toByteArray()
        val request = assertIs<NearbyEvent.DataChannelRequested>(
            s.feed(phoneEncrypted(4, encodeTlv(1, DataChannelMessages.requestValue(3, capability)), Vectors.SEED_D)).single(),
        )
        assertEquals(3, request.dataChannel)
        assertEquals(setOf(36, 149, 165), request.capability.channels5g)
        val (outer, _, accept) = open(sends(s.acceptDataChannel(request, 5745)).single())
        assertEquals(4, outer)
        assertEquals("8287838183830171ac", accept.toHex())
        assertEquals("82828382", open(sends(s.rejectDataChannel(request, DataChannelRefusal.BUSY)).single()).third.toHex())
        assertFailsWith<IllegalArgumentException> { s.acceptDataChannel(request, 5746) }

        val result = assertIs<NearbyEvent.DataChannelResult>(
            s.feed(phoneEncrypted(4, encodeTlv(7, hex("838183830271ac")), Vectors.SEED_E)).single(),
        )
        assertTrue(result.accepted)
        assertEquals(listOf(3, 2, 5745), listOf(result.dataChannel, result.peerRole, result.frequencyMhz))
        val refused = assertIs<NearbyEvent.DataChannelResult>(s.feed(phoneEncrypted(4, encodeTlv(7, hex("837f81")), Vectors.SEED_F)).single())
        assertFalse(refused.accepted)
        // 有线通道上命令 1 照旧作为未处理事件交出（见 NearbySessionTest）。
    }

    @Test
    fun discardedFramesNameTheLayerAndCommand() {
        val s = bluetoothHandshaken(Vectors.SEED_B, Vectors.SEED_C)
        // 命令 1 的能力串 TLV 声明的长度比实际长：整帧丢掉，原因里写明外层类型和命令号。
        val bad = assertIs<NearbyEvent.InputDiscarded>(s.feed(phoneEncrypted(4, encodeTlv(1, hex("838385")), Vectors.SEED_D)).single())
        assertTrue(bad.reason.startsWith("外层类型 4：命令 1（3 字节）："), bad.reason)
    }

    @Test
    fun wifiLinkMessagesOnBluetooth() {
        val s = bluetoothHandshaken(Vectors.SEED_B, Vectors.SEED_C)
        val (outer, encrypted, ask) = open(sends(s.requestWifiPeerAddress()).single())
        assertEquals(33, outer)
        assertTrue(encrypted)
        assertEquals("8180", ask.toHex())

        val mac = assertIs<NearbyEvent.WifiPeerAddress>(
            s.feed(phoneEncrypted(33, encodeTlv(2, "0A:1B:2C:3D:4E:5F".toByteArray()), Vectors.SEED_D)).single(),
        )
        assertEquals("0a:1b:2c:3d:4e:5f", mac.address)
        assertFalse(mac.toString().contains("4e:5f", ignoreCase = true))
        assertEquals(NearbyEvent.WifiPeerBusy, s.feed(phoneEncrypted(33, encodeTlv(2, "busy".toByteArray()), Vectors.SEED_E)).single())
        // MAC 格式不对：整帧丢掉。
        assertIs<NearbyEvent.InputDiscarded>(s.feed(phoneEncrypted(33, encodeTlv(2, "nope".toByteArray()), Vectors.SEED_F)).single())

        val info = WifiConnectionInfo(WifiMode.DIRECT, "192.168.49.2", "DIRECT-HC-ABCD", "02:11:22:33:44:55", "abcdefgh2345", 5745, 41000, "192.168.49.1")
        val (_, _, sent) = open(sends(s.sendWifiConnectionInfo(info)).single())
        val tlv = Tlv.parseAll(sent).single()
        assertEquals(3, tlv.tag)
        assertEquals(info.encode(), String(tlv.value, Charsets.UTF_8))
    }

    @Test
    fun wirelessOnlyCallsAreGuarded() {
        val early = session(NearbyLink.BLUETOOTH)
        early.start(0)
        assertFailsWith<IllegalStateException> { early.requestWifiPeerAddress() }
        val wired = NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(connectIdBytes, Vectors.SEED_A, Vectors.SEED_B))
        wired.start(0)
        wired.feed(hex(Vectors.PHONE_FIRST))
        val request = NearbyEvent.SocketRequested(2, 2, 19, "hicarAuth".toByteArray(), 0, null)
        assertFailsWith<IllegalStateException> { wired.acceptRfcommSocket(request, 4) }
        assertFailsWith<IllegalStateException> { wired.requestWifiPeerAddress() }
        assertFailsWith<IllegalStateException> { wired.requestWifiDisconnect() }
        val bt = bluetoothHandshaken()
        assertFailsWith<IllegalArgumentException> { bt.acceptRfcommSocket(request, 31) }
        assertFailsWith<IllegalStateException> { bt.requestWifiDisconnect() }
        // 有线通道上外层类型 33 照旧不处理。
        assertIs<NearbyEvent.Unhandled>(wired.feed(phoneEncrypted(33, encodeTlv(5), Vectors.SEED_D)).single())
    }

    @Test
    fun wifiChannelStartsAsInitiator() {
        val s = session(NearbyLink.WIFI)
        val frames = sends(s.start(1_000))
        assertEquals(2, frames.size)
        assertEquals(Vectors.CONNECT_ID_123456, frames[0].toHex())
        val (outer, encrypted, payload) = open(frames[1])
        assertEquals(0, outer)
        assertFalse(encrypted)
        // TLV1{02} TLV3(64 字符 deviceId，不带结尾 NUL) TLV5{01}，明文。
        val deviceIdText = Vectors.DEVICE_ID_1.toByteArray(Charsets.US_ASCII).toHex()
        assertEquals("818102" + "83c0" + deviceIdText + "858101", payload.toHex())
        assertEquals(6_000L, s.nextTickAtMillis)
    }

    @Test
    fun wifiChannelHandshakeInBothDirections() {
        val s = session(NearbyLink.WIFI, Vectors.SEED_A, Vectors.SEED_B, Vectors.SEED_C)
        s.start(0)
        // 手机的首包：车机照常应答（不发 TLV7），握手完成。
        val first = s.feed(hex(Vectors.PHONE_FIRST))
        assertEquals(listOf("Unhandled", "Unhandled", "Send", "HandshakeCompleted"), kinds(first))
        assertEquals(Vectors.PAYLOAD_REPLY, open(sends(first).single()).third.toHex())
        // 手机回应车机的请求（TLV2、13 字节摘要的 TLV4、TLV6）：不用再回，也不再报一次握手完成。
        val answer = ByteWriter().tlv(2, byteArrayOf(5)).tlv(4, "0123456789AB\u0000".toByteArray()).tlv(6, byteArrayOf(1)).toByteArray()
        assertEquals(emptyList(), s.feed(phoneEncrypted(0, answer, Vectors.SEED_D)))
        // 之后手机在通道 3 上请求业务 20，应答与有线相同（协议 1、不带 uuid）。
        val dmsdp = encodeTlv(3, SocketMessages.encodeRequest(SocketMessages.Request(3, 20, "DMSDP".toByteArray(), 1, null)))
        val request = assertIs<NearbyEvent.SocketRequested>(s.feed(phoneEncrypted(4, dmsdp, Vectors.SEED_E)).single())
        assertEquals(listOf(3, 20), listOf(request.channel, request.businessId))
        val (outer, _, payload) = open(sends(s.acceptSocket(request, 39_000)).single())
        assertEquals(4, outer)
        val response = SocketMessages.parseResponse(Tlv.parseAll(payload).single().value)
        assertEquals(listOf(0, 39_000, 3, 20, 1), listOf(response.status, response.port, response.channel, response.businessId, response.protocol))
        assertNull(response.uuid)
        // 对端请求断开（组网层 tag 5）：自动回 tag 6。
        val bye = s.feed(phoneEncrypted(33, encodeTlv(5), Vectors.SEED_F))
        assertEquals(listOf("Send", "WifiDisconnectRequested"), kinds(bye))
        assertEquals("8680", open(sends(bye).single()).third.toHex())
    }

    @Test
    fun wifiChannelCanRequestDisconnect() {
        val s = session(NearbyLink.WIFI, Vectors.SEED_A, Vectors.SEED_B)
        s.start(0)
        s.feed(hex(Vectors.PHONE_FIRST))
        val (outer, _, payload) = open(sends(s.requestWifiDisconnect()).single())
        assertEquals(33, outer)
        assertEquals("8580", payload.toHex())
        assertEquals(NearbyEvent.WifiDisconnectAnswered, s.feed(phoneEncrypted(33, encodeTlv(6), Vectors.SEED_D)).single())
    }
}
