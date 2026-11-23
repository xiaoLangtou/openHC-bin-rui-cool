package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 用真实的 dmsdp、hisight 层走一遍编排（nearby 和认证仍用假的）：手机一侧的数据按协议格式现场构造，
 * 投屏部分用 core:hisight 的合成对照向量（密钥 00..0f）。验证正式实现下的交接顺序和动作。
 */
class RealLayersFlowTest {

    private object Vectors {
        val root: JsonObject by lazy {
            val text = RealLayersFlowTest::class.java.getResourceAsStream("/session-vectors.json")!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            Json.parseToJsonElement(text).jsonObject
        }
        val key get() = root.getValue("key").jsonPrimitive.content.hexToBytes()
        val wallMillis get() = root.getValue("wallMillis").jsonPrimitive.long
        val config get() = root.getValue("config").jsonObject
        val setup get() = root.getValue("setup").jsonObject
        val controlInputs: List<ByteArray?>
            get() = root.getValue("control").jsonArray.map { step ->
                val o = step.jsonObject
                if (o.getValue("op").jsonPrimitive.content == "feed") o.getValue("in").jsonPrimitive.content.hexToBytes() else null
            }
        val videoStream get() = root.getValue("video").jsonObject.getValue("stream").jsonPrimitive.content.hexToBytes()
        val frameHashes
            get() = root.getValue("video").jsonObject.getValue("frames").jsonArray.map {
                it.jsonObject.getValue("esSha256").jsonPrimitive.content
            }
    }

    /** 手机一侧的设备会话数据：格式见 core:dmsdp。 */
    private object PhoneDmsdp {
        /** 业务 20 的首轮协商请求（module 1、16 字节、首字节 0）。 */
        val NEGOTIATION = "7f81900000000000000000000000000000000019e7".hexToBytes()

        fun plainFrame(text: String): ByteArray {
            val body = text.toByteArray(Charsets.US_ASCII)
            return byteArrayOf(0x00, (body.size ushr 8).toByte(), body.size.toByte()) + body
        }

        fun encryptedFrame(key: ByteArray, plain: ByteArray, ivSeed: Int): ByteArray {
            val iv = ByteArray(16) { (ivSeed + it).toByte() }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val payload = iv + cipher.doFinal(plain)
            return byteArrayOf(0xAB.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        }

        fun ea(value: Int): ByteArray {
            val out = ArrayList<Byte>()
            var rest = value
            while (true) {
                val group = rest and 0x7F
                rest = rest ushr 7
                if (rest == 0) {
                    out += (group or 0x80).toByte()
                    return out.toByteArray()
                }
                out += group.toByte()
            }
        }

        /** 设备记录：`$` ‖ BE32 module ‖ BE32 type ‖ BE16 长度 ‖ 数据。 */
        fun record(module: Int, type: Int, data: ByteArray): ByteArray {
            val header = java.nio.ByteBuffer.allocate(11).put(0x24).putInt(module).putInt(type)
                .putShort(data.size.toShort()).array()
            return header + data
        }

        fun displayPort(port: Int) = record(5, -1, ea(101) + ea(port))

        /** UDP 保活明文：BE32(0) ‖ BE16(10) ‖ 10 字节 0。 */
        val KEEPALIVE = ByteArray(16).also { it[5] = 0x0A }
    }

    private class HybridLayers(config: ReceiverConfig) : Layers {
        private val real = ProductionLayers(config)
        val fake = FakeLayers()

        override fun nearby(): NearbyLayer = fake.nearby()
        override fun auth(): AuthLayer = fake.auth()
        override fun dmsdp(sessionKey: ByteArray, phoneIp: String) = real.dmsdp(sessionKey, phoneIp)
        override fun hisight(sessionKey: ByteArray, sink: AccessUnitSink) = real.hisight(sessionKey, sink)
    }

