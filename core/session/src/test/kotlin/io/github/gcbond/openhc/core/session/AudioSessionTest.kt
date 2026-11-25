package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.common.hexToBytes
import io.github.gcbond.openhc.core.common.toHex
import io.github.gcbond.openhc.core.dmsdp.DmsdpAction
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.DmsdpEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 音频数据会话接进编排器：开数据端口、开好才发 SETUP、端口被占换一个、手机连入的字节交给 dmsdp、
 * 音频连接出错或被关只影响声音、拆除和会话结束时关掉音频资源、车机的焦点变化发给手机。
 * 最后用真实的 dmsdp 层走一遍端到端。
 */
class AudioSessionTest {

    /** 用假的层走到设备会话就绪（与 [ReceiverSessionTest] 同样的命令约定）。 */
    private class Driver {
        val layers = FakeLayers()
        val session = ReceiverSession(Synthetic.config(audio = AudioSettings()), AccessUnitSink { }, layers)
        val now = 1_000L
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        val ids = HashMap<Int, SessionChannel>()

        init {
            layers.nearby.onFeed = { text ->
                when (text) {
                    "auth?" -> listOf(NearbyOutput.HandshakeCompleted(true), Synthetic.authRequest())
                    "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                    else -> emptyList()
                }
            }
            val original = layers.dmsdp.onFeed
            layers.dmsdp.onFeed = { channel, text ->
                when {
                    channel == DmsdpChannel.CONTROL && text == "audio" ->
                        listOf(DmsdpAction.Send(DmsdpChannel.CONTROL, "audio-200".bytes()), DmsdpAction.OpenAudioListeners(AudioTransport.TCP))
                    channel == DmsdpChannel.CONTROL && text == "audio-udp" ->
                        listOf(DmsdpAction.Send(DmsdpChannel.CONTROL, "audio-200".bytes()), DmsdpAction.OpenAudioListeners(AudioTransport.UDP))
                    channel == DmsdpChannel.CONTROL && text == "audio-teardown" ->
                        listOf(DmsdpAction.CloseAudioTransport, DmsdpEvent.AudioSessionTornDown("77", "测试"))
                    else -> original(channel, text)
                }
            }
        }

        fun record(outputs: List<SessionOutput>): List<SessionOutput> {
            for (o in outputs) {
                if (o is SessionAction.Listen) {
                    listens[o.channel] = o
                    ids[o.id] = o.channel
                    ids[o.acceptId] = o.channel
                }
            }
            return outputs
        }

        fun listener(channel: SessionChannel) = listens.getValue(channel).id
        fun conn(channel: SessionChannel) = listens.getValue(channel).acceptId
        fun listening(channel: SessionChannel, port: Int) = record(session.onListening(listener(channel), port, now))
        fun accepted(channel: SessionChannel) = record(session.onAccepted(conn(channel), Synthetic.PHONE_IP, now))
        fun data(channel: SessionChannel, text: String) = text.bytes().let { record(session.onData(conn(channel), it, 0, it.size, now)) }

        fun toDeviceReady() {
            record(session.start(now))
            listening(SessionChannel.CONTROL, Synthetic.PORT)
            accepted(SessionChannel.CONTROL)
            data(SessionChannel.CONTROL, "auth?")
            listening(SessionChannel.AUTH, 40001)
            accepted(SessionChannel.AUTH)
            data(SessionChannel.AUTH, "hello")
            data(SessionChannel.AUTH, "done")
            data(SessionChannel.CONTROL, "dmsdp?")
            listening(SessionChannel.DMSDP_SESSION, 40002)
            accepted(SessionChannel.DMSDP_SESSION)
            data(SessionChannel.DMSDP_SESSION, "nego")
            listening(SessionChannel.DMSDP_CONTROL, 40003)
            accepted(SessionChannel.DMSDP_CONTROL)
            data(SessionChannel.DMSDP_CONTROL, "crypto")
        }

        /** 手机触发音频：返回车机开的数据端口。 */
        fun triggerAudio(): Int {
            val out = data(SessionChannel.DMSDP_CONTROL, "audio")
            val listensOut = out.filterIsInstance<SessionAction.Listen>()
            assertEquals(listOf(SessionChannel.AUDIO), listensOut.map { it.channel }, "只开数据端口，不再另开 P+1")
            return listensOut[0].port
        }

