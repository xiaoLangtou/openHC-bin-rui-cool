package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.MicEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.MicEvent
import io.github.gcbond.openhc.core.dmsdp.audio.MicProfile
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 麦克风数据会话接进编排器：真实的 dmsdp 层（nearby、认证、投屏用假的），手机侧的消息用 JCE 现场加密。
 * 验证手机 SETUP 后在车机 IP 上开监听、开好才回 200、手机连上后送出的声音写到这条连接上、
 * 麦克风连接出错或被关只影响麦克风、会话结束时麦克风先于设备会话关闭。
 */
class MicSessionWiringTest {

    private class MicRecorder : MicStreamListener {
        val events = ArrayList<MicEvent>()
        override fun onMicEvent(event: MicEvent) {
            events += event
        }
    }

    private class HybridLayers(config: ReceiverConfig, mic: MicStreamListener) : Layers {
        private val real = ProductionLayers(config, AudioStreamListener.NONE, mic)
        val fake = FakeLayers()
        override fun nearby(): NearbyLayer = fake.nearby()
        override fun auth(): AuthLayer = fake.auth()
        override fun dmsdp(sessionKey: ByteArray, phoneIp: String, phoneVersion: String?) = real.dmsdp(sessionKey, phoneIp, phoneVersion)
        override fun hisight(sessionKey: ByteArray, sink: AccessUnitSink) = fake.hisight(sessionKey, sink)
    }

    private fun config(mic: MicSettings? = MicSettings()) = ReceiverConfig(
        identity = Synthetic.IDENTITY,
        bluetoothAddress = Synthetic.BLUETOOTH,
        localIp = Synthetic.LOCAL_IP,
        controlPort = Synthetic.PORT,
        display = DisplaySettings(1280, 720, 30, dpi = 160, density = 1.0),
        authEnvelopeVersion = Synthetic.ENVELOPE_VERSION,
        trustStore = InMemoryTrustStore(),
        wallClock = { 1_767_312_000_000L },
        timeZone = TimeZone.getTimeZone("UTC"),
        mic = mic,
    )