    private fun vectorConfig(): ReceiverConfig {
        val c = Vectors.config
        return Synthetic.config(
            display = DisplaySettings(
                width = c.getValue("screenWidth").jsonPrimitive.int,
                height = c.getValue("screenHeight").jsonPrimitive.int,
                fps = c.getValue("fps").jsonPrimitive.int,
                dpi = c.getValue("dpi").jsonPrimitive.int,
                density = 1.0,
                gop = c.getValue("gop").jsonPrimitive.int,
                bitrate = c.getValue("bitrate").jsonPrimitive.int,
            ),
            wallMillis = Vectors.wallMillis,
        )
    }

    private class Ids {
        val byChannel = HashMap<SessionChannel, Int>()
        val listeners = HashMap<SessionChannel, SessionAction.Listen>()
        var udp: SessionAction.OpenUdp? = null

        fun note(outputs: List<SessionOutput>): List<SessionOutput> {
            for (o in outputs) {
                when (o) {
                    is SessionAction.Listen -> {
                        listeners[o.channel] = o
                        byChannel[o.channel] = o.acceptId
                    }
                    is SessionAction.Connect -> byChannel[o.channel] = o.id
                    is SessionAction.OpenUdp -> udp = o
                    else -> Unit
                }
            }
            return outputs
        }
    }

    private fun List<SessionOutput>.writesTo(id: Int) = filterIsInstance<SessionAction.Write>().filter { it.id == id }.map { it.bytes }