        fun writes(outputs: List<SessionOutput>) = outputs.filterIsInstance<SessionAction.Write>().map { it.bytes.text() }
        fun closes(outputs: List<SessionOutput>) = outputs.filterIsInstance<SessionAction.Close>().map { it.id }
    }

    @Test
    fun audioSettingsCodecReachesTheSpeakerDeclaration() {
        val default = Synthetic.config(audio = AudioSettings()).dmsdpConfig(Synthetic.PHONE_IP).speaker
        assertEquals(AudioCodec.AAC, default?.codec, "默认 AAC")
        assertTrue(default!!.audioProfile.startsWith("{\"codecs\":[],"), "AAC 用空数组的形状")
        val pcm = Synthetic.config(audio = AudioSettings(codec = AudioCodec.PCM)).dmsdpConfig(Synthetic.PHONE_IP).speaker
        assertEquals(AudioCodec.PCM, pcm?.codec)
        assertTrue(pcm!!.audioProfile.startsWith("{\"codecs\":[1],"), "PCM 声明 codecs [1]")
        assertTrue(pcm.multiBus && "MultiAudioBusPolicy" in pcm.audioProfile, "默认声明多音频总线")
        assertEquals(null, Synthetic.config().dmsdpConfig(Synthetic.PHONE_IP).speaker, "不打开就不声明")
        val tuned = Synthetic.config(audio = AudioSettings(codec = AudioCodec.PCM, multiBus = false, aacPhones = setOf("00112233aabbccdd")))
            .dmsdpConfig(Synthetic.PHONE_IP).speaker!!
        assertFalse(tuned.multiBus)
        assertEquals(setOf("00112233aabbccdd"), tuned.aacPhones, "退回 AAC 的手机名单原样带到扬声器配置")
    }

    @Test
    fun dataPortIsOpenedOnAnEvenPortAndSetupWaitsForIt() {
        val d = Driver()
        d.toDeviceReady()
        val out = d.record(d.session.onData(d.conn(SessionChannel.DMSDP_CONTROL), "audio".bytes(), 0, 5, d.now))
        assertEquals(listOf("audio-200"), d.writes(out), "200 先写出去")
        val data = d.listens.getValue(SessionChannel.AUDIO)
        assertEquals(listOf(data), out.filterIsInstance<SessionAction.Listen>(), "只开一个监听")
        assertEquals(Synthetic.LOCAL_IP, data.host)
        assertEquals(0, data.port % 2, "数据端口是偶数")
        assertTrue(data.port in 20_000..59_998)
        assertTrue(out.indexOf(data) > out.indexOfFirst { it is SessionAction.Write }, "先写 200 再开监听")
        assertTrue("dmsdp.audioListenersReady(${data.port})" !in d.layers.calls, "监听开好之前不发 SETUP")

        val setup = d.listening(SessionChannel.AUDIO, data.port)
        assertEquals(listOf("setup:${data.port}"), d.writes(setup))
        assertTrue("dmsdp.audioListenersReady(${data.port})" in d.layers.calls)
    }

    @Test
    fun busyPortIsRetriedWithAnotherPortAndGivesUpAfterEightTries() {
        val d = Driver()
        d.toDeviceReady()
        d.triggerAudio()
        repeat(7) { attempt ->
            val first = d.listens.getValue(SessionChannel.AUDIO)
            val out = d.record(d.session.onIoError(first.id, "端口被占", d.now))
            assertTrue(out.none { it is SessionEvent.Ended }, "第 ${attempt + 1} 次失败不结束会话")
            assertTrue(first.id in d.closes(out) && first.acceptId in d.closes(out), "失败的那个先关掉")
            val again = out.filterIsInstance<SessionAction.Listen>()
            assertEquals(listOf(SessionChannel.AUDIO), again.map { it.channel }, "换一个端口重开")
        }
        val last = d.record(d.session.onIoError(d.listener(SessionChannel.AUDIO), "端口被占", d.now))
        assertTrue(last.none { it is SessionAction.Listen })
        assertTrue("dmsdp.audioListenersFailed" in d.layers.calls)
        assertFalse(d.session.isEnded)
    }

