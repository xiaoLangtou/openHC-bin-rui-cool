package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 车机主动回连：蓝牙控制通道上车机当发起方（[NearbySession] 的 initiator 模式）。对手用真的 [NearbyFrameCipher]
 * 当「假手机」解车机发的帧、按协议回帧，验证的是两边都走真实编解码的往返。
 */
class BluetoothInitiatorTest {

    private val cipher = NearbyFrameCipher(Vectors.KEYS_16)
    private val phoneName = "HUAWEI Mate 70 Pro"
    private val tag = phoneName.toByteArray(Charsets.UTF_8)
    private val fe36 = hex("0000fe3600001000800000805f9b34fb")

    private fun session(random: FixedSecureRandom) =
        NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, random, NearbyLink.BLUETOOTH, initiator = true)

    private fun sends(actions: List<NearbyAction>) = actions.filterIsInstance<NearbyAction.Send>().map { it.frame }

    /** 拆开车机发出的一帧，返回（帧，明文载荷）。假手机的视角。 */
    private fun open(frame: ByteArray): Pair<NearbyFrame, ByteArray> {
        val decoder = NearbyFrameDecoder()
        decoder.feed(frame)
        val f = assertNotNull(decoder.next())
        assertNull(decoder.next())
        return f to cipher.open(f)
    }

    private fun phoneEncrypted(outerType: Int, payload: ByteArray, seed: ByteArray) = cipher.seal(outerType, payload, seed).encode()

    /** 假手机：版本应答、自己的摘要、加密应答凑在一帧里回车机（握手完成要集齐这三类）。 */
    private fun phoneHandshakeReply(seed: ByteArray): ByteArray {
        val payload = ByteWriter()
            .tlv(ChannelManagement.TAG_VERSION_RESPONSE, byteArrayOf(ChannelManagement.LOCAL_VERSION.toByte()))
            .tlv(ChannelManagement.TAG_SUMMARY_RESPONSE, "PHONESUMMARY".toByteArray())
            .tlv(ChannelManagement.TAG_ENCRYPTION_RESPONSE, byteArrayOf(1))
            .toByteArray()
        return phoneEncrypted(ChannelManagement.OUTER_TYPE, payload, seed)
    }

    /** 握手做完、认证子通道请求发出去的会话。 */
    private fun requested(): NearbySession {
        val s = session(FixedSecureRandom(hex("00005ba0"), Vectors.SEED_A, Vectors.SEED_B))
        s.start(0L)
        s.feed(phoneHandshakeReply(Vectors.SEED_C))
        s.requestAuthChannel(tag)
        return s
    }

    private fun cmd4(status: Int, port: Int, tag: ByteArray = this.tag, businessId: Int = NearbySession.BUSINESS_AUTH, uuid: ByteArray? = fe36) =
        phoneEncrypted(
            SocketMessages.OUTER_TYPE_MESSAGE,
            SocketMessages.responsePayload(status, port, NearbyLink.BLUETOOTH.channel, businessId, tag, 0, uuid),
            Vectors.SEED_D,
        )

    @Test
    fun startSendsPlainConnectIdThenPlainVersionDeviceIdEncryptionRequest() {
        val s = session(FixedSecureRandom(hex("00005ba0"), Vectors.SEED_A))
        val frames = sends(s.start(0L))
        assertEquals(2, frames.size, "connectId 一帧 + 版本/deviceId/加密请求一帧")

        val (f1, connectId) = open(frames[0])
        assertEquals(ChannelManagement.OUTER_TYPE, f1.outerType)
        assertTrue(!f1.isEncrypted, "connectId 帧是明文")
        assertTrue(ChannelManagement.decode(connectId).single() is ChannelManagement.Item.ConnectId)

        val (f2, request) = open(frames[1])
        assertTrue(!f2.isEncrypted, "握手请求帧是明文")
        val items = ChannelManagement.decode(request)
        val version = items.filterIsInstance<ChannelManagement.Item.Version>().single()
        assertTrue(version.request)
        assertEquals(ChannelManagement.LOCAL_VERSION.toLong(), version.version)
        val summary = items.filterIsInstance<ChannelManagement.Item.Summary>().single()
        assertTrue(summary.request, "摘要是请求（带本机 deviceId）")
        assertEquals(s.deviceId, ChannelManagement.textOf(summary.raw))
        val enc = items.filterIsInstance<ChannelManagement.Item.Encryption>().single()
        assertTrue(enc.request && enc.enabled, "开加密请求")
    }

    @Test
    fun phoneReplyCompletesHandshakeThenRequestAuthChannelSendsCmd3() {
        val s = session(FixedSecureRandom(hex("00005ba0"), Vectors.SEED_A, Vectors.SEED_B))
        s.start(0L)
        assertFailsWith<IllegalStateException>("握手没完成不能请求认证子通道") { s.requestAuthChannel(tag) }

        val done = s.feed(phoneHandshakeReply(Vectors.SEED_C)).filterIsInstance<NearbyEvent.HandshakeCompleted>().single()
        assertEquals(ChannelManagement.LOCAL_VERSION.toLong(), done.peerVersion)
        assertTrue(done.encrypted)
        assertNull(s.nextTickAtMillis, "握手完成后不再有超时")

        // 一帧加密的命令 3：通道 2、业务 19、协议 0、业务标签 = 手机名、不带 UUID。
        val (f, payload) = open(sends(s.requestAuthChannel(tag)).single())
        assertEquals(SocketMessages.OUTER_TYPE_MESSAGE, f.outerType)
        assertTrue(f.isEncrypted, "握手完成后命令 3 是加密帧")
        val tlv = Tlv.parseAll(payload).single()
        assertEquals(SocketMessages.CMD_CREATE_REQUEST, tlv.tag)
        val req = SocketMessages.parseRequest(tlv.valueView())
        assertEquals(NearbyLink.BLUETOOTH.channel, req.channel)
        assertEquals(NearbySession.BUSINESS_AUTH, req.businessId)
        assertEquals(0, req.protocol)
        assertEquals(phoneName, req.tag.toString(Charsets.UTF_8))
        assertNull(req.uuid)

        assertFailsWith<IllegalStateException>("只请求一次") { s.requestAuthChannel(tag) }
    }

    @Test
    fun acceptedCmd4YieldsAuthChannelReadyWithPortAndUuid() {
        val s = requested()
        val ready = s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8)).filterIsInstance<NearbyEvent.AuthChannelReady>().single()
        assertEquals(8, ready.port)
        assertEquals(fe36.toHex(), ready.uuid?.toHex())
    }

    @Test
    fun acceptedCmd4WithoutUuidHasNullUuid() {
        val ready = requested().feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8, uuid = null))
            .filterIsInstance<NearbyEvent.AuthChannelReady>().single()
        assertNull(ready.uuid)
    }

    @Test
    fun rejectedCmd4YieldsAuthChannelRejected() {
        val events = requested().feed(cmd4(SocketMessages.STATUS_REJECTED, 0, uuid = null))
        assertEquals(SocketMessages.STATUS_REJECTED, events.filterIsInstance<NearbyEvent.AuthChannelRejected>().single().status)
        assertTrue(events.none { it is NearbyEvent.AuthChannelReady })
    }

    @Test
    fun acceptedCmd4WithBadPortCountsAsRejected() {
        val events = requested().feed(cmd4(SocketMessages.STATUS_ACCEPTED, 0))
        assertTrue(events.any { it is NearbyEvent.InputDiscarded })
        assertEquals(SocketMessages.STATUS_REJECTED, events.filterIsInstance<NearbyEvent.AuthChannelRejected>().single().status)
    }

    @Test
    fun cmd4ForAnotherTagOrBusinessIsHandedOutAndTheRightOneStillCounts() {
        val s = requested()
        val wrongTag = s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8, tag = "别的手机".toByteArray()))
        assertTrue(wrongTag.none { it is NearbyEvent.AuthChannelReady || it is NearbyEvent.AuthChannelRejected })
        assertEquals(SocketMessages.CMD_CREATE_RESPONSE, wrongTag.filterIsInstance<NearbyEvent.Unhandled>().single().tag)
        val wrongBusiness = s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8, businessId = NearbySession.BUSINESS_DMSDP))
        assertTrue(wrongBusiness.single() is NearbyEvent.Unhandled)

        assertEquals(8, s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8)).filterIsInstance<NearbyEvent.AuthChannelReady>().single().port)
        // 只认第一条应答。
        assertTrue(s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 9)).single() is NearbyEvent.Unhandled)
    }

    @Test
    fun cmd4BeforeAnyRequestIsHandedOut() {
        val s = session(FixedSecureRandom(hex("00005ba0"), Vectors.SEED_A, Vectors.SEED_B))
        s.start(0L)
        s.feed(phoneHandshakeReply(Vectors.SEED_C))
        assertTrue(s.feed(cmd4(SocketMessages.STATUS_ACCEPTED, 8)).single() is NearbyEvent.Unhandled)
    }

    /**
     * 2026-11-22 实车（Mate 70）：认证通过后手机在控制通道上发来的资源层报文（外层 4，解密后 33 字节）。
     * 发起方模式下要和手机连上来时一样解析成数据通道协商。
     */
    @Test
    fun realPhoneDataChannelRequestAfterAuthParsesAsInPassiveMode() {
        val sample = hex("819f83839c818182818381848186818c7fbf857f83878089808b808a8091819280")
        val expected = DataChannelMessages.parseRequest(Tlv.parseAll(sample).single().valueView())

        val requested = requested().feed(phoneEncrypted(SocketMessages.OUTER_TYPE_RESOURCE, sample, Vectors.SEED_D))
            .filterIsInstance<NearbyEvent.DataChannelRequested>().single()
        assertEquals(expected.dataChannel, requested.dataChannel)
        assertEquals(expected.capability.toString(), requested.capability.toString())
        assertEquals(true, requested.capability.canBeGroupClient, "手机能当组成员（车机当组主）")
    }

    @Test
    fun handshakeTimesOut() {
        val s = session(FixedSecureRandom(hex("00005ba0"), Vectors.SEED_A))
        s.start(0L)
        val due = assertNotNull(s.nextTickAtMillis)
        assertTrue(s.onTick(due - 1).isEmpty(), "没到点不超时")
        assertEquals(listOf("HandshakeTimedOut"), s.onTick(due).map { it::class.simpleName })
    }

    @Test
    fun onlyBluetoothInitiatorRequestsAuthChannelAndModesAreValidated() {
        val passive = NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(hex("00005ba0")), NearbyLink.BLUETOOTH)
        assertTrue(!passive.initiator, "蓝牙通道默认车机是应答方")
        passive.start(0L)
        assertFailsWith<IllegalStateException> { passive.requestAuthChannel(tag) }

        assertFailsWith<IllegalArgumentException>("有线不当发起方") {
            NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(), NearbyLink.USB, initiator = true)
        }
        assertFailsWith<IllegalArgumentException>("WiFi 总是发起方") {
            NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(), NearbyLink.WIFI, initiator = false)
        }
        assertTrue(NearbySession(Vectors.HOST_1, Vectors.KEYS_16, Vectors.BT_1, FixedSecureRandom(), NearbyLink.WIFI).initiator)
    }
}
