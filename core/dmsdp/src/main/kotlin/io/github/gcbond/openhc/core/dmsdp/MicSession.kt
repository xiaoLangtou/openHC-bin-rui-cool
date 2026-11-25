package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.dmsdp.audio.MicCodec
import io.github.gcbond.openhc.core.dmsdp.audio.MicEndReason
import io.github.gcbond.openhc.core.dmsdp.audio.MicEvent
import io.github.gcbond.openhc.core.dmsdp.audio.MicPacketizer
import io.github.gcbond.openhc.core.dmsdp.audio.MicParameters
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.MicSummary
import io.github.gcbond.openhc.core.dmsdp.audio.describeHeaders
import java.security.SecureRandom

/**
 * 麦克风服务的数据会话（simple 流程）：车机是发送方，手机是接收方。消息都在加密的控制连接上，头部带 `ServiceID: mic1`，
 * 数据会话消息里麦克风的服务类型是 2（与服务声明相同）。
 *
 * 1. 手机触发：`SET_PARAMETER`，头部 `ServiceID`、`ServiceType: 2`、`DataSessionID`、`Simple: 1`、
 *    `ParaAudio: SampleRate=..;Format=..;ChannelNum=..;Codec=..`，正文 `msdp_trigger_method: OPTIONS`。
 *    车机先回 200（与扬声器相同，手机在这一步忽略它），再发一条**请求** `OPTIONS * DMSDP/1.0`（能力协商，
 *    头部顺序见 [optionsMessage]）：手机按里面的 `Fillp`、`TCP` 选传输方式（`Fillp: 0` 加 `TCP: 1` 是 TCP），
 *    并要求带齐 `CryptoVer`、`CryptoAbility: 222`、`CryptoAlg`。
 * 2. 手机建好收端后发 `SETUP`，`Transport: RTP/AVP/UDP;unicast;client_port=P-Q`（Q 通常是 -1，不建 RTCP）。
 *    车机开一个 TCP 监听（端口 S 由系统分配），开好后回 200，`Transport` 里原样带回 client_port、
 *    再加 `server_port=S--1`。
 * 3. 手机收到 200 后**去连「车机 IP : S」**；连上即开始推流（车机 → 手机），载荷与分帧见 [MicPacketizer]。
 * 4. 手机拆除：同样的 `SET_PARAMETER`，正文 `msdp_trigger_method: TEARDOWN`（也可能直接发 `TEARDOWN` 请求），
 *    车机回 200、关掉这次的资源。会话中手机还可能发 `SET_PARAMETER` 带 `AudioParameter`（新的采样参数），回 200 照办。
 *
 * 同一时间只有一个数据会话；新的 DataSessionID 触发时先拆旧的。格式不对只出诊断、不回应、不抛异常。
 *
 * 这套流程还没有在真机上验证过；手机发来的 SETUP、对能力协商的应答和没处理的请求都把头部原样记进诊断，方便对照。
 */
