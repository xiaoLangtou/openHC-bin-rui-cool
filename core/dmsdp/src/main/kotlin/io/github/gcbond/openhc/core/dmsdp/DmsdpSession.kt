package io.github.gcbond.openhc.core.dmsdp

import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.codec.JsonCodec
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.PhoneStateMessages
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * DMSDP 设备会话：认证之后、投屏之前的那一层。一个对象对应一次手机连接。
 *
 * 本类不碰 socket、线程和定时器：收到的字节用 [feed] / [onDatagram] 喂进来，要发的字节和需要调用方
 * 做的事以 [DmsdpAction] 返回，协议事件以 [DmsdpEvent] 返回，二者按产生顺序混排在同一个列表里。
 * 时间一律由参数 `nowMillis`（单调时钟毫秒）给出。不是线程安全的，所有调用要在同一个线程里串行。
 *
 * 一次有线连接的顺序：
 * 1. 手机连上业务 20 的 TCP（端口由 nearby 层的资源应答给出），字节喂给 [DmsdpChannel.SESSION]。
 *    收到首轮协商后输出 [DmsdpAction.OpenControlListener]：调用方开监听，再调 [controlListenerReady]，
 *    得到要写回业务 20 连接的应答。
 * 2. 手机连上控制监听：调 [onConnected]（[DmsdpChannel.CONTROL]），输出明文 OPTIONS。
 * 3. 手机明文选择加密：本类先明文回 200，再切到 AES-128-CTR，并按手机选择启动 TCP 或 UDP 保活
 *    （UDP 时输出 [DmsdpAction.OpenKeepAliveSocket]）。
 * 4. 之后的时间同步、设备信息、服务请求、HiSight 端口等都在加密的控制连接上，见 [DmsdpEvent]。
 * 5. 定时调用 [onTick]，最晚在 [nextTickAtMillis] 时调用。
 * 6. 开了文件分享时，手机经管理消息给出两个端口，本类输出 [DmsdpAction.Connect]，由调用方去连；
 *    文件请求、内容和结束都以事件交出，用户的决定经 [acceptFileOffer] 等方法传回。
 * 7. 声明了扬声器时，手机触发音频数据会话：本类先回 200，再输出 [DmsdpAction.OpenAudioListeners]；调用方开好
 *    数据端口后调 [audioListenersReady]，本类发 SETUP。手机连上来的音频连接按 [DmsdpChannel.AUDIO] 喂进来，
 *    拆出的音频（带路号和该放的通道）和状态经 [audioListener] 交出（流程见 [SpeakerSession]）。手机的音频焦点消息
 *    （设备消息 module 4）也从这里交出；车机这边的焦点变化用 [sendAudioFocus] 告诉手机。
 *    音频连接的数据不对只出诊断，不让会话作废。
 * 8. 声明了麦克风时，手机要录音就触发麦克风数据会话：本类回 200、发能力协商；手机发 SETUP 后输出
 *    [DmsdpAction.OpenMicListener]，调用方开好监听后调 [micListenerReady]，本类回 200；手机连上
 *    [DmsdpChannel.MIC] 后经 [micListener] 通知开始，采到的声音用 [sendMicAudio] 送（流程见 [MicSession]）。
 *
 * 出错规则：业务 20 或控制连接的数据格式不对时抛 [MalformedMessageException]，之后这个对象作废，
 * 再喂数据会抛 [IllegalStateException]；格式正确但本层没实现的消息只出事件，不应答、不中断会话。
 *
 * @param sessionKey 认证层给的 16 字节会话密钥，直接作为 AES-128 密钥。
 * @param random 生成每帧 IV；测试里可传返回固定序列的实现。
 * @param audioListener 音频数据会话的出口（在调用本类方法的线程上同步回调）；没声明扬声器时用不到。
 * @param micListener 麦克风数据会话的状态出口（同样同步回调）；没声明麦克风时用不到。
 */