    /** 手机一侧：JCE 的 AES/CTR，密钥是合成的 00..0f。 */
    private object PhoneSide {
        fun crypt(mode: Int, iv: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(mode, SecretKeySpec(Synthetic.KEY, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data, offset, length)
        }

        fun plainFrame(text: String): ByteArray {
            val body = text.toByteArray(Charsets.US_ASCII)
            return byteArrayOf(0x00, (body.size ushr 8).toByte(), body.size.toByte()) + body
        }

        fun encryptedFrame(text: String, ivSeed: Int): ByteArray {
            val iv = ByteArray(16) { (ivSeed + it).toByte() }
            val body = text.toByteArray(Charsets.US_ASCII)
            val payload = iv + crypt(Cipher.ENCRYPT_MODE, iv, body, 0, body.size)
            return byteArrayOf(0xAB.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        }

        /** 解开一帧（控制消息或麦克风数据），返回明文。 */
        fun open(frame: ByteArray): ByteArray = crypt(Cipher.DECRYPT_MODE, frame.copyOfRange(3, 19), frame, 19, frame.size - 19)

        fun openText(frame: ByteArray): String = String(open(frame), Charsets.US_ASCII)

        fun trigger(method: String, cseq: Int): String {
            val body = "msdp_trigger_method: $method\r\n"
            return "SET_PARAMETER * DMSDP/1.0\r\nCSeq: $cseq\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: 2718\r\n" +
                "Simple: 1\r\nParaAudio: SampleRate=16000;Format=16;ChannelNum=1;Codec=1\r\nContent-Length: ${body.length}\r\n\r\n$body"
        }

        fun setup(cseq: Int) = "SETUP * DMSDP/1.0\r\nCSeq: $cseq\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: 2718\r\n" +
            "Transport: RTP/AVP/UDP;unicast;client_port=51000--1\r\n\r\n"
    }

    /** 走到设备控制连接已加密的会话。 */
    private class Driver(config: ReceiverConfig, val recorder: MicRecorder = MicRecorder()) {
        val layers = HybridLayers(config, recorder)
        val session = ReceiverSession(config, AccessUnitSink { }, layers)
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        val now = 5_000L
        private var ivSeed = 0x10

        init {
            layers.fake.auth.onFeed = { listOf(HiCarAuthAction.Succeeded(Synthetic.KEY.copyOf(), "peer", AuthMode.TRUSTED_RECONNECT)) }
            layers.fake.nearby.onFeed = { text ->
                when (text) {
                    "auth?" -> listOf(Synthetic.authRequest())
                    "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                    else -> emptyList()
                }
            }
            run(session.start(now))
            run(session.onListening(listens.getValue(SessionChannel.CONTROL).id, Synthetic.PORT, now))
            run(session.onAccepted(conn(SessionChannel.CONTROL), Synthetic.PHONE_IP, now))
            feed(SessionChannel.CONTROL, "auth?".toByteArray())
            run(session.onListening(listens.getValue(SessionChannel.AUTH).id, 40001, now))
            run(session.onAccepted(conn(SessionChannel.AUTH), Synthetic.PHONE_IP, now))
            feed(SessionChannel.AUTH, "done".toByteArray())
            feed(SessionChannel.CONTROL, "dmsdp?".toByteArray())
            run(session.onListening(listens.getValue(SessionChannel.DMSDP_SESSION).id, 40002, now))
            run(session.onAccepted(conn(SessionChannel.DMSDP_SESSION), Synthetic.PHONE_IP, now))
            feed(SessionChannel.DMSDP_SESSION, "7f81900000000000000000000000000000000019e7".hexToBytes())
            run(session.onListening(listens.getValue(SessionChannel.DMSDP_CONTROL).id, 40003, now))
            run(session.onAccepted(conn(SessionChannel.DMSDP_CONTROL), Synthetic.PHONE_IP, now))
            feed(
                SessionChannel.DMSDP_CONTROL,
                PhoneSide.plainFrame("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n\r\n"),
            )
        }

        fun run(outputs: List<SessionOutput>): List<SessionOutput> {
            for (o in outputs) if (o is SessionAction.Listen) listens[o.channel] = o
            return outputs
        }

        fun conn(channel: SessionChannel) = listens.getValue(channel).acceptId
        fun listener(channel: SessionChannel) = listens.getValue(channel).id
        fun feed(channel: SessionChannel, bytes: ByteArray) = run(session.onData(conn(channel), bytes, 0, bytes.size, now))
        fun phoneSays(text: String) = feed(SessionChannel.DMSDP_CONTROL, PhoneSide.encryptedFrame(text, ivSeed++))
        fun writes(outputs: List<SessionOutput>, channel: SessionChannel) =
            outputs.filterIsInstance<SessionAction.Write>().filter { it.id == conn(channel) }.map { it.bytes }
        fun closes(outputs: List<SessionOutput>) = outputs.filterIsInstance<SessionAction.Close>().map { it.id }

        /** 触发、SETUP、端口开好（45678）、手机连上。 */
        fun toStreaming() {
            phoneSays(PhoneSide.trigger("OPTIONS", 21))
            phoneSays(PhoneSide.setup(22))
            run(session.onListening(listener(SessionChannel.MIC), 45678, now))
            run(session.onAccepted(conn(SessionChannel.MIC), Synthetic.PHONE_IP, now))
        }
    }

    @Test
    fun micSettingsReachTheDeviceDeclaration() {
        assertEquals(MicProfile.PCM_16K_MONO, config().dmsdpConfig(Synthetic.PHONE_IP).mic?.profile)
        assertEquals(null, config(MicSettings(profile = null)).dmsdpConfig(Synthetic.PHONE_IP).mic?.profile)
        assertEquals(null, config(mic = null).dmsdpConfig(Synthetic.PHONE_IP).mic, "不打开就不声明")
    }

    @Test
    fun setupOpensAListenerOnTheCarAndAnswersOnlyOnceItIsOpen() {
        val d = Driver(config())
        val trigger = d.phoneSays(PhoneSide.trigger("OPTIONS", 21))
        val replies = d.writes(trigger, SessionChannel.DMSDP_CONTROL).map { PhoneSide.openText(it) }
        assertEquals(2, replies.size)
        assertTrue(replies[0].startsWith("DMSDP/1.0 200 OK\r\nServiceID: mic1\r\n"))
        assertTrue(replies[1].startsWith("OPTIONS * DMSDP/1.0\r\nServiceID: mic1\r\nServiceType: 2\r\n"))
        assertTrue(trigger.none { it is SessionAction.Listen }, "还没收到 SETUP，不开端口")

        val setup = d.phoneSays(PhoneSide.setup(22))
        val listen = setup.filterIsInstance<SessionAction.Listen>().single()
        assertEquals(SessionChannel.MIC, listen.channel)
        assertEquals(Synthetic.LOCAL_IP, listen.host)
        assertEquals(0, listen.port, "端口由系统分配")
        assertTrue(d.writes(setup, SessionChannel.DMSDP_CONTROL).isEmpty(), "开好之前不回 200")

        val ready = d.run(d.session.onListening(listen.id, 45678, d.now))
        assertEquals(
            "DMSDP/1.0 200 OK\r\nServiceID: mic1\r\nServiceType: 2\r\nDataSessionID: 2718\r\nCSeq: 22\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=51000--1;server_port=45678--1\r\n\r\n",
            PhoneSide.openText(d.writes(ready, SessionChannel.DMSDP_CONTROL).single()),
        )
        d.run(d.session.onAccepted(listen.acceptId, Synthetic.PHONE_IP, d.now))
        assertIs<MicEvent.Started>(d.recorder.events.last())
    }

    @Test
    fun capturedAudioIsWrittenToTheMicConnection() {
        val d = Driver(config())
        assertTrue(d.session.sendMicAudio(ByteArray(640), 0, 640, d.now).isEmpty(), "还没有麦克风连接")
        d.toStreaming()
        val pcm = ByteArray(640) { (it * 3).toByte() }
        val out = d.session.sendMicAudio(pcm, 0, pcm.size, d.now)
        val rtp = PhoneSide.open(d.writes(out, SessionChannel.MIC).single())
        assertEquals(0x80, rtp[0].toInt() and 0xFF)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x04, 0x4C), rtp.copyOfRange(8, 12), "SSRC")
        assertContentEquals(byteArrayOf(0x00, 0x01), rtp.copyOfRange(12, 14), "唯一一片")
        assertContentEquals(pcm, rtp.copyOfRange(24, rtp.size))
        assertTrue(out.none { it is SessionEvent.Ended })
    }

