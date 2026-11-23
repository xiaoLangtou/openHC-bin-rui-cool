package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 控制连接：分帧、明文加密协商、切换 CTR、时间同步、设备消息分派，以及整段会话的逐字节对照。 */
class ControlSessionTest {

    private val phone = Phone()

    /** 整段 TCP 保活会话：每个发出的字节都与另一份实现在同一随机数序列下的输出一致。 */
    @Test
    fun wholeTcpSessionMatchesCrossVectors() {
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
        assertIs<DmsdpAction.OpenControlListener>(
            session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST)).only<DmsdpAction>(),
        )
        assertEquals(CrossVectors.TCP_SESSION_REPLY, session.controlListenerReady(40001).only<DmsdpAction.Send>().bytes.toHex())
        assertEquals(CrossVectors.TCP_OPTIONS, session.onConnected(DmsdpChannel.CONTROL, 0).only<DmsdpAction.Send>().bytes.toHex())

        val select = session.feed(DmsdpChannel.CONTROL, phone.plainFrame(CRYPTO_SELECT + "\r\n"), 1_000_000)
        assertEquals(2, select.size)
        assertEquals(CrossVectors.TCP_CRYPTO_200, assertIs<DmsdpAction.Send>(select[0]).bytes.toHex())
        assertEquals(KeepAliveMode.TCP, assertIs<DmsdpEvent.EncryptionEnabled>(select[1]).keepAliveMode)
        assertEquals(1_001_000, session.nextTickAtMillis)

        val time = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText(
                "SET_PARAMETER * DMSDP/1.0\r\nCSeq: 007\r\nTimeServerPort: 40002\r\n" +
                    "TimeUsHigh: 1\r\nTimeUsLow: 4294967295\r\n\r\n",
            ),
            1_000_100,
        )
        assertEquals(CrossVectors.TCP_TIME_200, time.sends(DmsdpChannel.CONTROL).single().toHex())
        val sync = time.events().single() as DmsdpEvent.TimeSyncRequested
        assertEquals(listOf("40002", "1", "4294967295"), listOf(sync.timeServerPort, sync.timeUsHigh, sync.timeUsLow))

        val device = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 1_000_200)
        assertEquals(CrossVectors.TCP_DEVICE_101, device.sends(DmsdpChannel.CONTROL).single().toHex())
        assertEquals(Synthetic.PEER_ID, (device.events().single() as DmsdpEvent.PeerDeviceInfo).deviceId)

        val services = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedRecord(3, -1, hex("e8") + hex("0027ffff")),
            1_000_300,
        )
        assertEquals(CrossVectors.TCP_DEVICE_102, services.sends(DmsdpChannel.CONTROL).single().toHex())
        assertEquals(listOf("display1"), (services.events().single() as DmsdpEvent.ServicesRequested).answeredServiceIds)

        val management = session.sendDeviceData(1, 17, "{\"cmdId\":\"9\"}".toByteArray())
        assertEquals(CrossVectors.TCP_MANAGEMENT_9, management.only<DmsdpAction.Send>().bytes.toHex())

        assertTrue(session.feed(DmsdpChannel.CONTROL, phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 3\r\n\r\n"), 1_000_400).isEmpty())
        assertEquals(CrossVectors.TCP_KEEPALIVE_GET_2, session.onTick(1_001_000).only<DmsdpAction.Send>().bytes.toHex())
        assertEquals(CrossVectors.TCP_KEEPALIVE_GET_3, session.onTick(1_002_000).only<DmsdpAction.Send>().bytes.toHex())
    }

    /** 整段 UDP 保活会话：兼容格式双发和扩展格式都与另一份实现一致。 */
    @Test
    fun wholeUdpSessionMatchesCrossVectors() {
        val random = SequenceRandom()
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, random)
        session.feed(DmsdpChannel.SESSION, hex(CrossVectors.SESSION_NEG_REQUEST))
        session.controlListenerReady(40001)
        session.onConnected(DmsdpChannel.CONTROL, 0)
        val select = session.feed(
            DmsdpChannel.CONTROL,
            phone.plainFrame(CRYPTO_SELECT + "UdpKA: 1\r\nUdpKAport: 52000\r\n\r\n"),
            1_000_000,
        )
        assertEquals(3, select.size)
        assertEquals(CrossVectors.TCP_CRYPTO_200, assertIs<DmsdpAction.Send>(select[0]).bytes.toHex())
        assertEquals(52000, assertIs<DmsdpAction.OpenKeepAliveSocket>(select[1]).remotePort)
        assertEquals(KeepAliveMode.UDP, assertIs<DmsdpEvent.EncryptionEnabled>(select[2]).keepAliveMode)

        session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 007\r\nTimeServerPort: 40002\r\nTimeUsHigh: 1\r\nTimeUsLow: 4294967295\r\n\r\n"),
            1_000_100,
        )
        session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex(CrossVectors.PHONE_101)), 1_000_200)
        session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(3, -1, hex("e80027ffff")), 1_000_300)
        session.sendDeviceData(1, 17, "{\"cmdId\":\"9\"}".toByteArray())
        assertEquals(64, random.index)

        assertTrue(session.onTick(1_001_000).isEmpty(), "第 1 轮是奇数轮，不发")
        val pair = session.onTick(1_002_000).map { assertIs<DmsdpAction.SendDatagram>(it).bytes.toHex() }
        assertEquals(listOf(CrossVectors.UDP_UDP_LEGACY_1, CrossVectors.UDP_UDP_LEGACY_2), pair)

        val extended = hex("00000000000a0ade0000001e00000000")
        assertIs<DmsdpEvent.KeepAlivePeriodChanged>(session.onDatagram(phone.datagram(extended), 1_002_500).single())
        assertTrue(session.onTick(1_003_000).isEmpty())
        assertEquals(1_018_000, session.nextTickAtMillis, "扩展周期每轮 15 秒")
        val next = session.onTick(1_018_000).map { assertIs<DmsdpAction.SendDatagram>(it).bytes }
        assertEquals(CrossVectors.UDP_UDP_EXTENDED, next[0].toHex())
        assertContentEquals(extended, phone.decrypt(next[1].copyOfRange(3, next[1].size)))
    }

    /** 解码侧：另一份实现生成的密文帧直接喂进来（同一把合成密钥），能正确解开并分派。 */
    @Test
    fun decodesCrossVectorFrames() {
        val (session, _) = encryptedSession()
        val device = session.feed(DmsdpChannel.CONTROL, hex(CrossVectors.TCP_DEVICE_101))
        val info = device.events().single() as DmsdpEvent.PeerDeviceInfo
        assertEquals(Synthetic.SELF_ID, info.deviceId)
        assertEquals(4L, info.deviceType)
        assertEquals(1, device.sends(DmsdpChannel.CONTROL).size)
        val service = session.feed(DmsdpChannel.CONTROL, hex(CrossVectors.TCP_DEVICE_102)).single() as DmsdpEvent.PeerServiceInfo
        assertEquals(8L, service.serviceType)
        assertEquals("display1", service.serviceId)
        assertEquals(17, (session.feed(DmsdpChannel.CONTROL, hex(CrossVectors.TCP_MANAGEMENT_9)).single() as DmsdpEvent.UnhandledDeviceData).type)
        val time = (session.feed(DmsdpChannel.CONTROL, hex(CrossVectors.TCP_TIME_200)).single() as DmsdpEvent.UnhandledControlMessage).message
        assertEquals(200, time.statusCode)
        assertEquals("007", time.header("CSeq"))
        assertTrue(session.feed(DmsdpChannel.CONTROL, hex(CrossVectors.TCP_KEEPALIVE_GET_2)).isEmpty(), "认作保活")
        assertEquals("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 2\r\n\r\n", String(phone.openControlFrame(session.onTick(101_000).only<DmsdpAction.Send>().bytes)))

        val (udp, _) = encryptedSession(select = "UdpKA: 1\r\nUdpKAport: 52000\r\n")
        assertTrue(udp.onDatagram(hex(CrossVectors.UDP_UDP_LEGACY_1), 0).isEmpty())
        assertTrue((udp.onDatagram(hex(CrossVectors.UDP_EXTENDED_IV10), 0).single() as DmsdpEvent.KeepAlivePeriodChanged).extended)
        assertTrue(udp.onDatagram(hex(CrossVectors.UDP_UDP_EXTENDED), 0).isEmpty(), "已是扩展周期")
    }

    @Test
    fun optionsTextAndCryptoResponseAreExact() {
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
        val options = phone.openControlFrame(session.onConnected(DmsdpChannel.CONTROL, 0).only<DmsdpAction.Send>().bytes)
        assertEquals(
            "OPTIONS * DMSDP/1.0\r\nCryptoVer: 1\r\nCryptoAbility: 222\r\nCryptoAlg: aes128-ctr\r\nUdpKA: 1\r\n" +
                "KaRetry: 160\r\nLowDelay: 1\r\nCSeq: 1\r\n\r\n",
            String(options),
        )
        val response = session.feed(DmsdpChannel.CONTROL, phone.plainFrame(CRYPTO_SELECT.replace("CSeq: 1", "CSeq: 0042") + "\r\n"), 0)
        assertEquals(
            "DMSDP/1.0 200 OK\r\nCSeq: 0042\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\nPlatform: 0\r\n\r\n",
            String(phone.openControlFrame(response.sends(DmsdpChannel.CONTROL).single())),
            "CSeq 原样回显",
        )

        val custom = DmsdpConfig(
            host = Synthetic.host(), deviceId = Synthetic.SELF_ID, bluetoothMac = "0a:1b:2c:3d:4e:5f",
            localIp = Synthetic.LOCAL_IP, phoneIp = Synthetic.PHONE_IP,
            advertiseUdpKeepAlive = false, keepAliveRetry = 30, platform = 1,
        )
        assertEquals("0A:1B:2C:3D:4E:5F", custom.bluetoothMac)
        val other = DmsdpSession(custom, Synthetic.KEY, SequenceRandom())
        val text = String(phone.openControlFrame(other.onConnected(DmsdpChannel.CONTROL, 0).only<DmsdpAction.Send>().bytes))
        assertTrue("UdpKA: 0\r\nKaRetry: 30\r\n" in text, text)
        val reply = other.feed(DmsdpChannel.CONTROL, phone.plainFrame(CRYPTO_SELECT + "\r\n"), 0)
        assertTrue("Platform: 1\r\n" in String(phone.openControlFrame(reply.sends(DmsdpChannel.CONTROL).single())))
    }

    @Test
    fun controlFramesSurviveSplitAndStickyDelivery() {
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
        session.onConnected(DmsdpChannel.CONTROL, 0)
        val select = phone.plainFrame(CRYPTO_SELECT + "\r\n")
        for (i in 0 until select.size - 1) {
            assertTrue(session.feed(DmsdpChannel.CONTROL, select, i, 1, 0).isEmpty(), "第 $i 字节还不够一帧")
        }
        // 最后一个字节与一个加密帧粘在一起：明文 200 先编码，再切换，后面的密文帧按加密处理。
        val time = phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 2\r\nTimeServerPort: 1\r\nTimeUsHigh: 2\r\nTimeUsLow: 3\r\n\r\n")
        val out = session.feed(DmsdpChannel.CONTROL, byteArrayOf(select.last()) + time, 0)
        val sent = out.sends(DmsdpChannel.CONTROL)
        assertEquals(2, sent.size)
        assertEquals(0, sent[0][0].toInt(), "加密选择的 200 必须是明文")
        assertEquals(0xAB, sent[1][0].toInt() and 0xFF)
        assertEquals("DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n", String(phone.openControlFrame(sent[1])))
        assertIs<DmsdpEvent.EncryptionEnabled>(out[1])
        assertIs<DmsdpEvent.TimeSyncRequested>(out[3])
    }

    @Test
    fun encryptionStateIsEnforcedPerFrame() {
        fun plainSession() = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
            .also { it.onConnected(DmsdpChannel.CONTROL, 0) }

        val cases = listOf<Pair<String, (DmsdpSession) -> Unit>>(
            "加密前收到密文" to { it.feed(DmsdpChannel.CONTROL, phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n\r\n")) },
            "加密前收到设备消息" to { it.feed(DmsdpChannel.CONTROL, ControlFrames.encode(0, phone.record(3, -1, hex("e5")))) },
            "加密前的其他请求" to { it.feed(DmsdpChannel.CONTROL, phone.plainFrame("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n\r\n")) },
            "未知标志" to { it.feed(DmsdpChannel.CONTROL, hex("010003414243")) },
            "长度为 0" to { it.feed(DmsdpChannel.CONTROL, hex("000000")) },
            "缺空行" to { it.feed(DmsdpChannel.CONTROL, phone.plainFrame("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n")) },
            "头部没有冒号" to { it.feed(DmsdpChannel.CONTROL, phone.plainFrame("SET_PARAMETER * DMSDP/1.0\r\nCSeq 1\r\n\r\n")) },
            "头部非 ASCII" to { it.feed(DmsdpChannel.CONTROL, phone.plainFrame("SET_PARAMETER * DMSDP/1.0\r\nCSeq: １\r\n\r\n")) },
        )
        for ((name, action) in cases) {
            val session = plainSession()
            assertFailsWith<MalformedMessageException>(name) { action(session) }
            assertFailsWith<IllegalStateException>("$name 之后会话作废") { session.feed(DmsdpChannel.CONTROL, hex("00")) }
            assertNull(session.nextTickAtMillis)
            assertTrue(session.onTick(Long.MAX_VALUE).isEmpty())
        }

        val (encrypted, _) = encryptedSession()
        assertFailsWith<MalformedMessageException>("加密后又收到明文") {
            encrypted.feed(DmsdpChannel.CONTROL, phone.plainFrame("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n\r\n"))
        }
        val (short, _) = encryptedSession()
        assertFailsWith<MalformedMessageException>("密文不足一个 IV") {
            short.feed(DmsdpChannel.CONTROL, ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, ByteArray(15)))
        }
    }

    @Test
    fun cryptoSelectionIsValidatedBeforeAnswering() {
        val invalid = listOf(
            CRYPTO_SELECT.replace("CryptoSwitch: 222", "CryptoSwitch: 111") + "\r\n",
            CRYPTO_SELECT.replace("aes128-ctr", "aes256-gcm") + "\r\n",
            CRYPTO_SELECT.replace("CryptoVer: 1", "CryptoVer: 2") + "\r\n",
            CRYPTO_SELECT.replace("CSeq: 1", "CSeq: x") + "\r\n",
            CRYPTO_SELECT + "CryptoAlg: aes128-ctr\r\n\r\n",
            CRYPTO_SELECT + "Content-Length: 1\r\n\r\n",
            CRYPTO_SELECT + "UdpKA: 2\r\n\r\n",
            CRYPTO_SELECT + "UdpKA: 1\r\n\r\n",
        ) + listOf("0", "65536", "-1", "+1", "1.0", "999999", "x", "").map { CRYPTO_SELECT + "UdpKA: 1\r\nUdpKAport: $it\r\n\r\n" }
        for (text in invalid) {
            val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
            session.onConnected(DmsdpChannel.CONTROL, 0)
            assertFailsWith<MalformedMessageException>(text) { session.feed(DmsdpChannel.CONTROL, phone.plainFrame(text)) }
            assertTrue(!session.isEncrypted)
        }
        // UdpKA 选 0 时即使给了端口也走 TCP；手机对 OPTIONS 的 200 不算选择。
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
        session.onConnected(DmsdpChannel.CONTROL, 0)
        val response = session.feed(DmsdpChannel.CONTROL, phone.plainFrame("DMSDP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"))
        assertEquals(200, (response.single() as DmsdpEvent.UnhandledControlMessage).message.statusCode)
        assertTrue(!session.isEncrypted)
        val tcp = session.feed(DmsdpChannel.CONTROL, phone.plainFrame(CRYPTO_SELECT + "UdpKA: 0\r\nUdpKAport: 12345\r\n\r\n"))
        assertEquals(KeepAliveMode.TCP, (tcp.last() as DmsdpEvent.EncryptionEnabled).keepAliveMode)
        assertTrue(tcp.none { it is DmsdpAction.OpenKeepAliveSocket })
    }

    /**
     * 加密后认不出的载荷（手机在声音数据连接连上前，经控制连接送来的声音）：跳过、不断开，
     * 前 3 个和之后每第 100 个交出事件；之后的文字消息照常处理。
     */
    @Test
    fun unrecognizedBinaryPayloadIsSkippedNotFatal() {
        val (session, _) = encryptedSession()
        val binary = ByteArray(1400) { (it * 7 + 3).toByte() }
        val events = (1..101).flatMap { session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(binary)).events() }
        val skipped = events.map { it as DmsdpEvent.UnrecognizedControlPayload }
        assertEquals(listOf(1, 2, 3, 100), skipped.map { it.count })
        assertEquals(1400, skipped[0].size)
        assertContentEquals(binary.copyOf(DmsdpEvent.UnrecognizedControlPayload.HEAD_BYTES), skipped[0].head)
        // 碰巧带空行、但不以大写字母开头的二进制也跳过（新会话里是第 1 个，交出事件）。
        val (other, _) = encryptedSession()
        val crlf = byteArrayOf(0x01, 0x0D, 0x0A, 0x0D, 0x0A)
        assertIs<DmsdpEvent.UnrecognizedControlPayload>(other.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(crlf)).events().single())
        val out = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 7\r\nTimeServerPort: 0\r\nTimeUsHigh: 1\r\nTimeUsLow: 2\r\n\r\n"),
        )
        assertIs<DmsdpEvent.TimeSyncRequested>(out.events().single())
    }

    @Test
    fun timeSyncAnswersOnlyCSeqAndKeepsRawValues() {
        val (session, _) = encryptedSession()
        val out = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 5\r\nTimeServerPort: 0\r\nTimeUsHigh: -1\r\nTimeUsLow: abc\r\n\r\n"),
        )
        assertEquals("DMSDP/1.0 200 OK\r\nCSeq: 5\r\n\r\n", String(phone.openControlFrame(out.sends(DmsdpChannel.CONTROL).single())))
        val event = out.events().single() as DmsdpEvent.TimeSyncRequested
        assertEquals(listOf("0", "-1", "abc"), listOf(event.timeServerPort, event.timeUsHigh, event.timeUsLow))

        // CSeq 无效的时间同步不应答，作为未处理消息交出。
        val bad = session.feed(
            DmsdpChannel.CONTROL,
            phone.encryptedText("SET_PARAMETER * DMSDP/1.0\r\nTimeServerPort: 1\r\nTimeUsHigh: 2\r\nTimeUsLow: 3\r\n\r\n"),
        )
        assertIs<DmsdpEvent.UnhandledControlMessage>(bad.single())
    }

    /** 以后的数据会话请求（例如音频）：原样交出，头部顺序和正文都保留，不应答、不断开。 */
    @Test
    fun unknownEncryptedRequestsBecomeUnhandledEvents() {
        val (session, _) = encryptedSession()
        val body = "msdp_trigger_method: OPTIONS\r\n"
        val text = "SET_PARAMETER * DMSDP/1.0\r\nServiceID: speaker1\r\nDataSessionID: 7\r\nCSeq: 9\r\n" +
            "Simple: 1\r\nParaAudio: x\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val message = (session.feed(DmsdpChannel.CONTROL, phone.encryptedText(text)).single() as DmsdpEvent.UnhandledControlMessage).message
        assertEquals("SET_PARAMETER", message.method)
        assertEquals(
            listOf("ServiceID", "DataSessionID", "CSeq", "Simple", "ParaAudio", "Content-Length"),
            message.headers.map { it.first },
        )
        assertEquals("9", message.header("cseq"))
        assertEquals(body, String(message.body))

        val response = session.feed(DmsdpChannel.CONTROL, phone.encryptedText("DMSDP/1.0 200 OK\r\nCSeq: 3\r\n\r\n")).single()
        assertEquals(200, (response as DmsdpEvent.UnhandledControlMessage).message.statusCode)
        assertTrue(session.isEncrypted)
    }

    @Test
    fun deviceRecordsAreDispatchedInOrder() {
        val (session, _) = encryptedSession()
        val payload = phone.record(3, 110, hex("58")) +
            phone.record(3, -1, hex("e900000002")) +
            phone.record(1, 17, "{\"cmdId\":\"9\"}".toByteArray()) +
            phone.record(5, -1, hex("e5") + Ea.encode(40078)) +
            phone.record(1, 546, "{}".toByteArray())
        val out = session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(payload))
        assertEquals(5, out.size)
        assertEquals(110, assertIs<DmsdpEvent.UnhandledDeviceData>(out[0]).type)
        assertEquals(2, assertIs<DmsdpEvent.DisconnectTypeNotified>(out[1]).disconnectType)
        assertEquals(17, assertIs<DmsdpEvent.UnhandledDeviceData>(out[2]).type)
        assertEquals(40078, assertIs<DmsdpEvent.DisplayPortAnnounced>(out[3]).port)
        // module 1 / type 546 是 JSON，现在解析成结构化管理消息（见 ManagementMessages）。
        assertEquals(546, assertIs<DmsdpEvent.ManagementParsed>(out[4]).type)

        val truncated = phone.record(3, -1, hex("e900000002")).copyOf(12)
        assertFailsWith<MalformedMessageException> { session.feed(DmsdpChannel.CONTROL, phone.encryptedFrame(truncated)) }
    }

    @Test
    fun displayPortMustBeExact() {
        for ((data, port) in listOf("e581" to 1, "e5ff" to 127, "e50081" to 128, "e57f7f83" to 65535)) {
            val (session, _) = encryptedSession()
            val out = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(5, -1, hex(data)))
            assertEquals(port, (out.single() as DmsdpEvent.DisplayPortAnnounced).port)
        }
        for (bad in listOf("e5", "e580", "e5000084", "e58181", "e5000000")) {
            val (session, _) = encryptedSession()
            assertFailsWith<MalformedMessageException>(bad) {
                session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(5, -1, hex(bad)))
            }
        }
        val (session, _) = encryptedSession()
        val other = session.feed(DmsdpChannel.CONTROL, phone.encryptedRecord(5, -1, hex("e681"))).single()
        assertEquals(102, (other as DmsdpEvent.UnsupportedDeviceCommand).command)
    }

    @Test
    fun sendDeviceDataRequiresEncryptionAndLimits() {
        val session = DmsdpSession(Synthetic.config(), Synthetic.KEY, SequenceRandom())
        assertFailsWith<IllegalStateException> { session.sendDeviceData(1, 546, "{}".toByteArray()) }
        val (encrypted, _) = encryptedSession()
        val frame = encrypted.sendDeviceData(1, 546, ByteArray(DeviceRecords.MAX_SEND_DATA)).only<DmsdpAction.Send>().bytes
        assertEquals(3 + 0xFFFF, frame.size)
        val record = DeviceRecords.parseAll(phone.openControlFrame(frame)).single()
        assertEquals(1L, record.module)
        assertEquals(546, record.type)
        assertFailsWith<IllegalArgumentException> { encrypted.sendDeviceData(1, 546, ByteArray(DeviceRecords.MAX_SEND_DATA + 1)) }
        assertFailsWith<IllegalArgumentException> { encrypted.sendDeviceData(1, 546, ByteArray(0)) }
        val negative = encrypted.sendDeviceData(0xFFFF_FFFFL, Int.MIN_VALUE, hex("01")).only<DmsdpAction.Send>().bytes
        assertEquals("24ffffffff800000000001", phone.openControlFrame(negative).copyOf(11).toHex())
    }

    @Test
    fun closingControlStopsKeepAliveAndRejectsPartialFrames() {
        val (session, _) = encryptedSession()
        session.feed(DmsdpChannel.CONTROL, phone.encryptedText("GET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\n\r\n").copyOf(5))
        assertFailsWith<MalformedMessageException> { session.onClosed(DmsdpChannel.CONTROL, 0) }

        val (clean, _) = encryptedSession()
        assertTrue(clean.onClosed(DmsdpChannel.CONTROL, 0).isEmpty())
        assertNull(clean.nextTickAtMillis)
        assertTrue(clean.onTick(Long.MAX_VALUE).isEmpty())
        assertFailsWith<IllegalStateException> { clean.feed(DmsdpChannel.CONTROL, hex("00")) }

        val (closed, _) = encryptedSession()
        assertTrue(closed.close().isEmpty())
        assertTrue(closed.onTick(Long.MAX_VALUE).isEmpty())
        assertFailsWith<IllegalStateException> { closed.feed(DmsdpChannel.CONTROL, hex("00")) }
    }

    @Test
    fun messageParsingKeepsOrderAndRawValues() {
        val message = DmsdpMessage.parse("GET_PARAMETER rtsp://x DMSDP/1.0\r\nB:  2 \r\na: 1\r\nB: 3\r\n\r\nbody".toByteArray())
        assertEquals("GET_PARAMETER rtsp://x DMSDP/1.0", message.startLine)
        assertEquals(listOf("B" to "2", "a" to "1", "B" to "3"), message.headers)
        assertEquals("2", message.header("b"))
        assertTrue(message.hasDuplicateHeaders())
        assertEquals("body", String(message.body))
        assertTrue(!message.contentLengthMatches())
        assertNull(message.statusCode)
        assertEquals(
            "OPTIONS * DMSDP/1.0\r\nA: 1\r\n\r\nxy",
            String(DmsdpMessage.encode("OPTIONS * DMSDP/1.0", listOf("A" to "1"), "xy".toByteArray())),
        )
    }
}
