package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.dmsdp.audio.AudioCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioDepacketizer
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.AudioEvent
import io.github.gcbond.openhc.core.dmsdp.audio.AudioFraming
import io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket
import io.github.gcbond.openhc.core.dmsdp.audio.AudioRoute
import io.github.gcbond.openhc.core.dmsdp.audio.AudioRouter
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamInfo
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.AudioSummary
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PcmByteOrder
import io.github.gcbond.openhc.core.dmsdp.audio.PcmCheck
import io.github.gcbond.openhc.core.dmsdp.audio.PcmVerdict
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneRef
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneStateMessages
import io.github.gcbond.openhc.core.dmsdp.audio.SpeakerPlan

/**
 * 扬声器服务的数据会话（只实现 simple 流程）。消息都在加密的控制连接上，头部带 `ServiceID: speaker1`：
 *
 * 1. 手机先发音频焦点消息（设备消息 module 4，见 [PhoneAudioFocus]），例如 type 1、数据 `"1"`：要长期焦点放媒体。
 *    本类解析后交出（[AudioEvent.PhoneFocus]），焦点由应用申请；分流焦点消息另外决定第 0 路放到哪个通道（[AudioRouter]）。
 * 2. 手机触发：`SET_PARAMETER`，头部有 `ServiceID`、`ServiceType: 3`、`DataSessionID`、`Simple: 1`、
 *    `ParaAudio`（如 `SampleRate=48000;Format=16;ChannelNum=2;Codec=0`），正文是 `msdp_trigger_method: OPTIONS`。
 * 3. 车机先回 200（头部依次是 ServiceID、ServiceType: 3、DataSessionID、手机的 CSeq），再开数据端口的 TCP 监听，
 *    开好后发 SETUP（头部顺序见 [setupMessage]，CSeq 取本机请求的计数器）。simple 流程没有 PLAY。
 * 4. 手机对 SETUP 回应答（以 CSeq 对应），选 TCP 时先回 200、再连车机的数据端口推流。一条连接上可能有几路
 *    （手机分流时音乐和导航各一路），拆出的包带着路号和该放的通道交出。
 * 5. 手机拆除：同样的 `SET_PARAMETER`，正文是 `msdp_trigger_method: TEARDOWN`。车机回 200、关掉这次的资源。
 *    手机不接受 SETUP 时约 8 秒后拆除、再触发，这条路会反复走。
 *
 * 服务类型有两个号：服务声明里是 4，数据会话消息（200、SETUP）里是 3，不能混用。
 *
 * 声明给手机的能力（2102 里 PCM 还是 AAC）在第一次用到时按连上的手机定下来（[SpeakerPlan]），SETUP 的 `Codec` 跟着它；
 * 声明 PCM 时顺带判断这台手机能不能出声（[PcmCheck]），结论经 [AudioEvent.CodecVerdict] 交给应用记下。
 *
 * 同一时间只有一个数据会话；手机触发新的 DataSessionID 时先拆旧的。手机发来的格式不对时只出诊断、不回应，
 * 也不抛异常：声音的问题不能连累设备会话和投屏。
 */