class DmsdpSession(
    private val config: DmsdpConfig,
    sessionKey: ByteArray,
    private val random: SecureRandom,
    audioListener: AudioStreamListener = AudioStreamListener.NONE,
    micListener: MicStreamListener = MicStreamListener.NONE,
) {
    private val cipher = AesCtr(sessionKey)

    // 扬声器的 2102 要看连上的是哪台手机，所以在回服务声明时才问扬声器（它在下面才建，用到时早已建好）。
    private val devices = DeviceMessageHandler(config) { speaker?.declaredProfile() ?: "" }
    // 514/515 元数据线（v29）：**不带** "_launcher" 和屏幕参数键单独开启——请求-应答是管理层的独立机制，
    // 理论上不依赖融合桌面；实弹验证时 _launcher 也在场，这次单独开就是验证它到底依不依赖（2026-11-24）。
    // 若实车发现它也触发窗口化，就证明元数据真的挂在融合桌面上，只能等桌面参数修对后一起回来。
    private var metadataRequested = false
    private var metadataRequestedOnDisplayStart = false
    private val files = config.fileShare?.let { IncomingFileTransfer(config, it) }
    private val speaker = config.speaker?.let {
        SpeakerSession(it, cipher, audioListener, ::sendSpeakerText) { nextCSeq++ }
    }
    private val mic = config.mic?.let {
        MicSession(it, cipher, random, micListener, ::sendSpeakerText, { nextCSeq++ }, ::phoneClockUs)
    }

    /** 最近一次时间同步请求里手机的单调时钟（微秒）和收到它的本机时间；麦克风时间戳用。 */
    private var phoneTimeUs: Long? = null
    private var phoneTimeAtMillis = 0L

    /** 最近一次调用带来的时间：收尾（[close] 不带时间）时给 PCM 判定用。 */
    private var lastNowMillis = 0L

    private val sessionDecoder = SessionFrameDecoder()
    private var controlPort = 0
    private var pendingNegotiations = 0
    private var listenerRequested = false

    private val controlDecoder = ControlFrameDecoder()

    /** 加密控制连接上认不出、跳过了的载荷个数（见 [DmsdpEvent.UnrecognizedControlPayload]）。 */
    private var unrecognizedPayloads = 0
    private var controlState = ControlState.NOT_CONNECTED
    private var nextCSeq = 1
    private var tcpKeepAlive: TcpKeepAlive? = null
    private var udpKeepAlive: UdpKeepAlive? = null

    private var failed = false
    private var closed = false

    private enum class ControlState { NOT_CONNECTED, PLAINTEXT, ENCRYPTED, CLOSED }

    /** 控制连接是否已切到加密。 */
    val isEncrypted: Boolean get() = controlState == ControlState.ENCRYPTED

    /** 下一次希望被 [onTick] 调用的时间；null 表示目前没有定时任务。 */
    val nextTickAtMillis: Long?
        get() {
            if (failed || closed) return null
            val keepAlive = if (controlState == ControlState.ENCRYPTED) {
                tcpKeepAlive?.nextAtMillis ?: udpKeepAlive?.nextAtMillis
            } else {
                null
            }
            val fileDeadline = files?.nextDeadlineMillis
            return when {
                keepAlive == null -> fileDeadline
                fileDeadline == null -> keepAlive
                else -> minOf(keepAlive, fileDeadline)
            }
        }

    /** 喂入 [channel] 上收到的一段字节（半包、粘包都可以）。 */
    fun feed(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> {
        checkRange(data, offset, length)
        checkUsable()
        lastNowMillis = nowMillis
        val out = Outputs()
        when (channel) {
            DmsdpChannel.SESSION -> fatalOnError { feedSession(data, offset, length, out) }
            DmsdpChannel.CONTROL -> {
                check(controlState == ControlState.PLAINTEXT || controlState == ControlState.ENCRYPTED) {
                    "控制连接不在可收数据的状态: $controlState"
                }
                fatalOnError { feedControl(data, offset, length, nowMillis, out) }
            }
            DmsdpChannel.FILE_CONTROL, DmsdpChannel.FILE_DATA ->
                requireFiles().feed(channel, data, offset, length, nowMillis, out)
            DmsdpChannel.AUDIO -> audioQuietly(out) { speaker?.feed(data, offset, length, out) }
            DmsdpChannel.MIC -> audioQuietly(out) { mic?.feed(length, out) }
        }
        return out.list
    }

    fun feed(channel: DmsdpChannel, data: ByteArray, nowMillis: Long): List<DmsdpOutput> =
        feed(channel, data, 0, data.size, nowMillis)

    /** 控制监听已就绪：返回对首轮协商的应答（写到业务 20 连接）。之后重复的协商用同一端口自动应答。 */
    fun controlListenerReady(port: Int): List<DmsdpOutput> {
        require(port in 1..0xFFFF) { "端口超出范围: $port" }
        checkUsable()
        check(controlPort == 0 || controlPort == port) { "控制监听端口不能更换" }
        controlPort = port
        val out = Outputs()
        repeat(pendingNegotiations) { out.send(DmsdpChannel.SESSION, SessionFrames.negotiationReply(port)) }
        pendingNegotiations = 0
        return out.list
    }

    /** [channel] 的连接已建立。控制连接建立后返回明文 OPTIONS。 */
    fun onConnected(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput> {
        checkUsable()
        lastNowMillis = nowMillis
        val out = Outputs()
        when (channel) {
            DmsdpChannel.SESSION -> Unit
            DmsdpChannel.CONTROL -> {
                check(controlState == ControlState.NOT_CONNECTED) { "控制连接只能建立一次" }
                controlState = ControlState.PLAINTEXT
                sendRequest(OPTIONS, optionsHeaders(), out)
            }
            DmsdpChannel.FILE_CONTROL, DmsdpChannel.FILE_DATA -> requireFiles().onConnected(channel, out)
            DmsdpChannel.AUDIO -> audioQuietly(out) { speaker?.onConnected(nowMillis, out) }
            DmsdpChannel.MIC -> audioQuietly(out) { mic?.onConnected(nowMillis, out) }
        }
        return out.list
    }

    /**
     * [channel] 的连接已被对端关闭（EOF）或出错断开（也包括 [DmsdpAction.Connect] 没连上）。
     * 业务 20 或控制连接留下半帧时抛 [MalformedMessageException]；控制连接关闭后不再产生保活。
     * 文件连接断开只影响文件分享，见 [DmsdpEvent.FileShareFailed]。
     */
    fun onClosed(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput> {
        if (failed || closed) return emptyList()
        lastNowMillis = nowMillis
        val out = Outputs()
        when (channel) {
            DmsdpChannel.SESSION -> fatalOnError {
                if (sessionDecoder.hasPartial) throw MalformedMessageException("业务 20 连接关闭时留下不完整的帧")
            }
            DmsdpChannel.CONTROL -> {
                val partial = controlDecoder.hasPartial
                controlState = ControlState.CLOSED
                tcpKeepAlive = null
                udpKeepAlive = null
                if (partial) fatalOnError { throw MalformedMessageException("控制连接关闭时留下不完整的帧") }
            }
            DmsdpChannel.FILE_CONTROL, DmsdpChannel.FILE_DATA -> files?.onClosed(channel, out)
            DmsdpChannel.AUDIO -> audioQuietly(out) { speaker?.onClosed(out) }
            DmsdpChannel.MIC -> audioQuietly(out) { mic?.onClosed(out) }
        }
        return out.list
    }

    /**
     * UDP 保活 socket 收到一个数据报。格式不对抛 [MalformedMessageException]（不计存活），
     * 但会话对象不作废，要不要断开由调用方决定。
     */
    fun onDatagram(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> {
        checkRange(data, offset, length)
        checkUsable()
        val keepAlive = udpKeepAlive ?: return emptyList()
        val plain = UdpKeepAlive.open(cipher, data, offset, length)
        val out = Outputs()
        if (keepAlive.onPeerKeepAlive(plain)) out.event(DmsdpEvent.KeepAlivePeriodChanged(keepAlive.extended))
        return out.list
    }

    fun onDatagram(data: ByteArray, nowMillis: Long): List<DmsdpOutput> = onDatagram(data, 0, data.size, nowMillis)

    /** 推进定时任务（保活、文件分享的本机时限）。可以早调、晚调；晚了也只执行一轮，不追补。 */
    fun onTick(nowMillis: Long): List<DmsdpOutput> {
        if (failed || closed) return emptyList()
        lastNowMillis = nowMillis
        val out = Outputs()
        files?.tick(nowMillis, out)
        if (controlState != ControlState.ENCRYPTED) return out.list
        tcpKeepAlive?.let { keepAlive ->
            when (keepAlive.tick(nowMillis)) {
                KeepAliveTick.SEND -> sendRequest(GET_PARAMETER, emptyList(), out)
                KeepAliveTick.TIMEOUT -> out.event(DmsdpEvent.KeepAliveTimeout(KeepAliveMode.TCP))
                KeepAliveTick.NONE -> Unit
            }
        }
        udpKeepAlive?.let { keepAlive ->
            when (keepAlive.tick(nowMillis)) {
                KeepAliveTick.SEND -> repeat(2) {
                    out.add(DmsdpAction.SendDatagram(UdpKeepAlive.seal(cipher, random, keepAlive.payload())))
                }
                KeepAliveTick.TIMEOUT -> out.event(DmsdpEvent.KeepAliveTimeout(KeepAliveMode.UDP))
                KeepAliveTick.NONE -> Unit
            }
        }
        return out.list
    }

    /**
     * [DmsdpAction.OpenAudioListeners] 要的数据端口开好了（[port]）。返回要发给手机的 SETUP。
     * 数据会话已经结束时返回关闭音频资源的动作。
     */
    fun audioListenersReady(port: Int, nowMillis: Long): List<DmsdpOutput> {
        require(port in 1..0xFFFF) { "端口超出范围: $port" }
        checkUsable()
        lastNowMillis = nowMillis
        val out = Outputs()
        audioQuietly(out) { speaker?.listenersReady(port, nowMillis, out) }
        return out.list
    }

    /** 音频端口开不了：不发 SETUP，结束这次数据会话（手机等不到 SETUP 会自己拆掉再来）。 */
    fun audioListenersFailed(reason: String): List<DmsdpOutput> {
        checkUsable()
        val out = Outputs()
        audioQuietly(out) { speaker?.listenersFailed(reason, out) }
        return out.list
    }

    /** [DmsdpAction.OpenMicListener] 要的监听开好了：回手机 SETUP 的 200（数据端口 [port]）。 */
    fun micListenerReady(port: Int): List<DmsdpOutput> {
        require(port in 1..0xFFFF) { "端口超出范围: $port" }
        checkUsable()
        val out = Outputs()
        audioQuietly(out) { mic?.listenerReady(port, out) }
        return out.list
    }

    /** 麦克风监听开不了：不回 SETUP，结束这次麦克风会话。 */
    fun micListenerFailed(reason: String): List<DmsdpOutput> {
        checkUsable()
        val out = Outputs()
        audioQuietly(out) { mic?.listenerFailed(reason, out) }
        return out.list
    }

    /**
     * 送一块车机麦克风的声音（格式按手机这次要的：PCM 是 16 位小端交错样本，AAC 是一个带 ADTS 头的帧），
     * 返回要写到 [DmsdpChannel.MIC] 的帧。麦克风连接还没建立、会话已结束或作废时返回空列表（不抛异常）。
     */
    fun sendMicAudio(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> {
        checkRange(data, offset, length)
        if (failed || closed) return emptyList()
        val out = Outputs()
        audioQuietly(out) { mic?.sendAudio(data, offset, length, out) }
        return out.list
    }

    /**
     * 在加密的控制连接上发一条设备消息（`$` 记录）：例如回复 [DmsdpEvent.UnhandledDeviceData] 里的消息。
     * [data] 不含 11 字节记录头，长度 1～65508。
     */
    fun sendDeviceData(module: Long, type: Int, data: ByteArray): List<DmsdpOutput> {
        checkUsable()
        check(controlState == ControlState.ENCRYPTED) { "控制连接还没有加密，不能发设备消息" }
        val out = Outputs()
        sendRecord(module, type, data, out)
        return out.list
    }

    /**
     * 把车机这边的音频焦点变化告诉手机：设备消息 module 4，type 是 [CarAudioFocus.type]，数据是本机设备 ID（UTF-8）。
     * 没声明扬声器、控制连接不在加密状态、会话已经结束时什么都不发（返回空列表），不抛异常。
     */
    fun sendAudioFocus(change: CarAudioFocus): List<DmsdpOutput> {
        val target = speaker
        if (failed || closed || target == null || controlState != ControlState.ENCRYPTED) return emptyList()
        val out = Outputs()
        sendRecord(PhoneAudioFocus.MODULE_AUDIO, change.type, config.deviceId.toByteArray(Charsets.UTF_8), out)
        audioQuietly(out) { target.onCarFocusSent(change, out) }
        return out.list
    }

    // ---------------- 文件分享（需要配置 [DmsdpConfig.fileShare]）----------------

    /** 接受 [DmsdpEvent.FileOffered]：回手机 102，之后文件内容以事件交出。过期或已处理的请求忽略。 */
    fun acceptFileOffer(offerId: Long, nowMillis: Long): List<DmsdpOutput> = fileAction { requireFiles().accept(offerId, nowMillis, it) }

    /** 拒绝文件请求：回 104；[insufficientSpace] 为 true 时回 103（空间不足）。 */
    fun rejectFileOffer(offerId: Long, insufficientSpace: Boolean = false): List<DmsdpOutput> =
        fileAction { requireFiles().reject(offerId, insufficientSpace, it) }

    /** 取消文件请求：还没接受时回 104，接收中回 108。已收到的部分由调用方丢弃。 */
    fun cancelFileOffer(offerId: Long): List<DmsdpOutput> = fileAction { requireFiles().cancel(offerId, it) }

    /** [DmsdpEvent.FileOfferReceived] 之后、文件都保存好了再调用：回手机 111，并关闭文件数据连接。 */
    fun confirmFileOfferSaved(offerId: Long): List<DmsdpOutput> = fileAction { requireFiles().confirmSaved(offerId, it) }

    /** 结束会话：有进行中的文件请求就尽量通知手机取消；之后不再产生输出。连接由调用方关闭。 */
    fun close(): List<DmsdpOutput> {
        if (closed) return emptyList()
        val out = Outputs()
        if (!failed) files?.close(out)
        speaker?.let { audio -> audioQuietly(out) { audio.close(lastNowMillis, out) } }
        mic?.let { m -> audioQuietly(out) { m.close(out) } }
        closed = true
        tcpKeepAlive = null
        udpKeepAlive = null
        return out.list
    }

    // ---------------- 业务 20 ----------------

    private fun feedSession(data: ByteArray, offset: Int, length: Int, out: Outputs) {
        sessionDecoder.append(data, offset, length)
        while (true) {
            val frame = sessionDecoder.next() ?: return
            val payload = frame.payload
            if (frame.module != SessionFrames.MODULE_SESSION || payload.size != SessionFrames.NEGOTIATION_BYTES ||
                payload[0].toInt() != 0
            ) {
                throw MalformedMessageException("业务 20 首轮协商与已知格式不符（module=${frame.module}，${payload.size} 字节）")
            }
            if (controlPort != 0) {
                out.send(DmsdpChannel.SESSION, SessionFrames.negotiationReply(controlPort))
            } else {
                pendingNegotiations++
                if (!listenerRequested) {
                    listenerRequested = true
                    out.add(DmsdpAction.OpenControlListener)
                }
            }
        }
    }

    // ---------------- 控制连接 ----------------

    private fun feedControl(data: ByteArray, offset: Int, length: Int, nowMillis: Long, out: Outputs) {
        controlDecoder.append(data, offset, length)
        while (true) {
            // 加密状态可能在同一批数据的两帧之间切换，所以逐帧判断。
            val frame = controlDecoder.next() ?: return
            if (controlState == ControlState.ENCRYPTED) {
                if (!frame.encrypted) throw MalformedMessageException("加密之后又收到明文控制帧")
                onEncryptedPayload(cipher.open(frame.payload), nowMillis, out)
            } else {
                if (frame.encrypted) throw MalformedMessageException("加密协商完成前收到密文控制帧")
                onPlainPayload(frame.payload, nowMillis, out)
            }
        }
    }

    private fun onPlainPayload(payload: ByteArray, nowMillis: Long, out: Outputs) {
        if (DeviceRecords.isRecord(payload)) throw MalformedMessageException("加密协商完成前收到设备消息")
        val message = DmsdpMessage.parse(payload)
        when {
            // 手机对 OPTIONS 的应答不代表已选择加密，继续等它的选择请求。
            message.isResponse -> out.event(DmsdpEvent.UnhandledControlMessage(message))
            message.method == SET_PARAMETER -> onCryptoSelection(message, nowMillis, out)
            else -> throw MalformedMessageException("加密协商完成前收到不支持的明文请求")
        }
    }

    /**
     * 手机的加密选择：`CryptoVer: 1`、`CryptoSwitch: 222`、`CryptoAlg: aes128-ctr`，可带
     * `UdpKA`（0/1，缺省按 0）与 `UdpKAport`。先**明文**回 200，再启用加密；提前加密手机切换不过来。
     */
    private fun onCryptoSelection(message: DmsdpMessage, nowMillis: Long, out: Outputs) {
        if (message.hasDuplicateHeaders()) throw MalformedMessageException("加密选择请求有重名头部")
        if (!message.contentLengthMatches()) throw MalformedMessageException("加密选择请求的 Content-Length 与正文不符")
        if (message.header("CryptoVer") != CRYPTO_VERSION || message.header("CryptoSwitch") != CRYPTO_ABILITY ||
            message.header("CryptoAlg") != CRYPTO_ALG
        ) {
            throw MalformedMessageException("手机选择了不支持的加密方式")
        }
        val cseq = message.header(CSEQ)
        if (cseq == null || !isAsciiDecimal(cseq)) throw MalformedMessageException("加密选择请求缺少有效 CSeq")
        val mode = when (message.header("UdpKA")) {
            null, "0" -> KeepAliveMode.TCP
            "1" -> KeepAliveMode.UDP
            else -> throw MalformedMessageException("手机选择了不支持的保活方式")
        }
        val udpPort = if (mode == KeepAliveMode.UDP) {
            parsePort(message.header("UdpKAport")) ?: throw MalformedMessageException("手机选择 UDP 保活但没给有效端口")
        } else {
            0
        }
        val response = DmsdpMessage.encode(
            STATUS_OK,
            listOf(
                CSEQ to cseq,
                "CryptoVer" to CRYPTO_VERSION,
                "CryptoSwitch" to CRYPTO_ABILITY,
                "CryptoAlg" to CRYPTO_ALG,
                "Platform" to config.platform.toString(),
            ),
        )
        out.send(DmsdpChannel.CONTROL, ControlFrames.encode(ControlFrames.FLAG_PLAIN, response))
        controlState = ControlState.ENCRYPTED
        if (mode == KeepAliveMode.UDP) {
            udpKeepAlive = UdpKeepAlive(nowMillis, config.keepAliveRetry)
            out.add(DmsdpAction.OpenKeepAliveSocket(udpPort))
        } else {
            tcpKeepAlive = TcpKeepAlive(nowMillis)
        }
        out.event(DmsdpEvent.EncryptionEnabled(mode))
    }

    private fun onEncryptedPayload(payload: ByteArray, nowMillis: Long, out: Outputs) {
        if (DeviceRecords.isRecord(payload)) {
            for (record in DeviceRecords.parseAll(payload)) onDeviceRecord(record, nowMillis, out)
            return
        }
        if (!DmsdpMessage.looksLikeText(payload)) {
            // 手机的声音数据连接没连上时（鸿蒙 3.0 / 4.x 一直连不上），经控制连接送声音：交给扬声器拼组播放。
            if (speaker?.onControlAudio(payload, nowMillis, out) == true) return
            // 其他认不出的：跳过，不断开。
            unrecognizedPayloads++
            if (unrecognizedPayloads <= UNRECOGNIZED_REPORTED || unrecognizedPayloads % UNRECOGNIZED_REPORT_EVERY == 0) {
                val head = payload.copyOf(minOf(payload.size, DmsdpEvent.UnrecognizedControlPayload.HEAD_BYTES))
                out.event(DmsdpEvent.UnrecognizedControlPayload(unrecognizedPayloads, payload.size, head))
            }
            return
        }
        val message = try {
            DmsdpMessage.parse(payload)
        } catch (e: MalformedMessageException) {
            // 看着像文字、却拆不开（头部行缺冒号、含非 ASCII 等）：跳过并记下开头，不断开连接。
            unrecognizedPayloads++
            if (unrecognizedPayloads <= UNRECOGNIZED_REPORTED || unrecognizedPayloads % UNRECOGNIZED_REPORT_EVERY == 0) {
                val head = payload.copyOf(minOf(payload.size, DmsdpEvent.UnrecognizedControlPayload.HEAD_BYTES))
                out.event(DmsdpEvent.UnrecognizedControlPayload(unrecognizedPayloads, payload.size, head))
            }
            return
        }
        when {
            isKeepAliveRequest(message) -> tcpKeepAlive?.onPeerRequest()
            isTimeSyncRequest(message) -> {
                rememberPhoneTime(message, nowMillis)
                sendText(DmsdpMessage.encode(STATUS_OK, listOf(CSEQ to message.header(CSEQ)!!)), out)
                out.event(
                    DmsdpEvent.TimeSyncRequested(
                        message.header(TIME_SERVER_PORT)!!,
                        message.header(TIME_US_HIGH)!!,
                        message.header(TIME_US_LOW)!!,
                    ),
                )
            }
            speaker != null && speaker.accepts(message) -> audioQuietly(out) { speaker.onMessage(message, nowMillis, out) }
            mic != null && mic.accepts(message) -> audioQuietly(out) { mic.onMessage(message, nowMillis, out) }
            else -> out.event(DmsdpEvent.UnhandledControlMessage(message))
        }
    }

    /**
     * 时间同步请求里的 `TimeUsHigh`、`TimeUsLow` 是手机单调时钟微秒数的高、低 32 位（低位按有符号整数写）。
     * 记下来估计手机时钟（忽略单程传输时间），麦克风的 RTP 时间戳尽量落在手机的时钟上。
     */
    private fun rememberPhoneTime(message: DmsdpMessage, nowMillis: Long) {
        val high = message.header(TIME_US_HIGH)?.toLongOrNull() ?: return
        val low = message.header(TIME_US_LOW)?.toLongOrNull() ?: return
        if (high !in 0..0x7FFF_FFFFL || low !in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL) return
        phoneTimeUs = (high shl 32) or (low and 0xFFFF_FFFFL)
        phoneTimeAtMillis = nowMillis
    }

    private fun phoneClockUs(nowMillis: Long): Long? = phoneTimeUs?.let { it + (nowMillis - phoneTimeAtMillis) * 1000 }

    /** 手机的保活：首行正好是 `GET_PARAMETER * DMSDP/1.0`、CSeq 为正整数、没有正文。只更新存活，不回 200。 */
    private fun isKeepAliveRequest(message: DmsdpMessage): Boolean {
        if (message.startLine != "$GET_PARAMETER * ${DmsdpMessage.VERSION}") return false
        val cseq = message.header(CSEQ) ?: return false
        return isAsciiDecimal(cseq) && cseq.any { it != '0' } && message.bodySize == 0 &&
            message.contentLengthMatches() && !message.hasDuplicateHeaders()
    }

    /** 时间同步：`SET_PARAMETER`，带 TimeServerPort、TimeUsHigh、TimeUsLow 三个头。回只含 CSeq 的 200。 */
    private fun isTimeSyncRequest(message: DmsdpMessage): Boolean {
        if (message.method != SET_PARAMETER) return false
        if (message.header(TIME_SERVER_PORT) == null || message.header(TIME_US_HIGH) == null ||
            message.header(TIME_US_LOW) == null
        ) {
            return false
        }
        val cseq = message.header(CSEQ) ?: return false
        return isAsciiDecimal(cseq) && message.contentLengthMatches() && !message.hasDuplicateHeaders()
    }

    private fun onDeviceRecord(record: DeviceRecord, nowMillis: Long, out: Outputs) {
        // 原始记录全量转储：在任何解析/分发之前，每条收到的记录都原样打出来（排查未知通道）。
        out.event(DmsdpEvent.RawRecord(sent = false, module = record.module, type = record.type, data = record.data))
        val deviceData = record.type == DeviceMessageHandler.TYPE_DEVICE_DATA
        when {
            deviceData && record.module == DeviceMessageHandler.MODULE_DEVICE -> devices.handle(
                record.data,
                reply = { sendRecord(DeviceMessageHandler.MODULE_DEVICE, DeviceMessageHandler.TYPE_DEVICE_DATA, it, out) },
                emit = { event ->
                    // 扬声器要按手机定声明（PCM 还是 AAC），所以先记下是哪台手机；正常在服务请求 104 之前到。
                    if (event is DmsdpEvent.PeerDeviceInfo) {
                        val name = (event.device["deviceName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        speaker?.onPeerDevice(event.deviceId, name, config.phoneVersion)
                    }
                    out.event(event)
                    // 服务请求 104 = 连接协商完成。此刻向手机请求应用元数据（华为官方协议 dataType=514）：
                    // 手机不会主动推音频元数据/歌词，必须车机先请求（2026-11-23 实弹验证，当时 _launcher 在场）。
                    if (config.requestMetadata && event is DmsdpEvent.ServicesRequested && !metadataRequested) {
                        metadataRequested = true
                        sendMetadataRequest("服务协商完成", out)
                    }
                    // 手机端 MediaClient (s.java) 只有在投屏虚拟桌面 CarLauncher 创建完成后才转发媒体回调；
                    // 实测日志显示 VideoStarted 后手机紧接着发 ServiceStatusChanged(serviceType=8, action=202)，
                    // 此刻 CarLauncher 已完成初始化，补发一次 514 触发手机端 sendCurrentDataToCar()。
                    if (config.requestMetadata && event is DmsdpEvent.ServiceStatusChanged &&
                        !metadataRequestedOnDisplayStart &&
                        (event.serviceType == 8L || event.serviceType == 1L)
                    ) {
                        metadataRequestedOnDisplayStart = true
                        sendMetadataRequest("显示服务状态同步(action=${event.action})", out)
                    }
                },
            )
            deviceData && record.module == DeviceMessageHandler.MODULE_DISPLAY -> {
                val port = DeviceMessageHandler.parseDisplayPort(record.data)
                if (port != null) {
                    out.event(DmsdpEvent.DisplayPortAnnounced(port))
                } else {
                    val command = Ea.decodeComplete(record.data, 0, "显示命令")
                    out.event(
                        DmsdpEvent.UnsupportedDeviceCommand(
                            record.module,
                            command.value,
                            record.data.copyOfRange(command.next, record.data.size),
                        ),
                    )
                }
            }
            // 通话 / 语音助手状态：正常走 module 1，module 4 上同样的 type 也认（放在焦点消息前面）。
            speaker != null && PhoneStateMessages.accepts(record.module, record.type) ->
                audioQuietly(out) { speaker.onPhoneState(record.module, record.type, record.data, out) }
            speaker != null && record.module == PhoneAudioFocus.MODULE_AUDIO ->
                audioQuietly(out) { speaker.onFocusRecord(record.type, record.data, nowMillis, out) }
            record.module == MODULE_MANAGEMENT && record.type == TYPE_MANAGEMENT && files != null -> {
                val handled = files.onManagement(
                    record.data,
                    sendManagement = { sendRecord(MODULE_MANAGEMENT, TYPE_MANAGEMENT, it, out) },
                    out = out,
                )
                if (!handled) out.event(DmsdpEvent.UnhandledDeviceData(record.module, record.type, record.data))
            }
            // 管理通道的 JSON 消息（500 能力查询、533 应用状态等）：解析成结构化事件，方便诊断和接功能；
            // 不是 JSON 的照旧走 UnhandledDeviceData。type 17 不认（上面文件分享分支管，或没开分享时保持旧路径）。
            record.module == MODULE_MANAGEMENT && ManagementMessages.accepts(record.type) -> {
                when (val parsed = ManagementMessages.parse(record.type, record.data)) {
                    null -> out.event(DmsdpEvent.UnhandledDeviceData(record.module, record.type, record.data))
                    else -> {
                        // 515 音频元数据要求回执（{"MediaDataResp":{"RespCode":100000}}），不回手机可能停推。
                        // 元数据到达走 MediaDataReceived 事件（诊断日志可见），后续接 UI/歌词消费方。
                        parsed.reply?.let { sendRecord(MODULE_MANAGEMENT, record.type, JsonCodec.encodeToUtf8(it), out) }
                        parsed.mediaData?.let { out.event(DmsdpEvent.MediaDataReceived(it)) }
                        if (parsed.carDesktop) {
                            out.event(DmsdpEvent.CarDesktopRequested(parsed.summary))
                        } else {
                            out.event(DmsdpEvent.ManagementParsed(parsed.type, parsed.summary))
                        }
                    }
                }
            }
            else -> out.event(DmsdpEvent.UnhandledDeviceData(record.module, record.type, record.data))
        }
    }

    // ---------------- 发送 ----------------

    private fun optionsHeaders(): List<Pair<String, String>> = listOf(
        "CryptoVer" to CRYPTO_VERSION,
        "CryptoAbility" to CRYPTO_ABILITY,
        "CryptoAlg" to CRYPTO_ALG,
        "UdpKA" to if (config.advertiseUdpKeepAlive) "1" else "0",
        "KaRetry" to config.keepAliveRetry.toString(),
        "LowDelay" to "1",
    )

    /** 本机发起的请求共用一个 CSeq 计数器：OPTIONS 是 1，之后的保活 GET 从 2 起。CSeq 放在最后一个头部。 */
    private fun sendRequest(method: String, headers: List<Pair<String, String>>, out: Outputs) {
        val text = DmsdpMessage.encode("$method * ${DmsdpMessage.VERSION}", headers + (CSEQ to nextCSeq.toString()))
        sendText(text, out)
        nextCSeq++
    }

    private fun sendText(text: ByteArray, out: Outputs) {
        val frame = if (controlState == ControlState.ENCRYPTED) {
            ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, cipher.seal(random, text))
        } else {
            ControlFrames.encode(ControlFrames.FLAG_PLAIN, text)
        }
        out.send(DmsdpChannel.CONTROL, frame)
    }

    /** 扬声器的消息只在加密的控制连接上发；控制连接已经不在加密状态（例如被手机关了）时丢掉。 */
    private fun sendSpeakerText(text: ByteArray, out: Outputs) {
        if (controlState == ControlState.ENCRYPTED) sendText(text, out)
    }

    private fun sendMetadataRequest(reason: String, out: Outputs) {
        sendRecord(
            MODULE_MANAGEMENT,
            ManagementMessages.METADATA_REQUEST_TYPE,
            JsonCodec.encodeToUtf8(ManagementMessages.metadataRequest(config.host.fullModel)),
            out,
        )
        out.event(
            DmsdpEvent.ManagementParsed(
                ManagementMessages.METADATA_REQUEST_TYPE,
                "已请求应用元数据（$reason，RequestAAData=127，RequestCaller=${config.host.fullModel}）",
            ),
        )
    }

    private fun sendRecord(module: Long, type: Int, data: ByteArray, out: Outputs) {
        // 发出的记录同样全量转储（自动应答不发日志的话，双向对照就断了）。
        out.event(DmsdpEvent.RawRecord(sent = true, module = module, type = type, data = data))
        val record = DeviceRecords.encode(module, type, data)
        out.send(DmsdpChannel.CONTROL, ControlFrames.encode(ControlFrames.FLAG_ENCRYPTED, cipher.seal(random, record)))
    }

    // ---------------- 工具 ----------------

    private fun requireFiles(): IncomingFileTransfer = files ?: throw IllegalStateException("没有启用文件分享")

    /** 音频部分出了意外（程序缺陷）只记一笔：声音的问题不能让设备会话作废。 */
    private inline fun audioQuietly(out: Outputs, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            out.event(DmsdpEvent.AudioNote("音频处理出错（${e.javaClass.simpleName}：${e.message}），已忽略"))
        }
    }

    private inline fun fileAction(block: (Outputs) -> Unit): List<DmsdpOutput> {
        checkUsable()
        val out = Outputs()
        block(out)
        return out.list
    }

    private fun checkUsable() {
        check(!failed) { "会话已因协议错误作废" }
        check(!closed) { "会话已关闭" }
    }

    private inline fun fatalOnError(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            tcpKeepAlive = null
            udpKeepAlive = null
            throw e
        }
    }

    internal class Outputs {
        val list = ArrayList<DmsdpOutput>()
        fun add(output: DmsdpOutput) {
            list += output
        }

        fun send(channel: DmsdpChannel, bytes: ByteArray) = add(DmsdpAction.Send(channel, bytes))
        fun event(event: DmsdpEvent) = add(event)
    }

    private companion object {
        /** 认不出的载荷：前几个都交出事件，之后每第这么多个交一次，免得刷屏。 */
        const val UNRECOGNIZED_REPORTED = 3
        const val UNRECOGNIZED_REPORT_EVERY = 100
        const val OPTIONS = "OPTIONS"
        const val SET_PARAMETER = "SET_PARAMETER"
        const val GET_PARAMETER = "GET_PARAMETER"
        const val STATUS_OK = "DMSDP/1.0 200 OK"
        const val CSEQ = "CSeq"
        const val CRYPTO_VERSION = "1"
        const val CRYPTO_ABILITY = "222"
        const val CRYPTO_ALG = "aes128-ctr"
        const val TIME_SERVER_PORT = "TimeServerPort"
        const val TIME_US_HIGH = "TimeUsHigh"
        const val TIME_US_LOW = "TimeUsLow"

        /** 文件分享等管理消息：设备记录 module 1 / type 17。 */
        const val MODULE_MANAGEMENT = 1L
        const val TYPE_MANAGEMENT = 17

        /** 1～5 位 ASCII 数字、值在 1～65535 之间的端口。 */
        fun parsePort(text: String?): Int? {
            if (text == null || text.length !in 1..5 || !isAsciiDecimal(text)) return null
            return text.toInt().takeIf { it in 1..0xFFFF }
        }
    }
}