    @Test
    fun audioConnectionFeedsDmsdpAndItsTroublesOnlyAffectSound() {
        val d = Driver()
        d.toDeviceReady()
        val port = d.triggerAudio()
        d.listening(SessionChannel.AUDIO, port)
        d.accepted(SessionChannel.AUDIO)
        assertTrue("dmsdp.onConnected(AUDIO)" in d.layers.calls)
        d.data(SessionChannel.AUDIO, "pcm")
        assertTrue("dmsdp.feed(AUDIO,pcm)" in d.layers.calls)

        // 数据连接出错：只关它，会话继续。
        val error = d.record(d.session.onIoError(d.conn(SessionChannel.AUDIO), "连接被重置", d.now))
        assertEquals(listOf(d.conn(SessionChannel.AUDIO)), d.closes(error))
        assertTrue(error.any { it is SessionEvent.Diagnostic })
        assertTrue(error.none { it is SessionEvent.Ended })
        assertTrue("dmsdp.onClosed(AUDIO)" in d.layers.calls)
        assertFalse(d.session.isEnded)

        // 拆除：剩下的监听关掉。
        val teardown = d.data(SessionChannel.DMSDP_CONTROL, "audio-teardown")
        assertEquals(setOf(d.listener(SessionChannel.AUDIO)), d.closes(teardown).toSet())
        assertFalse(d.session.isEnded)
    }

    /**
     * 音频数据会话走 UDP：绑数据端口（偶数）收数据报，绑好才发 SETUP；第一个数据报当成手机开始发（onConnected 一次），
     * 每个数据报按到达顺序喂给协议层；端口绑不上换一个再试；拆除时关掉这个端口。
     */
    @Test
    fun udpDataSessionBindsThePortAndFeedsDatagrams() {
        val d = Driver()
        d.toDeviceReady()
        val busy = d.record(d.session.onData(d.conn(SessionChannel.DMSDP_CONTROL), "audio-udp".bytes(), 0, 9, d.now))
        val first = busy.filterIsInstance<SessionAction.BindUdp>().single()
        assertTrue(busy.none { it is SessionAction.Listen }, "UDP 不开 TCP 监听")
        // 端口被占：换一个再绑。
        val retry = d.record(d.session.onIoError(first.id, "地址已被占用", d.now))
        val bind = retry.filterIsInstance<SessionAction.BindUdp>().single()
        assertEquals(SessionChannel.AUDIO, bind.channel)
        assertEquals(Synthetic.LOCAL_IP, bind.localHost)
        assertEquals(0, bind.localPort % 2, "数据端口是偶数")
        assertFalse("dmsdp.audioListenersReady(${bind.localPort})" in d.layers.calls.joinToString(), "绑好之前不发 SETUP")
        d.record(d.session.onListening(bind.id, bind.localPort, d.now))
        assertTrue(d.layers.calls.any { it.startsWith("dmsdp.audioListenersReady") }, d.layers.calls.toString())
        for (text in listOf("rtp1", "rtp2")) {
            val bytes = text.bytes()
            d.record(d.session.onDatagram(bind.id, bytes, 0, bytes.size, d.now))
        }
        assertEquals(1, d.layers.calls.count { it == "dmsdp.onConnected(AUDIO)" }, "第一个数据报才算连上，只算一次")
        assertTrue("dmsdp.feed(AUDIO,rtp1)" in d.layers.calls && "dmsdp.feed(AUDIO,rtp2)" in d.layers.calls)
        val teardown = d.data(SessionChannel.DMSDP_CONTROL, "audio-teardown")
        assertTrue(bind.id in d.closes(teardown), "拆除时关掉 UDP 端口")
        assertFalse(d.session.isEnded)
    }

    @Test
    fun peerClosingTheAudioConnectionOnlyAffectsSound() {
        val d = Driver()
        d.toDeviceReady()
        val port = d.triggerAudio()
        d.listening(SessionChannel.AUDIO, port)
        d.accepted(SessionChannel.AUDIO)
        val closed = d.record(d.session.onClosed(d.conn(SessionChannel.AUDIO), d.now))
        assertTrue(closed.none { it is SessionEvent.Ended })
        assertTrue("dmsdp.onClosed(AUDIO)" in d.layers.calls)
        assertFalse(d.session.isEnded)
    }