internal class SpeakerSession(
    private val speaker: DmsdpConfig.Speaker,
    private val cipher: AesCtr,
    private val listener: AudioStreamListener,
    /** 在加密的控制连接上发一条文本消息。 */
    private val sendText: (ByteArray, DmsdpSession.Outputs) -> Unit,
    /** 取一个本机请求的 CSeq（与保活的 GET_PARAMETER 共用计数器，不会撞号）。 */
    private val nextCSeq: () -> Int,
) {
    private var current: Stream? = null

    /** 本机发出、还没收到应答的 SETUP：CSeq 文本 → 数据会话。 */
    private val setups = HashMap<String, Stream>()

    /** 当前这次调用的输出列表：深处的拆包回调要往里放诊断。 */
    private var out: DmsdpSession.Outputs? = null
    private var listenerFailures = 0

    private var peer: PhoneRef? = null
    private var plan: SpeakerPlan? = null
    private var check: PcmCheck? = null
    private val router = AudioRouter(speaker.multiBus)

    private inner class Stream(val info: AudioStreamInfo) {
        /** 数据端口；0 表示还没开好（SETUP 还没发）。 */
        var port = 0
        var setupAtMillis = -1L
        var answer: String? = null
        var data: Link? = null
    }

    /** 手机连上的数据连接。 */
    private inner class Link(val stream: Stream) : AudioDepacketizer.Output {
        val depacketizer = AudioDepacketizer(cipher, stream.info, this)
        var finished = false
        var broken = false
        private var firstPacket = true

        /** 每一路（SSRC）上次放到的通道，用来在第一次出现、换通道时出一条事件。 */
        private val routed = HashMap<Long, AudioRoute>()

        fun finish() {
            if (finished) return
            finished = true
            if (!broken) parse { depacketizer.finish() }
        }

        fun feed(data: ByteArray, offset: Int, length: Int) {
            if (finished || broken) return
            parse { depacketizer.feed(data, offset, length) }
        }

        /** 拆包器本身出错（程序缺陷）：这条连接之后不再拆包，原始字节照常交出。 */
        private inline fun parse(block: () -> Unit) {
            try {
                block()
            } catch (e: RuntimeException) {
                broken = true
                note(stream.info, "拆包出错（${e.javaClass.simpleName}：${e.message}），之后只交原始字节")
            }
        }

        override fun onFraming(framing: AudioFraming) {
            emit(AudioEvent.FramingDetected(stream.info, framing))
            check?.onFraming(framing.payloadCodec, framing.playable)?.let { verdict(it) }
        }

        override fun onUnknown(reason: String, head: ByteArray) = emit(AudioEvent.FramingUnknown(stream.info, reason, head))

        override fun onNote(message: String) = note(stream.info, message)

        override fun onPacket(packet: AudioPacket) {
            router.onBus(packet.bus)?.let { note(stream.info, it) }
            val route = router.route(packet.bus, packet.usage)
            packet.route = route
            if (routed[packet.ssrc] != route && (routed.size < MAX_ROUTED_STREAMS || packet.ssrc in routed)) {
                routed[packet.ssrc] = route
                emit(AudioEvent.StreamRouted(stream.info, packet.ssrc, packet.bus, packet.usage, route))
            }
            if (firstPacket) {
                firstPacket = false
                emit(AudioEvent.FirstPacket(stream.info, packet.payloadType, packet.length))
            }
            check?.onPacket(packet.codec, packet.playable)?.let { verdict(it) }
            guard("onAudioPacket") { listener.onAudioPacket(packet) }
        }
    }

    /** 这条加密的文本消息归不归扬声器管：请求看 ServiceID，响应看 CSeq 是不是本机发出的 SETUP。 */
    fun accepts(message: DmsdpMessage): Boolean = if (message.isResponse) {
        message.header(CSEQ)?.let { setups.containsKey(it) } == true
    } else {
        message.header(SERVICE_ID) == DmsdpConfig.SPEAKER_SERVICE_ID
    }

    fun onMessage(message: DmsdpMessage, nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        if (message.isResponse) onSetupAnswer(message, nowMillis) else onRequest(message, nowMillis)
    }

    /** 手机的设备信息（命令 101）：记下是哪台手机，定扬声器能力时用。 */
    fun onPeerDevice(deviceId: String, name: String?) {
        if (peer == null) peer = PhoneRef(PhoneRef.keyOf(deviceId), name?.take(MAX_NAME_CHARS))
    }

    /** 服务声明（102）里扬声器属性 2102 的值；第一次调用时定下这次连接的声明。 */
    fun declaredProfile(): String {
        val p = plan()
        return DmsdpConfig.audioProfile(p.codec, p.multiBus)
    }

    /** 设备消息 module 4：手机的音频焦点消息。 */
    fun onFocusRecord(type: Int, data: ByteArray, nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        val message = PhoneAudioFocus.parse(type, data)
        emit(AudioEvent.PhoneFocus(message))
        router.onFocus(message)?.let { note(current?.info, it) }
        if (message.action == PhoneAudioFocus.Action.REQUEST) check?.onFocusRequest(message.focusType, nowMillis)
    }

    /**
     * 设备消息 type 504（通话状态）/ 505（语音助手状态），module 1 或 4（见 [PhoneStateMessages]）：解析后交出，不回应。
     * 格式不对只记一条诊断。
     */
    fun onPhoneState(module: Long, type: Int, data: ByteArray, out: DmsdpSession.Outputs) = using(out) {
        val value = PhoneStateMessages.parse(type, data)
        if (value == null) {
            note(current?.info, "手机的${if (type == PhoneStateMessages.TYPE_CALL_STATE) "通话" else "语音助手"}状态消息" +
                "（module $module / type $type，${data.size} 字节）格式不对，忽略")
            return@using
        }
        emit(
            if (type == PhoneStateMessages.TYPE_CALL_STATE) {
                AudioEvent.PhoneCallState(value, module)
            } else {
                AudioEvent.PhoneVoiceState(value, module)
            },
        )
    }

    /** 车机的焦点变化已经发给手机。 */
    fun onCarFocusSent(change: CarAudioFocus, out: DmsdpSession.Outputs) = using(out) {
        emit(AudioEvent.CarFocusSent(change))
    }

    fun listenersReady(port: Int, nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        val stream = current
        if (stream == null) {
            note(null, "音频端口开好时已经没有等它的数据会话，关掉")
            out.add(DmsdpAction.CloseAudioTransport)
            return@using
        }
        if (stream.port != 0) return@using
        stream.port = port
        sendSetup(stream, nowMillis)
    }

    fun listenersFailed(reason: String, out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        emit(AudioEvent.ListenFailed(stream.info, reason))
        end(stream, AudioEndReason.LISTEN_FAILED)
    }

    fun onConnected(nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        val stream = current
        if (stream == null) {
            note(null, "没有数据会话时手机连上了数据端口，忽略")
            return@using
        }
        stream.data?.finish()
        stream.data = Link(stream)
        val after = if (stream.setupAtMillis >= 0) nowMillis - stream.setupAtMillis else null
        emit(AudioEvent.PeerConnected(stream.info, after))
    }

    fun feed(data: ByteArray, offset: Int, length: Int, out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        val link = stream.data ?: return@using
        if (link.finished) return@using
        // 原始字节先交出：抓包不依赖识别成功。
        guard("onAudioRawData") { listener.onAudioRawData(stream.info, data, offset, length) }
        link.feed(data, offset, length)
    }

    fun onClosed(out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        val link = stream.data ?: return@using
        if (link.finished) return@using
        link.finish()
        emit(AudioEvent.PeerClosed(stream.info, link.depacketizer.bytes))
    }

    /** 整个设备会话结束：当前的数据会话随之结束（资源由调用方统一关闭），PCM 有没有出声在这时下最后的结论。 */
    fun close(nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        current?.let { end(it, AudioEndReason.SESSION_CLOSED) }
        setups.clear()
        check?.onClose(nowMillis)?.let { verdict(it) }
    }

    // ---------------- 请求 ----------------

    private fun onRequest(message: DmsdpMessage, nowMillis: Long) {
        if (message.method != SET_PARAMETER) {
            unhandled(message, "扬声器服务的 ${message.method} 请求没有实现，没有应答")
            return
        }
        val trigger = triggerMethod(message)
        when {
            trigger.equals(TRIGGER_OPTIONS, ignoreCase = true) -> onOptions(message, nowMillis)
            trigger.equals(TRIGGER_TEARDOWN, ignoreCase = true) -> onTeardown(message)
            trigger != null -> unhandled(message, "手机触发了没实现的数据会话操作「${trigger.take(32)}」，没有应答")
            message.header(AUDIO_PARAMETER) != null -> onAudioParameter(message)
            else -> unhandled(message, "扬声器服务的 SET_PARAMETER 没有触发字段，没有应答")
        }
    }

    private fun onOptions(message: DmsdpMessage, nowMillis: Long) {
        val cseq = validCSeq(message) ?: return
        val sessionId = validSessionId(message) ?: return
        val simple = message.header(SIMPLE)
        if (simple != "1") {
            note(null, "手机要求非 simple 流程（Simple=${simple?.take(8)}），没有实现，没有应答")
            return
        }
        reply(sessionId, cseq)
        val codec = plan().codec
        check?.onTrigger()
        val active = current
        if (active != null && active.info.dataSessionId == sessionId) {
            note(active.info, "手机重复触发同一个数据会话，已回 200" + if (active.port != 0) "，重发 SETUP" else "")
            if (active.port != 0) sendSetup(active, nowMillis)
            return
        }
        if (active != null) end(active, AudioEndReason.REPLACED)
        val stream = Stream(streamInfo(sessionId, message.header(PARA_AUDIO) ?: "", speaker.transport, codec))
        current = stream
        emit(AudioEvent.Requested(stream.info))
        out?.add(DmsdpAction.OpenAudioListeners)
    }

    private fun onTeardown(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val sessionId = validSessionId(message) ?: return
        reply(sessionId, cseq)
        val active = current
        if (active != null && active.info.dataSessionId == sessionId) {
            end(active, AudioEndReason.PEER_TEARDOWN)
        } else {
            note(null, "手机拆除的数据会话 $sessionId 不是当前的，已回 200")
        }
    }

    /** 不带触发字段、只带 AudioParameter 的参数更新：回 200（这类消息还没在真机上见过，回 200 免得手机等应答）。 */
    private fun onAudioParameter(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val sessionId = message.header(DATA_SESSION_ID)?.takeIf { SESSION_ID.matches(it) }
            ?: current?.info?.dataSessionId ?: "0"
        reply(sessionId, cseq)
        note(current?.info, "手机发来音频参数「${message.header(AUDIO_PARAMETER)?.take(120)}」，已回 200")
    }

    private fun onSetupAnswer(message: DmsdpMessage, nowMillis: Long) {
        val cseq = message.header(CSEQ) ?: return
        val stream = setups.remove(cseq) ?: return
        val after = if (stream.setupAtMillis >= 0) nowMillis - stream.setupAtMillis else -1
        stream.answer = message.startLine
        emit(AudioEvent.SetupAnswered(stream.info, message.startLine, message.statusCode, message.headers, after))
        when {
            stream !== current -> note(stream.info, "这个应答属于已经结束的数据会话，忽略")
            message.statusCode == STATUS_CODE_OK ->
                out?.event(DmsdpEvent.AudioSessionEstablished(stream.info.dataSessionId))
            else -> note(stream.info, "手机没有接受 SETUP")
        }
    }

    // ---------------- 发送与收尾 ----------------

    /** 这次连接的声明：第一次用到时按已知的手机定下，之后不变（服务声明和 SETUP 必须一致）。 */
    private fun plan(): SpeakerPlan {
        plan?.let { return it }
        val phone = peer
        val fallback = speaker.codec == AudioCodec.PCM && phone != null && phone.key in speaker.aacPhones
        val decided = SpeakerPlan(if (fallback) AudioCodec.AAC else speaker.codec, speaker.multiBus, phone, fallback)
        plan = decided
        check = PcmCheck(decided.codec == AudioCodec.PCM)
        emit(AudioEvent.SpeakerDeclared(decided))
        return decided
    }

    private fun verdict(verdict: PcmVerdict) = emit(AudioEvent.CodecVerdict(plan?.phone, verdict))

    private fun reply(sessionId: String, cseq: String) {
        val target = out ?: return
        sendText(replyMessage(sessionId, cseq), target)
    }

    private fun sendSetup(stream: Stream, nowMillis: Long) {
        val target = out ?: return
        val cseq = nextCSeq()
        setups[cseq.toString()] = stream
        stream.setupAtMillis = nowMillis
        sendText(setupMessage(stream.info.dataSessionId, cseq, stream.port, stream.info.transport, stream.info.codec), target)
        emit(AudioEvent.SetupSent(stream.info, stream.port, cseq, stream.info.codec))
    }

    private fun end(stream: Stream, reason: AudioEndReason) {
        stream.data?.finish()
        if (current === stream) current = null
        val pending = setups.entries.iterator()
        while (pending.hasNext()) if (pending.next().value === stream) pending.remove()
        if (reason != AudioEndReason.SESSION_CLOSED) out?.add(DmsdpAction.CloseAudioTransport)
        out?.event(DmsdpEvent.AudioSessionTornDown(stream.info.dataSessionId, reason.label))
        emit(AudioEvent.Ended(stream.info, reason, summary(stream)))
    }

    private fun summary(stream: Stream): AudioSummary {
        val data = stream.data?.depacketizer
        return AudioSummary(
            dataSessionId = stream.info.dataSessionId,
            setupPort = stream.port,
            setupAnswer = stream.answer,
            dataConnected = data != null,
            dataBytes = data?.bytes ?: 0,
            framing = data?.conclusion ?: "手机没有连数据端口",
            packets = data?.packets ?: 0,
            rtcpPackets = data?.rtcpPackets ?: 0,
            sequenceGaps = data?.sequenceGaps ?: 0,
            badFrames = data?.badFrames ?: 0,
            byteOrder = data?.pcmByteOrder ?: PcmByteOrder.UNDECIDED,
            streams = data?.streams ?: "",
        )
    }

    // ---------------- 工具 ----------------

    /** 在这次调用期间记下输出列表，深处的回调往里放诊断；调用结束恢复原值。 */
    private inline fun using(out: DmsdpSession.Outputs, block: () -> Unit) {
        val saved = this.out
        this.out = out
        try {
            block()
        } finally {
            this.out = saved
        }
    }

    private fun validCSeq(message: DmsdpMessage): String? {
        val cseq = message.header(CSEQ)
        if (cseq == null || cseq.length > MAX_CSEQ_DIGITS || !isAsciiDecimal(cseq)) {
            note(null, "数据会话消息缺少有效的 CSeq，没有应答")
            return null
        }
        return cseq
    }

    private fun validSessionId(message: DmsdpMessage): String? {
        val id = message.header(DATA_SESSION_ID)
        if (id == null || !SESSION_ID.matches(id)) {
            note(null, "数据会话消息缺少有效的 DataSessionID，没有应答")
            return null
        }
        return id
    }

    private fun unhandled(message: DmsdpMessage, why: String) {
        out?.event(DmsdpEvent.UnhandledControlMessage(message))
        note(current?.info, why)
    }

    private fun note(stream: AudioStreamInfo?, message: String) = emit(AudioEvent.Note(stream, message))

    private fun emit(event: AudioEvent) = guard("onAudioEvent") { listener.onAudioEvent(event) }

    /** 调上层的回调：抛出的异常接住、记成诊断（只记前几次），不影响设备会话。 */
    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            listenerFailures++
            if (listenerFailures <= MAX_LISTENER_FAILURE_NOTES) {
                out?.event(DmsdpEvent.AudioNote("音频回调 $what 抛出异常：${e.javaClass.simpleName}：${e.message}"))
            }
        }
    }

    internal companion object {
        const val SET_PARAMETER = "SET_PARAMETER"
        const val CSEQ = "CSeq"
        const val SERVICE_ID = "ServiceID"
        const val SERVICE_TYPE = "ServiceType"
        const val DATA_SESSION_ID = "DataSessionID"
        const val SIMPLE = "Simple"
        const val PARA_AUDIO = "ParaAudio"
        const val AUDIO_PARAMETER = "AudioParameter"

        /** 正文里触发字段的名字。 */
        const val TRIGGER_FIELD = "msdp_trigger_method"
        const val TRIGGER_OPTIONS = "OPTIONS"
        const val TRIGGER_TEARDOWN = "TEARDOWN"

        /** 数据会话消息里扬声器的服务类型（服务声明里是 4，这里是 3）。 */
        const val DATA_SERVICE_TYPE = "3"

        const val STATUS_OK = "DMSDP/1.0 200 OK"
        const val STATUS_CODE_OK = 200
        private const val MAX_CSEQ_DIGITS = 9
        private const val MAX_LISTENER_FAILURE_NOTES = 3

        /** 一条连接上最多分别记几路的通道（本机限制，路数正常是一两路）。 */
        private const val MAX_ROUTED_STREAMS = 16

        /** 手机自报的名字最多留这么多字（只给应用显示用）。 */
        private const val MAX_NAME_CHARS = 40

        /** DataSessionID：十进制整数，可能带负号。 */
        private val SESSION_ID = Regex("-?[0-9]{1,19}")

        /** 对触发的 200：头部依次是 ServiceID、ServiceType、DataSessionID、CSeq（手机的）。 */
        fun replyMessage(sessionId: String, cseq: String): ByteArray = DmsdpMessage.encode(
            STATUS_OK,
            listOf(
                SERVICE_ID to DmsdpConfig.SPEAKER_SERVICE_ID,
                SERVICE_TYPE to DATA_SERVICE_TYPE,
                DATA_SESSION_ID to sessionId,
                CSEQ to cseq,
            ),
        )

        /**
         * 车机的 SETUP。头部顺序是线上格式的一部分：ServiceID、ServiceType、DataSessionID、CSeq、Simple、
         * CryptoVer、CryptoAbility、CryptoAlg、Fillp、（只有 TCP 时）TCP、Codec、Transport。
         * UDP 不写 `TCP: 0`，而是不带这个头；Transport 不管哪种方式都写 `RTP/AVP/UDP`。
         */
        fun setupMessage(sessionId: String, cseq: Int, port: Int, transport: AudioTransport, codec: AudioCodec): ByteArray {
            val headers = ArrayList<Pair<String, String>>(12)
            headers += SERVICE_ID to DmsdpConfig.SPEAKER_SERVICE_ID
            headers += SERVICE_TYPE to DATA_SERVICE_TYPE
            headers += DATA_SESSION_ID to sessionId
            headers += CSEQ to cseq.toString()
            headers += SIMPLE to "1"
            headers += "CryptoVer" to "1"
            headers += "CryptoAbility" to "222"
            headers += "CryptoAlg" to "aes128-ctr"
            headers += "Fillp" to if (transport == AudioTransport.FILLP) "1" else "0"
            if (transport == AudioTransport.TCP) headers += "TCP" to "1"
            headers += "Codec" to codec.wire.toString()
            // 第二个口是 RTCP 口，必须写 -1（不建 RTCP）：手机建发送端前检查它小于 0，否则直接失败（-4），
            // 只写一个口也会被当成 0 而失败。
            headers += "Transport" to "RTP/AVP/UDP;unicast;client_port=$port--1"
            return DmsdpMessage.encode("SETUP * ${DmsdpMessage.VERSION}", headers)
        }

        /** 正文里 `msdp_trigger_method: 值` 这一行的值；正文没有时也看同名头部。 */
        fun triggerMethod(message: DmsdpMessage): String? {
            if (message.bodySize > 0) {
                val text = String(message.body, Charsets.ISO_8859_1)
                for (line in text.split('\n')) {
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals(TRIGGER_FIELD, ignoreCase = true)) {
                        return line.substring(colon + 1).trim()
                    }
                }
            }
            return message.header(TRIGGER_FIELD)
        }

        /** ParaAudio 是 `名=值` 用分号隔开（也接受逗号）；缺的或不合理的按 48kHz、双声道、16 位。 */
        fun streamInfo(sessionId: String, parameters: String, transport: AudioTransport, codec: AudioCodec): AudioStreamInfo {
            val values = HashMap<String, String>()
            for (part in parameters.split(';', ',')) {
                val eq = part.indexOf('=')
                if (eq > 0) values[part.substring(0, eq).trim().lowercase()] = part.substring(eq + 1).trim()
            }
            fun number(name: String, range: IntRange): Int? =
                values[name]?.takeIf { it.length in 1..6 && isAsciiDecimal(it) }?.toInt()?.takeIf { it in range }
            return AudioStreamInfo(
                dataSessionId = sessionId,
                sampleRate = number("samplerate", 8_000..192_000) ?: DEFAULT_SAMPLE_RATE,
                channels = number("channelnum", 1..8) ?: DEFAULT_CHANNELS,
                bitsPerSample = number("format", 8..32) ?: DEFAULT_BITS,
                requestedCodec = number("codec", 0..99),
                codec = codec,
                transport = transport,
                parameters = parameters.take(MAX_PARAMETER_CHARS),
            )
        }

        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_CHANNELS = 2
        private const val DEFAULT_BITS = 16
        private const val MAX_PARAMETER_CHARS = 200
    }
}