    @Test
    fun realDeviceSessionAndProjectionFlow() {
        val hashes = ArrayList<String>()
        val nalTypes = ArrayList<List<Int>>()
        val sink = AccessUnitSink { unit ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(unit.data, unit.offset, unit.length)
            hashes += digest.digest().toHex()
            nalTypes += unit.nalTypes().toList()
        }
        val config = vectorConfig()
        val layers = HybridLayers(config)
        layers.fake.auth.onFeed = {
            listOf(HiCarAuthAction.Succeeded(Vectors.key, "peer", AuthMode.TRUSTED_RECONNECT))
        }
        layers.fake.nearby.onFeed = { text ->
            when (text) {
                "auth?" -> listOf(Synthetic.authRequest())
                "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                else -> emptyList()
            }
        }
        val session = ReceiverSession(config, sink, layers)
        val ids = Ids()
        var now = 10_000L
        fun run(outputs: List<SessionOutput>) = ids.note(outputs)
        fun feed(channel: SessionChannel, bytes: ByteArray) = run(session.onData(ids.byChannel.getValue(channel), bytes, 0, bytes.size, now))

        // nearby 与认证（假的）：走到认证成功、业务 20 连上。
        run(session.start(now))
        run(session.onListening(ids.listeners.getValue(SessionChannel.CONTROL).id, Synthetic.PORT, now))
        run(session.onAccepted(ids.byChannel.getValue(SessionChannel.CONTROL), Synthetic.PHONE_IP, now))
        feed(SessionChannel.CONTROL, "auth?".bytes())
        run(session.onListening(ids.listeners.getValue(SessionChannel.AUTH).id, 40001, now))
        run(session.onAccepted(ids.byChannel.getValue(SessionChannel.AUTH), Synthetic.PHONE_IP, now))
        // 假认证在握手前直接成功，没有 HandshakeAnswered：业务 20 请求放到成功之后发。
        feed(SessionChannel.AUTH, "done".bytes())
        feed(SessionChannel.CONTROL, "dmsdp?".bytes())
        run(session.onListening(ids.listeners.getValue(SessionChannel.DMSDP_SESSION).id, 40002, now))
        run(session.onAccepted(ids.byChannel.getValue(SessionChannel.DMSDP_SESSION), Synthetic.PHONE_IP, now))

        // 真实的设备会话：首轮协商 → 另开控制监听 → 端口写回业务 20。
        val nego = feed(SessionChannel.DMSDP_SESSION, PhoneDmsdp.NEGOTIATION)
        val controlListen = assertIs<SessionAction.Listen>(nego.single())
        assertEquals(SessionChannel.DMSDP_CONTROL, controlListen.channel)
        assertEquals(Synthetic.LOCAL_IP, controlListen.host)
        val reply = run(session.onListening(controlListen.id, 40003, now))
            .writesTo(ids.byChannel.getValue(SessionChannel.DMSDP_SESSION)).single()
        assertEquals("7f8190009c43", reply.copyOf(6).toHex(), "应答里是新开的控制端口 40003")

        // 控制连接：明文 OPTIONS（声明 UDP 保活）→ 手机明文选加密和 UDP 保活。
        val dmsdpControl = ids.byChannel.getValue(SessionChannel.DMSDP_CONTROL)
        val options = run(session.onAccepted(dmsdpControl, Synthetic.PHONE_IP, now)).writesTo(dmsdpControl).single()
        val optionsText = String(options, 3, options.size - 3, Charsets.US_ASCII)
        assertTrue(optionsText.startsWith("OPTIONS * DMSDP/1.0\r\n") && "UdpKA: 1\r\n" in optionsText, optionsText)
        val select = feed(
            SessionChannel.DMSDP_CONTROL,
            PhoneDmsdp.plainFrame(
                "SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n" +
                    "UdpKA: 1\r\nUdpKAport: 50000\r\n\r\n",
            ),
        )
        assertEquals(0x00, select.writesTo(dmsdpControl).single()[0].toInt(), "加密选择的 200 用明文回")
        val udp = assertIs<SessionAction.OpenUdp>(select.single { it is SessionAction.OpenUdp })
        assertEquals(listOf(Synthetic.LOCAL_IP, Synthetic.PHONE_IP, "50000"), listOf(udp.localHost, udp.remoteHost, udp.remotePort.toString()))
        assertTrue(select.any { it is SessionEvent.DeviceSessionReady })

        // 加密的设备记录通告投屏端口 → 车机去连手机。
        val announce = feed(SessionChannel.DMSDP_CONTROL, PhoneDmsdp.encryptedFrame(Vectors.key, PhoneDmsdp.displayPort(7000), 0x40))
        val hisightConnect = assertIs<SessionAction.Connect>(announce.single { it is SessionAction.Connect })
        assertEquals(listOf(SessionChannel.HISIGHT_CONTROL.name, Synthetic.PHONE_IP, "7000"),
            listOf(hisightConnect.channel.name, hisightConnect.host, hisightConnect.port.toString()))

        // 真实的投屏层：连上后发 ANNOUNCE，之后按对照向量喂手机的控制消息。
        val hisightControl = hisightConnect.id
        assertEquals(1, run(session.onConnected(hisightControl, now)).writesTo(hisightControl).size)
        val inputs = Vectors.controlInputs
        // 第 0 步是 start，之后第一个不是 feed 的步骤是「媒体连上」。
        val mediaStep = (1 until inputs.size).first { inputs[it] == null }
        var negotiated: List<SessionOutput> = emptyList()
        for (i in 1 until mediaStep) negotiated = feed(SessionChannel.HISIGHT_CONTROL, inputs[i]!!)
        val setup = Vectors.setup
        val video = negotiated.filterIsInstance<SessionAction.Connect>().single()
        assertEquals(SessionChannel.VIDEO, video.channel, "SETUP 之后先只连视频")
        assertEquals(setup.getValue("serverPort").jsonPrimitive.int, video.port)
        assertEquals("VideoNegotiated(640x480@30，触摸关，按键关)", negotiated.filterIsInstance<SessionEvent.VideoNegotiated>().single().toString())

        val remote = assertIs<SessionAction.Connect>(run(session.onConnected(video.id, now)).single())
        assertEquals(SessionChannel.REMOTE_CONTROL, remote.channel)
        assertEquals(setup.getValue("remotePort").jsonPrimitive.int, remote.port)
        assertEquals(1, run(session.onConnected(remote.id, now)).writesTo(hisightControl).size, "两条都连上才发 PLAY")
        assertTrue(feed(SessionChannel.HISIGHT_CONTROL, inputs[mediaStep + 1]!!).contains(SessionEvent.PlayAccepted))

        // 视频按任意切分喂入，帧原样交给回调，第一帧时报开始出画面。
        val stream = Vectors.videoStream
        val started = ArrayList<SessionOutput>()
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(1000, stream.size - pos)
            started += session.onData(video.id, stream, pos, n, now)
            pos += n
        }
        assertEquals(Vectors.frameHashes, hashes)
        assertEquals(listOf(7, 8, 5), nalTypes[1])
        assertEquals(1, started.count { it is SessionEvent.VideoStarted })