    @Test
    fun carFocusChangeIsWrittenToTheDeviceControlConnection() {
        val d = Driver()
        assertTrue(d.session.sendAudioFocus(CarAudioFocus.LOST_TRANSIENT).isEmpty(), "设备会话还没建起来时什么都不发")
        d.toDeviceReady()
        val out = d.session.sendAudioFocus(CarAudioFocus.LOST_TRANSIENT)
        assertEquals(listOf("focus:-2"), d.writes(out))
        assertEquals(d.conn(SessionChannel.DMSDP_CONTROL), out.filterIsInstance<SessionAction.Write>().single().id)
        d.session.stop(d.now)
        assertTrue(d.session.sendAudioFocus(CarAudioFocus.GAINED).isEmpty(), "会话结束后什么都不发")
    }

    @Test
    fun sessionEndClosesAudioBeforeTheDeviceSession() {
        val d = Driver()
        d.toDeviceReady()
        val port = d.triggerAudio()
        d.listening(SessionChannel.AUDIO, port)
        d.accepted(SessionChannel.AUDIO)
        val stop = d.session.stop(d.now)
        val closes = d.closes(stop)
        val audioConn = closes.indexOf(d.conn(SessionChannel.AUDIO))
        val dmsdpControl = closes.indexOf(d.conn(SessionChannel.DMSDP_CONTROL))
        assertTrue(audioConn in 0 until dmsdpControl, "音频先于设备控制连接关闭：$closes")
        assertTrue(d.listener(SessionChannel.AUDIO) in closes)
        assertIs<SessionEvent.Ended>(stop.last())
    }

    // ---------------- 真实的 dmsdp 层端到端 ----------------

    private class AudioRecorder : AudioStreamListener {
        val events = ArrayList<AudioEvent>()
        val raw = ByteArrayOutputStream()
        val packets = ArrayList<ByteArray>()

        override fun onAudioEvent(event: AudioEvent) {
            events += event
        }

        override fun onAudioRawData(stream: AudioStreamInfo, data: ByteArray, offset: Int, length: Int) {
            raw.write(data, offset, length)
        }

        override fun onAudioPacket(packet: AudioPacket) {
            packets += packet.copyPayload()
        }
    }

    /** nearby、认证、投屏用假的，dmsdp 用正式实现（带音频出口）。 */
    private class HybridLayers(config: ReceiverConfig, audio: AudioStreamListener) : Layers {
        private val real = ProductionLayers(config, audio)
        val fake = FakeLayers()
        override fun nearby(): NearbyLayer = fake.nearby()
        override fun auth(): AuthLayer = fake.auth()
        override fun dmsdp(sessionKey: ByteArray, phoneIp: String, phoneVersion: String?) = real.dmsdp(sessionKey, phoneIp, phoneVersion)
        override fun hisight(sessionKey: ByteArray, sink: AccessUnitSink) = fake.hisight(sessionKey, sink)
    }