    @Test
    fun micConnectionTroubleOnlyAffectsTheMic() {
        val d = Driver(config())
        d.toStreaming()
        val error = d.run(d.session.onIoError(d.conn(SessionChannel.MIC), "连接被重置", d.now))
        assertTrue(error.none { it is SessionEvent.Ended })
        assertEquals(setOf(d.conn(SessionChannel.MIC), d.listener(SessionChannel.MIC)), d.closes(error).toSet(), "连接和监听都关")
        assertEquals(MicEndReason.PEER_CLOSED, d.recorder.events.filterIsInstance<MicEvent.Stopped>().single().reason)
        assertTrue(d.session.sendMicAudio(ByteArray(640), 0, 640, d.now).isEmpty())
        assertFalse(d.session.isEnded)

        // 下一次：端口开不了。
        d.phoneSays(PhoneSide.trigger("OPTIONS", 30).replace("2718", "2719"))
        d.phoneSays(PhoneSide.setup(31).replace("2718", "2719"))
        val failed = d.run(d.session.onIoError(d.listener(SessionChannel.MIC), "端口被占", d.now))
        assertTrue(failed.none { it is SessionEvent.Ended })
        assertEquals(MicEndReason.LISTEN_FAILED, d.recorder.events.filterIsInstance<MicEvent.Stopped>().last().reason)
        assertFalse(d.session.isEnded)
    }

    @Test
    fun peerCloseAndTeardownCloseTheMicResources() {
        val d = Driver(config())
        d.toStreaming()
        val closed = d.run(d.session.onClosed(d.conn(SessionChannel.MIC), d.now))
        assertTrue(d.listener(SessionChannel.MIC) in d.closes(closed), "手机关掉连接后监听也关")
        assertFalse(d.session.isEnded)

        val again = Driver(config())
        again.toStreaming()
        val teardown = again.phoneSays(PhoneSide.trigger("TEARDOWN", 23))
        assertTrue("CSeq: 23\r\n" in PhoneSide.openText(again.writes(teardown, SessionChannel.DMSDP_CONTROL).single()))
        assertEquals(
            setOf(again.conn(SessionChannel.MIC), again.listener(SessionChannel.MIC)),
            again.closes(teardown).toSet(),
        )
        assertEquals(MicEndReason.PEER_TEARDOWN, again.recorder.events.filterIsInstance<MicEvent.Stopped>().single().reason)
    }

    @Test
    fun sessionEndClosesTheMicBeforeTheDeviceSession() {
        val d = Driver(config())
        d.toStreaming()
        val stop = d.session.stop(d.now)
        val closes = d.closes(stop)
        val mic = closes.indexOf(d.conn(SessionChannel.MIC))
        val control = closes.indexOf(d.conn(SessionChannel.DMSDP_CONTROL))
        assertTrue(mic in 0 until control, "麦克风先于设备控制连接关闭：$closes")
        assertTrue(d.listener(SessionChannel.MIC) in closes)
        assertIs<SessionEvent.Ended>(stop.last())
        assertEquals(MicEndReason.SESSION_CLOSED, d.recorder.events.filterIsInstance<MicEvent.Stopped>().single().reason)
        assertTrue(d.session.sendMicAudio(ByteArray(640), 0, 640, d.now).isEmpty(), "结束之后不再送")
    }
}