        // UDP 保活：加密后 1 秒第一轮（不发），2 秒第二轮连发两个。
        assertTrue(session.onTick(now + 1_000).none { it is SessionAction.SendDatagram })
        val keepAlive = session.onTick(now + 2_000).filterIsInstance<SessionAction.SendDatagram>()
        assertEquals(2, keepAlive.size)
        assertTrue(keepAlive.all { it.id == udp.id && it.bytes.size == 35 })
        val datagram = PhoneDmsdp.encryptedFrame(Vectors.key, PhoneDmsdp.KEEPALIVE, 0x60)
        assertEquals(emptyList(), session.onDatagram(udp.id, datagram, 0, datagram.size, now + 2_100))

        // 手机的保活与结束触发：回 200、发 TEARDOWN，整个会话由上到下关闭。
        now += 2_200
        for (i in mediaStep + 2 until inputs.size - 1) feed(SessionChannel.HISIGHT_CONTROL, inputs[i]!!)
        val end = feed(SessionChannel.HISIGHT_CONTROL, inputs.last()!!)
        assertEquals(2, end.writesTo(hisightControl).size, "回 200 再发 TEARDOWN")
        val ended = assertIs<SessionEvent.Ended>(end.last())
        assertEquals("投屏结束（PEER_TEARDOWN）", ended.reason.toString())
        val closed = end.filterIsInstance<SessionAction.Close>().map { it.id }
        assertEquals(
            listOf(hisightControl, video.id, remote.id, udp.id, dmsdpControl, controlListen.id),
            closed.take(6),
        )
        assertTrue(session.isEnded)
    }

    @Test
    fun realAuthLayerAnswersWithIdentityFromAssets() {
        val config = Synthetic.config()
        val auth = ProductionLayers(config).auth()
        val handshake = """{"version":"X","oper_type":1,"data":"{\"deviceId\":\"${"F".repeat(64)}\",\"has_pin\":true,\"usb_connection\":true}"}"""
        val out = auth.feed(handshake.bytes(), 0, handshake.bytes().size)
        val answered = out.filterIsInstance<HiCarAuthAction.HandshakeAnswered>().single()
        assertEquals(AuthMode.PIN_PAIRING, answered.mode)
        val pin = answered.pinCode!!
        assertTrue(pin.length == 6 && pin.all { it in '0'..'9' })
        val reply = Json.parseToJsonElement(out.filterIsInstance<HiCarAuthAction.Send>().single().bytes.text()).jsonObject
        assertEquals(Synthetic.ENVELOPE_VERSION, reply.getValue("version").jsonPrimitive.content)
        assertEquals(pin, reply.getValue("pin_code").jsonPrimitive.content)
        val info = Json.parseToJsonElement(reply.getValue("data").jsonPrimitive.content).jsonObject
        assertEquals(
            mapOf(
                "CONNECT_ABILITY_KEY" to "2",
                "CAR_MODE_ID" to "ABCDEF01",
                "CAR_BRAND" to "OpenHC 测试",
                "DEVICE_TYPE" to "4",
                "DAY_NIGHT_MODE" to "day",
                "DRIVING_POSITION" to "left",
            ),
            info.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            listOf("CONNECT_ABILITY_KEY", "CAR_MODE_ID", "CAR_BRAND", "DEVICE_TYPE", "DAY_NIGHT_MODE", "DRIVING_POSITION"),
            info.keys.toList(),
        )
    }