    /** 手机一侧：JCE 的 AES/CTR 现场加密，密钥是合成的 00..0f。 */
    private object PhoneSide {
        fun ctr(plain: ByteArray, ivSeed: Int): ByteArray {
            val iv = ByteArray(16) { (ivSeed + it).toByte() }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Synthetic.KEY, "AES"), IvParameterSpec(iv))
            return iv + cipher.doFinal(plain)
        }

        fun decrypt(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Synthetic.KEY, "AES"), IvParameterSpec(sealed.copyOf(16)))
            return cipher.doFinal(sealed, 16, sealed.size - 16)
        }

        fun plainFrame(text: String): ByteArray {
            val body = text.toByteArray(Charsets.US_ASCII)
            return byteArrayOf(0x00, (body.size ushr 8).toByte(), body.size.toByte()) + body
        }

        fun encryptedFrame(text: String, ivSeed: Int): ByteArray {
            val payload = ctr(text.toByteArray(Charsets.US_ASCII), ivSeed)
            return byteArrayOf(0xAB.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        }

        fun openFrame(frame: ByteArray): String = String(decrypt(frame.copyOfRange(3, frame.size)), Charsets.US_ASCII)

        fun trigger(method: String, cseq: Int): String {
            val body = "msdp_trigger_method: $method\r\n"
            return "SET_PARAMETER * DMSDP/1.0\r\ncseq: $cseq\r\nserviceid: speaker1\r\nservicetype: 3\r\ndatasessionid: 314\r\n" +
                "simple: 1\r\nparaaudio: SampleRate=48000;Format=16;ChannelNum=2;Codec=0\r\ncontent-length: ${body.length}\r\n\r\n$body"
        }

        /** 4 字节大端长度 ‖ IV16 ‖ 加密的 RTP（载荷是 1024 帧的双声道正弦）。 */
        fun audioFrame(sequence: Int): Pair<ByteArray, ByteArray> {
            val pcm = ByteArray(4096)
            for (i in 0 until 1024) {
                val v = (6000 * sin(2 * PI * 440 * (sequence * 1024 + i) / 48_000.0)).roundToInt()
                for (c in 0 until 2) {
                    pcm[i * 4 + c * 2] = v.toByte()
                    pcm[i * 4 + c * 2 + 1] = (v shr 8).toByte()
                }
            }
            val rtp = byteArrayOf(0x80.toByte(), 97, 0, sequence.toByte(), 0, 0, 0, 0, 0x0A, 0x0B, 0x0C, 0x0D) + pcm
            val sealed = ctr(rtp, 0x30 + sequence)
            val n = sealed.size
            return (byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + sealed) to pcm
        }
    }

    @Test
    fun realDeviceSessionCarriesAudioEndToEnd() {
        val audio = AudioRecorder()
        val config = Synthetic.config(audio = AudioSettings())
        val layers = HybridLayers(config, audio)
        layers.fake.auth.onFeed = { listOf(HiCarAuthAction.Succeeded(Synthetic.KEY.copyOf(), "peer", AuthMode.TRUSTED_RECONNECT)) }
        layers.fake.nearby.onFeed = { text ->
            when (text) {
                "auth?" -> listOf(Synthetic.authRequest())
                "dmsdp?" -> listOf(Synthetic.dmsdpRequest())
                else -> emptyList()
            }
        }
        val session = ReceiverSession(config, AccessUnitSink { }, layers)
        val listens = HashMap<SessionChannel, SessionAction.Listen>()
        val now = 5_000L
        fun run(outputs: List<SessionOutput>): List<SessionOutput> {
            for (o in outputs) if (o is SessionAction.Listen) listens[o.channel] = o
            return outputs
        }
        fun conn(channel: SessionChannel) = listens.getValue(channel).acceptId
        fun feed(channel: SessionChannel, bytes: ByteArray) = run(session.onData(conn(channel), bytes, 0, bytes.size, now))
        fun writes(outputs: List<SessionOutput>, channel: SessionChannel) =
            outputs.filterIsInstance<SessionAction.Write>().filter { it.id == conn(channel) }.map { it.bytes }

        run(session.start(now))
        run(session.onListening(listens.getValue(SessionChannel.CONTROL).id, Synthetic.PORT, now))
        run(session.onAccepted(conn(SessionChannel.CONTROL), Synthetic.PHONE_IP, now))
        feed(SessionChannel.CONTROL, "auth?".bytes())
        run(session.onListening(listens.getValue(SessionChannel.AUTH).id, 40001, now))
        run(session.onAccepted(conn(SessionChannel.AUTH), Synthetic.PHONE_IP, now))
        feed(SessionChannel.AUTH, "done".bytes())
        feed(SessionChannel.CONTROL, "dmsdp?".bytes())
        run(session.onListening(listens.getValue(SessionChannel.DMSDP_SESSION).id, 40002, now))
        run(session.onAccepted(conn(SessionChannel.DMSDP_SESSION), Synthetic.PHONE_IP, now))
        feed(SessionChannel.DMSDP_SESSION, "7f81900000000000000000000000000000000019e7".hexToBytes())
        run(session.onListening(listens.getValue(SessionChannel.DMSDP_CONTROL).id, 40003, now))
        run(session.onAccepted(conn(SessionChannel.DMSDP_CONTROL), Synthetic.PHONE_IP, now))
        feed(
            SessionChannel.DMSDP_CONTROL,
            PhoneSide.plainFrame("SET_PARAMETER * DMSDP/1.0\r\nCSeq: 1\r\nCryptoVer: 1\r\nCryptoSwitch: 222\r\nCryptoAlg: aes128-ctr\r\n\r\n"),
        )

        // 手机触发：先回 200，再开数据端口。
        val trigger = feed(SessionChannel.DMSDP_CONTROL, PhoneSide.encryptedFrame(PhoneSide.trigger("OPTIONS", 21), 0x10))
        assertEquals(
            "DMSDP/1.0 200 OK\r\nServiceID: speaker1\r\nServiceType: 3\r\nDataSessionID: 314\r\nCSeq: 21\r\n\r\n",
            PhoneSide.openFrame(writes(trigger, SessionChannel.DMSDP_CONTROL).single()),
        )
        val port = listens.getValue(SessionChannel.AUDIO).port

        // 端口开好才发 SETUP。
        val setup = run(session.onListening(listens.getValue(SessionChannel.AUDIO).id, port, now))
        val setupText = PhoneSide.openFrame(writes(setup, SessionChannel.DMSDP_CONTROL).single())
        assertTrue(setupText.startsWith("SETUP * DMSDP/1.0\r\nServiceID: speaker1\r\n"), setupText)
        assertTrue("CSeq: 2\r\n" in setupText && "TCP: 1\r\nCodec: 1\r\n" in setupText, setupText)
        assertTrue(setupText.endsWith("client_port=$port--1\r\n\r\n"), setupText)

        // 手机应答、连上数据端口推流。
        val answer = feed(SessionChannel.DMSDP_CONTROL, PhoneSide.encryptedFrame("DMSDP/1.0 200 OK\r\nCSeq: 2\r\n\r\n", 0x20))
        assertTrue(answer.any { it is SessionEvent.Diagnostic && "AudioSessionEstablished" in it.message })
        run(session.onAccepted(conn(SessionChannel.AUDIO), Synthetic.PHONE_IP, now))
        val frames = (0 until 4).map { PhoneSide.audioFrame(it) }
        val wire = frames.fold(ByteArray(0)) { acc, frame -> acc + frame.first }
        var pos = 0
        while (pos < wire.size) {
            val n = minOf(700, wire.size - pos)
            assertTrue(session.onData(conn(SessionChannel.AUDIO), wire, pos, n, now).none { it is SessionEvent.Ended })
            pos += n
        }
        assertContentEquals(wire, audio.raw.toByteArray())
        assertEquals(4, audio.packets.size)
        frames.forEachIndexed { i, frame -> assertContentEquals(frame.second, audio.packets[i]) }

        // 车机这边失去焦点：经设备控制连接告诉手机（module 4 / type -2，数据是本机设备 ID）。
        val focus = run(session.sendAudioFocus(CarAudioFocus.LOST_TRANSIENT))
        val record = PhoneSide.decrypt(writes(focus, SessionChannel.DMSDP_CONTROL).single().let { it.copyOfRange(3, it.size) })
        assertEquals("24" + "00000004" + "fffffffe", record.copyOf(9).toHex(), "\$ ‖ module 4 ‖ type -2")
        assertEquals(config.deviceId, String(record, 11, record.size - 11, Charsets.UTF_8))
        assertTrue(audio.events.any { it is AudioEvent.CarFocusSent && it.change == CarAudioFocus.LOST_TRANSIENT })

        // 手机拆除：回 200，关掉音频连接和监听。
        val teardown = feed(SessionChannel.DMSDP_CONTROL, PhoneSide.encryptedFrame(PhoneSide.trigger("TEARDOWN", 22), 0x40))
        assertTrue("CSeq: 22\r\n" in PhoneSide.openFrame(writes(teardown, SessionChannel.DMSDP_CONTROL).single()))
        val closed = teardown.filterIsInstance<SessionAction.Close>().map { it.id }.toSet()
        assertEquals(setOf(conn(SessionChannel.AUDIO), listens.getValue(SessionChannel.AUDIO).id), closed)
        val ended = audio.events.filterIsInstance<AudioEvent.Ended>().single()
        assertEquals(AudioEndReason.PEER_TEARDOWN, ended.reason)
        assertEquals("200 OK", ended.summary.setupAnswer?.substringAfter("DMSDP/1.0 "))
        assertEquals(wire.size.toLong(), ended.summary.dataBytes)
        assertFalse(session.isEnded)
    }
}