internal class MicSession(
    private val config: DmsdpConfig.Mic,
    private val cipher: AesCtr,
    private val random: SecureRandom,
    private val listener: MicStreamListener,
    /** 在加密的控制连接上发一条文本消息。 */
    private val sendText: (ByteArray, DmsdpSession.Outputs) -> Unit,
    /** 取一个本机请求的 CSeq（与保活、扬声器 SETUP 共用计数器）。 */
    private val nextCSeq: () -> Int,
    /** 当前时刻手机单调时钟的估计（微秒），来自手机的时间同步请求；还没收到时为 null。 */
    private val phoneClockUs: (Long) -> Long?,
) {
    private enum class State { NEGOTIATING, SETTING_UP, LISTENING, STREAMING }

    private inner class Stream(var parameters: MicParameters) {
        var state = State.NEGOTIATING
        var setupCSeq: String? = null
        var clientRtpPort = 0
        var clientRtcpPort = -1
        var serverPort = 0
        var connected = false
        val packetizer = MicPacketizer(cipher, random)

        /** 时间戳起点（90kHz）和从起点算起送出的采样数。 */
        var timestampBase = 0L
        var samples = 0L
        var totalMillis = 0L
        var dropped = 0L
        var peerBytesNoted = false
        var oversizeNoted = false

        val timestamp: Long get() = timestampBase + samples * RTP_CLOCK / parameters.sampleRate
    }

    private var current: Stream? = null

    /** 本机发出、还没收到应答的 OPTIONS 的 CSeq。 */
    private val pendingOptions = HashSet<String>()

    /** 当前这次调用的输出列表：深处的回调要往里放事件。 */
    private var out: DmsdpSession.Outputs? = null
    private var listenerFailures = 0

    /** 这条加密的文本消息归不归麦克风管：请求看 ServiceID，响应看 CSeq 是不是本机发出的 OPTIONS。 */
    fun accepts(message: DmsdpMessage): Boolean = if (message.isResponse) {
        message.header(CSEQ)?.let { it in pendingOptions } == true
    } else {
        message.header(SERVICE_ID) == DmsdpConfig.MIC_SERVICE_ID
    }

    fun onMessage(message: DmsdpMessage, nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        if (message.isResponse) onResponse(message) else onRequest(message)
    }

    fun listenerReady(port: Int, out: DmsdpSession.Outputs) = using(out) {
        val stream = current
        if (stream == null || stream.state != State.SETTING_UP) {
            note(stream?.parameters, "麦克风端口开好时已经没有等它的 SETUP，关掉")
            out.add(DmsdpAction.CloseMicTransport)
            return@using
        }
        stream.serverPort = port
        stream.state = State.LISTENING
        sendText(setupReply(stream, port), out)
        emit(MicEvent.SetupAnswered(stream.parameters, port))
    }

    fun listenerFailed(reason: String, out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        note(stream.parameters, "开不了麦克风数据端口，没回 SETUP：$reason")
        end(stream, MicEndReason.LISTEN_FAILED)
    }

    fun onConnected(nowMillis: Long, out: DmsdpSession.Outputs) = using(out) {
        val stream = current
        if (stream == null) {
            note(null, "没有麦克风会话时手机连上了麦克风数据端口，忽略")
            return@using
        }
        if (stream.state != State.LISTENING) note(stream.parameters, "手机在回 200 之前就连上了麦克风数据端口")
        stream.state = State.STREAMING
        stream.connected = true
        // 时间戳尽量落在手机的时钟上（与发送方的做法一致）；手机还没做时间同步时用本机时钟。
        val startUs = phoneClockUs(nowMillis) ?: (nowMillis * 1000)
        stream.timestampBase = startUs * 9 / 100
        stream.samples = 0
        out.event(DmsdpEvent.MicSessionEstablished(stream.parameters.dataSessionId))
        emit(MicEvent.Started(stream.parameters))
    }

    /** 手机按理不在麦克风连接上发数据；收到只记一笔。 */
    fun feed(length: Int, out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        if (!stream.peerBytesNoted) {
            stream.peerBytesNoted = true
            note(stream.parameters, "手机在麦克风连接上发来 $length 字节，忽略")
        }
    }

    fun onClosed(out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        if (!stream.connected) return@using
        end(stream, MicEndReason.PEER_CLOSED)
    }

    /**
     * 送一块声音：PCM 时是 16 位小端交错样本（长度按采样帧取整，超过一块的上限就分几块）；AAC 时是一个带 ADTS 头的帧。
     * 连接还没建立时丢掉、计数。
     */
    fun sendAudio(data: ByteArray, offset: Int, length: Int, out: DmsdpSession.Outputs) = using(out) {
        val stream = current ?: return@using
        if (!stream.connected) {
            stream.dropped++
            return@using
        }
        val parameters = stream.parameters
        when (parameters.codec) {
            MicCodec.PCM -> {
                val frameBytes = parameters.frameBytes
                val usable = length - length % frameBytes
                val chunk = MicPacketizer.MAX_PCM_BUFFER - MicPacketizer.MAX_PCM_BUFFER % frameBytes
                var at = 0
                while (at < usable) {
                    val n = minOf(chunk, usable - at)
                    for (frame in stream.packetizer.pcm(data, offset + at, n, stream.timestamp)) {
                        out.send(DmsdpChannel.MIC, frame)
                    }
                    advance(stream, n / frameBytes)
                    at += n
                }
            }
            MicCodec.AAC -> {
                if (length !in 1..MicPacketizer.MAX_AAC_FRAME) {
                    stream.dropped++
                    if (!stream.oversizeNoted) {
                        stream.oversizeNoted = true
                        note(parameters, "AAC 帧 $length 字节放不进一个包，丢掉（之后同类不再记）")
                    }
                    return@using
                }
                out.send(DmsdpChannel.MIC, stream.packetizer.aac(data, offset, length, stream.timestamp))
                advance(stream, AAC_FRAME_SAMPLES)
            }
        }
    }

    /** 整个设备会话结束：当前的麦克风会话随之结束（资源由调用方统一关闭）。 */
    fun close(out: DmsdpSession.Outputs) = using(out) {
        current?.let { end(it, MicEndReason.SESSION_CLOSED) }
        pendingOptions.clear()
    }

    // ---------------- 请求 ----------------

    private fun onRequest(message: DmsdpMessage) {
        when (message.method) {
            SET_PARAMETER -> {
                val trigger = triggerMethod(message)
                when {
                    trigger.equals(TRIGGER_OPTIONS, ignoreCase = true) -> onTriggerOptions(message)
                    // CLOSE 和 TEARDOWN 一样处理：回 200，关掉对得上的会话。
                    trigger.equals(TRIGGER_TEARDOWN, ignoreCase = true) || trigger.equals(TRIGGER_CLOSE, ignoreCase = true) ->
                        onTeardown(message)
                    trigger != null -> unhandled(message, "手机触发了没实现的麦克风操作「${trigger.take(32)}」，没有应答")
                    message.header(AUDIO_PARAMETER) != null -> onAudioParameter(message)
                    else -> unhandled(message, "麦克风服务的 SET_PARAMETER 没有触发字段，没有应答")
                }
            }
            SETUP -> onSetup(message)
            TEARDOWN -> onTeardown(message)
            else -> unhandled(message, "麦克风服务的 ${message.method} 请求没有实现，没有应答")
        }
    }

    private fun onTriggerOptions(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val sessionId = validSessionId(message) ?: return
        val simple = message.header(SIMPLE)
        if (simple != "1") {
            note(null, "手机要求非 simple 的麦克风流程（Simple=${simple?.take(8)}），没有实现，没有应答")
            return
        }
        reply(sessionId, cseq)
        val active = current
        if (active != null && active.parameters.dataSessionId == sessionId) {
            if (active.state == State.NEGOTIATING) {
                note(active.parameters, "手机重复触发同一个麦克风会话，已回 200，重发能力协商")
                sendOptions(active)
            } else {
                note(active.parameters, "手机重复触发同一个麦克风会话，已回 200")
            }
            return
        }
        if (active != null) end(active, MicEndReason.REPLACED)
        val parameters = parse(sessionId, message.header(PARA_AUDIO)) ?: return
        val stream = Stream(parameters)
        current = stream
        emit(MicEvent.Requested(parameters))
        sendOptions(stream)
    }

    private fun onSetup(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val sessionId = validSessionId(message) ?: return
        val stream = current
        if (stream == null || stream.parameters.dataSessionId != sessionId) {
            note(stream?.parameters, "手机 SETUP 的数据会话 $sessionId 不是当前的麦克风会话，没有应答")
            return
        }
        val ports = clientPorts(message.header(TRANSPORT))
        if (ports == null) {
            note(stream.parameters, "手机的麦克风 SETUP 没有可用的 client_port，没有应答")
            return
        }
        stream.setupCSeq = cseq
        stream.clientRtpPort = ports.first
        stream.clientRtcpPort = ports.second
        pendingOptions.clear()
        when (stream.state) {
            State.NEGOTIATING -> {
                stream.state = State.SETTING_UP
                emit(MicEvent.SetupReceived(stream.parameters, ports.first, message.headers))
                out?.add(DmsdpAction.OpenMicListener)
            }
            State.SETTING_UP -> note(stream.parameters, "手机重复发麦克风 SETUP，端口还在开")
            State.LISTENING, State.STREAMING -> {
                note(stream.parameters, "手机重复发麦克风 SETUP，按已开的端口 ${stream.serverPort} 再回 200")
                out?.let { sendText(setupReply(stream, stream.serverPort), it) }
            }
        }
    }

    private fun onTeardown(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val sessionId = validSessionId(message) ?: return
        reply(sessionId, cseq)
        val active = current
        if (active != null && active.parameters.dataSessionId == sessionId) {
            end(active, MicEndReason.PEER_TEARDOWN)
        } else {
            note(null, "手机拆除的麦克风会话 $sessionId 不是当前的，已回 200")
        }
    }

    /** 不带触发字段、只带 AudioParameter 的设参：回 200，参数合法且属于当前会话时照办。 */
    private fun onAudioParameter(message: DmsdpMessage) {
        val cseq = validCSeq(message) ?: return
        val active = current
        val sessionId = message.header(DATA_SESSION_ID)?.takeIf { SESSION_ID.matches(it) }
            ?: active?.parameters?.dataSessionId ?: "0"
        reply(sessionId, cseq)
        if (active == null || active.parameters.dataSessionId != sessionId) {
            note(null, "手机给不是当前的麦克风会话设参，已回 200")
            return
        }
        val updated = parse(sessionId, message.header(AUDIO_PARAMETER)) ?: return
        if (updated.sameFormat(active.parameters)) {
            note(active.parameters, "手机重发了相同的麦克风参数，已回 200")
            return
        }
        // 新参数从当前时间戳接着往下数。
        active.timestampBase = active.timestamp
        active.samples = 0
        active.parameters = updated
        emit(MicEvent.ParametersChanged(updated))
    }

    private fun onResponse(message: DmsdpMessage) {
        val cseq = message.header(CSEQ) ?: return
        pendingOptions.remove(cseq)
        note(current?.parameters, "手机应答了麦克风能力协商：${message.startLine}；头部 ${describeHeaders(message.headers)}")
    }

    // ---------------- 发送与收尾 ----------------

    private fun sendOptions(stream: Stream) {
        val target = out ?: return
        val cseq = nextCSeq()
        pendingOptions += cseq.toString()
        sendText(optionsMessage(stream.parameters.dataSessionId, cseq, stream.parameters.codec), target)
    }

    private fun reply(sessionId: String, cseq: String) {
        val target = out ?: return
        sendText(replyMessage(sessionId, cseq), target)
    }

    private fun setupReply(stream: Stream, port: Int): ByteArray = setupReplyMessage(
        stream.parameters.dataSessionId,
        checkNotNull(stream.setupCSeq),
        stream.clientRtpPort,
        stream.clientRtcpPort,
        port,
    )

    private fun advance(stream: Stream, frames: Int) {
        stream.samples += frames
        stream.totalMillis += frames * 1000L / stream.parameters.sampleRate
    }

    private fun end(stream: Stream, reason: MicEndReason) {
        if (current === stream) current = null
        pendingOptions.clear()
        if (reason != MicEndReason.SESSION_CLOSED) out?.add(DmsdpAction.CloseMicTransport)
        out?.event(DmsdpEvent.MicSessionTornDown(stream.parameters.dataSessionId, reason.label))
        val summary = MicSummary(
            dataSessionId = stream.parameters.dataSessionId,
            serverPort = stream.serverPort,
            connected = stream.connected,
            packets = stream.packetizer.packets,
            bytes = stream.packetizer.bytes,
            audioMillis = stream.totalMillis,
            dropped = stream.dropped,
        )
        emit(MicEvent.Stopped(stream.parameters, reason, summary))
    }

    // ---------------- 工具 ----------------

    private fun parse(sessionId: String, text: String?): MicParameters? {
        val parameters = parseParameters(sessionId, text ?: "")
        if (parameters == null) {
            note(null, "手机要的麦克风参数不支持（${text?.take(MAX_PARAMETER_CHARS)}），只支持 16 位单/双声道")
        }
        return parameters
    }

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
            note(null, "麦克风会话消息缺少有效的 CSeq，没有应答")
            return null
        }
        return cseq
    }

    private fun validSessionId(message: DmsdpMessage): String? {
        val id = message.header(DATA_SESSION_ID)
        if (id == null || !SESSION_ID.matches(id)) {
            note(null, "麦克风会话消息缺少有效的 DataSessionID，没有应答")
            return null
        }
        return id
    }

    private fun unhandled(message: DmsdpMessage, why: String) {
        out?.event(DmsdpEvent.UnhandledControlMessage(message))
        note(current?.parameters, "$why（${message.startLine}；头部 ${describeHeaders(message.headers)}）")
    }

    private fun note(parameters: MicParameters?, message: String) = emit(MicEvent.Note(parameters, message))

    /** 调上层的回调：抛出的异常接住、记成诊断（只记前几次），不影响设备会话。 */
    private fun emit(event: MicEvent) {
        try {
            listener.onMicEvent(event)
        } catch (e: Exception) {
            listenerFailures++
            if (listenerFailures <= MAX_LISTENER_FAILURE_NOTES) {
                out?.event(DmsdpEvent.AudioNote("麦克风回调抛出异常：${e.javaClass.simpleName}：${e.message}"))
            }
        }
    }

    internal companion object {
        const val SET_PARAMETER = "SET_PARAMETER"
        const val SETUP = "SETUP"
        const val TEARDOWN = "TEARDOWN"
        const val CSEQ = "CSeq"
        const val SERVICE_ID = "ServiceID"
        const val SERVICE_TYPE = "ServiceType"
        const val DATA_SESSION_ID = "DataSessionID"
        const val SIMPLE = "Simple"
        const val PARA_AUDIO = "ParaAudio"
        const val AUDIO_PARAMETER = "AudioParameter"
        const val TRANSPORT = "Transport"
        const val TRIGGER_OPTIONS = "OPTIONS"
        const val TRIGGER_TEARDOWN = "TEARDOWN"
        const val TRIGGER_CLOSE = "CLOSE"

        /** 数据会话消息里麦克风的服务类型（与服务声明相同）。 */
        const val DATA_SERVICE_TYPE = "2"

        const val STATUS_OK = "DMSDP/1.0 200 OK"

        /** RTP 时间戳的时钟（Hz）。 */
        const val RTP_CLOCK = 90_000L

        /** 一个 AAC-LC 帧的采样数。 */
        const val AAC_FRAME_SAMPLES = 1024

        /** 手机按「采样率 × 声道 × 位深」不超过它就用 PCM（没给编码号时照此推断）。 */
        private const val PCM_BITRATE_LIMIT = 520_000
        private const val MAX_CSEQ_DIGITS = 9
        private const val MAX_LISTENER_FAILURE_NOTES = 3
        private const val MAX_PARAMETER_CHARS = 200
        private val SESSION_ID = Regex("-?[0-9]{1,19}")

        /** 对触发、拆除、设参的 200：头部依次是 ServiceID、ServiceType、DataSessionID、CSeq（手机的）。 */
        fun replyMessage(sessionId: String, cseq: String): ByteArray = DmsdpMessage.encode(
            STATUS_OK,
            listOf(
                SERVICE_ID to DmsdpConfig.MIC_SERVICE_ID,
                SERVICE_TYPE to DATA_SERVICE_TYPE,
                DATA_SESSION_ID to sessionId,
                CSEQ to cseq,
            ),
        )

        /**
         * 车机的能力协商请求。头部顺序：ServiceID、ServiceType、DataSessionID、CSeq、CryptoVer、CryptoAbility、
         * CryptoAlg、Fillp、TCP、Codec、Simple。`Fillp: 0` 加 `TCP: 1` 让手机的收端走 TCP、由手机去连车机；
         * `Codec` 是压缩标志（AAC 写 1、PCM 写 0）。
         */
        fun optionsMessage(sessionId: String, cseq: Int, codec: MicCodec): ByteArray = DmsdpMessage.encode(
            "OPTIONS * ${DmsdpMessage.VERSION}",
            listOf(
                SERVICE_ID to DmsdpConfig.MIC_SERVICE_ID,
                SERVICE_TYPE to DATA_SERVICE_TYPE,
                DATA_SESSION_ID to sessionId,
                CSEQ to cseq.toString(),
                "CryptoVer" to "1",
                "CryptoAbility" to "222",
                "CryptoAlg" to "aes128-ctr",
                "Fillp" to "0",
                "TCP" to "1",
                "Codec" to codec.compressedFlag.toString(),
                SIMPLE to "1",
            ),
        )

        /** 对手机 SETUP 的 200：client_port 原样带回，加上车机的数据端口（RTCP 口写 -1，不建）。 */
        fun setupReplyMessage(sessionId: String, cseq: String, clientRtp: Int, clientRtcp: Int, serverPort: Int): ByteArray =
            DmsdpMessage.encode(
                STATUS_OK,
                listOf(
                    SERVICE_ID to DmsdpConfig.MIC_SERVICE_ID,
                    SERVICE_TYPE to DATA_SERVICE_TYPE,
                    DATA_SESSION_ID to sessionId,
                    CSEQ to cseq,
                    TRANSPORT to "RTP/AVP/UDP;unicast;client_port=$clientRtp-$clientRtcp;server_port=$serverPort--1",
                ),
            )

        /** 正文里 `msdp_trigger_method: 值` 这一行的值（名字不分大小写）；正文没有时也看同名头部。 */
        fun triggerMethod(message: DmsdpMessage): String? {
            if (message.bodySize > 0) {
                for (line in String(message.body, Charsets.ISO_8859_1).split('\n')) {
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals(TRIGGER_FIELD, ignoreCase = true)) {
                        return line.substring(colon + 1).trim()
                    }
                }
            }
            return message.header(TRIGGER_FIELD)
        }

        private const val TRIGGER_FIELD = "msdp_trigger_method"

        /**
         * `Transport` 里 `client_port=P-Q` 的两个数（Q 可以是负数，例如 `P--1`；只写一个数时 Q 按 -1）。
         * P 不是 1～65535 的端口时返回 null。
         */
        fun clientPorts(transport: String?): Pair<Int, Int>? {
            if (transport == null) return null
            val value = transport.split(';').map { it.trim() }
                .firstOrNull { it.startsWith("client_port=", ignoreCase = true) }
                ?.substringAfter('=') ?: return null
            val dash = value.indexOf('-', startIndex = 1)
            val first = if (dash < 0) value else value.substring(0, dash)
            val rtp = first.takeIf { it.length in 1..5 && isAsciiDecimal(it) }?.toInt()?.takeIf { it in 1..0xFFFF } ?: return null
            if (dash < 0) return rtp to -1
            val second = value.substring(dash + 1)
            val negative = second.startsWith('-')
            val digits = if (negative) second.substring(1) else second
            if (digits.length !in 1..5 || !isAsciiDecimal(digits)) return null
            val rtcp = digits.toInt().let { if (negative) -it else it }
            return rtp to rtcp
        }

        /**
         * `SampleRate=..;Format=..;ChannelNum=..;Codec=..`（分号或逗号分隔、名字不分大小写）。缺采样率按 48000、
         * 缺声道按 2、缺位深按 16；缺编码号时照手机的规则推断（比特率不超过 520000 用 PCM，否则 AAC）。
         * 只支持 16 位、1～2 声道、8000～48000Hz，其他返回 null。
         */
        fun parseParameters(sessionId: String, text: String): MicParameters? {
            val values = HashMap<String, String>()
            for (part in text.split(';', ',')) {
                val eq = part.indexOf('=')
                if (eq > 0) values[part.substring(0, eq).trim().lowercase()] = part.substring(eq + 1).trim()
            }
            fun number(name: String): Int? = values[name]?.takeIf { it.length in 1..6 && isAsciiDecimal(it) }?.toInt()
            val sampleRate = number("samplerate") ?: DEFAULT_SAMPLE_RATE
            val channels = number("channelnum") ?: DEFAULT_CHANNELS
            val bits = number("format") ?: DEFAULT_BITS
            if (sampleRate !in 8_000..48_000 || channels !in 1..2 || bits != DEFAULT_BITS) return null
            val codec = if (values.containsKey("codec")) {
                MicCodec.fromPara(number("codec") ?: return null) ?: return null
            } else if (sampleRate.toLong() * channels * bits <= PCM_BITRATE_LIMIT) {
                MicCodec.PCM
            } else {
                MicCodec.AAC
            }
            return MicParameters(sessionId, sampleRate, channels, bits, codec, text.take(MAX_PARAMETER_CHARS))
        }

        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_CHANNELS = 2
        private const val DEFAULT_BITS = 16
    }
}