    @Test
    fun realAuthLayerRefusesReconnectWithoutTrust() {
        val auth = ProductionLayers(Synthetic.config()).auth()
        val handshake = """{"version":"X","oper_type":1,"data":"{\"deviceId\":\"${"E".repeat(64)}\",\"has_pin\":false,\"usb_connection\":true}"}"""
        val out = auth.feed(handshake.bytes(), 0, handshake.bytes().size)
        assertTrue(out.none { it is HiCarAuthAction.Send })
        val failed = out.filterIsInstance<HiCarAuthAction.Failed>().single()
        assertIs<AuthFailure.NotTrusted>(failed.failure)
    }

    @Test
    fun realNearbyLayerStartsWithPlainConnectId() {
        val config = Synthetic.config()
        val nearby = ProductionLayers(config).nearby()
        val frame = assertIs<NearbyOutput.Send>(nearby.start(0).single()).bytes
        assertEquals(0x7F, frame[0].toInt() and 0xFF)
        assertEquals(5_000L, nearby.nextTickAtMillis)
        // 不合法的数据只出诊断，不中断。
        val note = assertIs<NearbyOutput.Note>(nearby.feed(byteArrayOf(1, 2, 3), 0, 3).single())
        assertTrue(note.text.startsWith("InputDiscarded"), note.text)
        assertIs<NearbyOutput.HandshakeTimedOut>(nearby.onTick(5_000).single())
    }

    @Test
    fun configIsValidatedUpFront() {
        val config = Synthetic.config()
        assertEquals(91, Synthetic.config(localIp = "192.168.66.150").carInfo().size)
        assertEquals(64, config.deviceId.length)
        assertEquals("0A:1B:2C:3D:4E:5F", config.bluetoothText)
        assertFailsWith<IllegalArgumentException> { Synthetic.config(localIp = "192.168.66.1").carInfo() }
        assertFailsWith<IllegalArgumentException> { Synthetic.config(localIp = "192.168.066.150") }
        assertFailsWith<IllegalArgumentException> { Synthetic.config(controlBindHost = "::") }
        assertFailsWith<IllegalArgumentException> { Synthetic.config(display = DisplaySettings(0, 720, 30, 160, 1.0)) }
        assertFailsWith<IllegalArgumentException> {
            ReceiverConfig(Synthetic.IDENTITY, "not-an-address", Synthetic.LOCAL_IP, 1, DisplaySettings(1, 1, 1, 1, 1.0), "v",
                io.github.gcbond.openhc.core.auth.InMemoryTrustStore())
        }
        assertFailsWith<IllegalArgumentException> {
            ReceiverConfig(Synthetic.IDENTITY, null, Synthetic.LOCAL_IP, 1, DisplaySettings(1, 1, 1, 1, 1.0), "",
                io.github.gcbond.openhc.core.auth.InMemoryTrustStore())
        }
        // 取不到蓝牙地址时与 nearby 层一样按 02:00:00:00:00:00 报。
        val noBluetooth = ReceiverConfig(Synthetic.IDENTITY, null, Synthetic.LOCAL_IP, 1, DisplaySettings(1, 1, 1, 1, 1.0), "v",
            io.github.gcbond.openhc.core.auth.InMemoryTrustStore())
        assertEquals("02:00:00:00:00:00", noBluetooth.bluetoothText)
        assertEquals(io.github.gcbond.openhc.core.nearby.NearbySession.deviceId(Synthetic.IDENTITY.host, null), noBluetooth.deviceId)
    }
}
