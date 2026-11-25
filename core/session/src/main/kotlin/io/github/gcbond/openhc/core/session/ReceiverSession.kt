package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.dmsdp.DmsdpAction
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.DmsdpEvent
import io.github.gcbond.openhc.core.dmsdp.DmsdpOutput
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.AudioTransport
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.dmsdp.audio.MicStreamListener
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.VideoGap
import io.github.gcbond.openhc.core.hisight.HiSightChannel
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.HiSightOutput
import io.github.gcbond.openhc.core.nearby.NearbySession

/**
 * 一次有线投屏连接的会话编排：持有 nearby、auth、dmsdp、hisight 四层的会话对象，负责层与层之间的交接。
 * 本身不做 I/O，用法见 [SessionLogic]；[SocketRunner] 是配套的阻塞 I/O 执行器。
 *
 * 交接顺序（谁连谁、什么时候开监听、什么时候回端口）见 docs/ARCHITECTURE.md「会话编排」，概括：
 * 1. 在控制端口开监听 → [SessionEvent.ControlListening]（之后才能发 carInfo）。
 * 2. 手机连上控制通道 → nearby 发 connectId、握手。
 * 3. 手机请求业务 19 → 在本机地址开认证监听，开好后把端口回给手机；手机连上后交给 auth。
 * 4. 手机请求业务 20：认证握手之前拒绝；认证进行中先留着；认证成功后开监听、回端口。
 * 5. 手机连上业务 20 → 用会话密钥建 dmsdp；首轮协商要求另开控制监听，开好后把端口回到业务 20 连接上。
 * 6. 手机连上控制监听 → 明文 OPTIONS → 手机选加密和保活（UDP 时车机去连手机给的端口）。
 * 7. 手机通告投屏端口 → 用同一个会话密钥建 hisight，车机去连；RTSP 协商到 SETUP 后
 *    先连视频、连上后再连回控，两条都连上才发 PLAY。
 * 8. 视频帧经 [videoSink] 同步交出（缓冲区复用规则见 [io.github.gcbond.openhc.core.hisight.AccessUnit]），不另外复制。
 * 9. 声明了扬声器（[ReceiverConfig.audio]）时，手机触发音频数据会话：dmsdp 要求开端口，这里在车机 IP 上开数据端口
 *    （偶数 P）的 TCP 监听，开好后交回 dmsdp 发 SETUP；手机连上来的音频连接的字节交给 dmsdp 拆包，
 *    音频帧和状态经构造时给的音频出口交出。音频的监听、连接出错或被关只影响声音，会话继续。
 *    车机这边的音频焦点变化用 [sendAudioFocus] 告诉手机。
 * 10. 声明了麦克风（[ReceiverConfig.mic]）时，手机发麦克风 SETUP：dmsdp 要求开端口，这里在车机 IP 上开一个
 *    TCP 监听（端口由系统分配），开好交回 dmsdp 回 200；手机连上后，采到的声音经 [sendMicAudio] 写到这条连接上。
 *    麦克风的监听、连接出错或被关同样只影响麦克风。
 *
 * 结束时（任何原因）：先让投屏层收尾（释放按下的输入、发 TEARDOWN），再结束设备会话，然后由上到下关闭全部资源：
 * 投屏控制、视频、回控 → UDP 保活、设备控制连接与监听、业务 20 连接与监听 → 认证连接与监听 → 控制通道与监听，
 * 最后输出 [SessionEvent.Ended]。
 *
 * 不是线程安全的：所有方法在同一个线程上串行调用。视频回调也在这个线程上（[onData] 里）同步执行。
 *
 * 无线连接的 WiFi 阶段也用本类：[WirelessReceiverSession] 在车机 WiFi 地址上建一个内嵌实例，控制通道是通道 3、
 * 认证已在蓝牙上做完（见 [ControlLink.wifi]），之后的设备会话和投屏与有线完全相同。
 */
class ReceiverSession internal constructor(
    private val config: ReceiverConfig,
    private val videoSink: AccessUnitSink,
    private val layers: Layers,
    private val link: ControlLink = ControlLink.wired(config),
    private val ids: IdSource = IdSource(),
) : SessionLogic {

    /**
     * @param audio 音频数据会话的出口（[ReceiverConfig.audio] 打开时才用得到），在协议线程上同步回调，
     *   缓冲区复用规则见 [io.github.gcbond.openhc.core.dmsdp.audio.AudioPacket]。
     * @param mic 麦克风数据会话的状态出口（[ReceiverConfig.mic] 打开时才用得到），在协议线程上同步回调：
     *   收到开始就开采集，采到的声音经 [sendMicAudio] 送回来。
     */
    constructor(
        config: ReceiverConfig,
        videoSink: AccessUnitSink,
        audio: AudioStreamListener = AudioStreamListener.NONE,
        mic: MicStreamListener = MicStreamListener.NONE,
    ) : this(config, videoSink, ProductionLayers(config, audio, mic))

    private enum class Kind { LISTENER, TCP, UDP }

    private enum class State { OPENING, OPEN, CLOSED }

    private class Endpoint(val id: Int, val channel: SessionChannel, val kind: Kind) {
        var state = State.OPENING
        var port = 0
        var remoteHost: String? = null

        /** 监听接受的那条连接。 */
        var accept: Endpoint? = null
    }

    private class Out {
        val list = ArrayList<SessionOutput>(4)

        fun add(output: SessionOutput) {
            list += output
        }
    }

    /** 视频回调抛出的异常，与各层的协议错误区分开。 */
    private class SinkFailure(message: String) : RuntimeException(message)

    private val endpoints = HashMap<Int, Endpoint>()

    private var controlListener: Endpoint? = null
    private var control: Endpoint? = null
    private var authListener: Endpoint? = null
    private var authConn: Endpoint? = null
    private var dmsdpListener: Endpoint? = null
    private var dmsdpConn: Endpoint? = null
    private var dmsdpControlListener: Endpoint? = null
    private var dmsdpControl: Endpoint? = null
    private var keepAlive: Endpoint? = null
    private var hisightControl: Endpoint? = null
    private var video: Endpoint? = null
    private var remote: Endpoint? = null

    /** 音频数据会话的监听（数据端口 P）；它接受的连接是 [Endpoint.accept]。 */
    private var audioListener: Endpoint? = null
    private var audioPort = 0
    private var audioAttempts = 0

    /** 这次音频数据会话走 UDP（数据端口收数据报，不 accept）；收到过第一个数据报。 */
    private var audioUdp = false
    private var audioUdpHeard = false

    /** 麦克风数据会话的监听；它接受的连接（手机连进来）是 [Endpoint.accept]。 */
    private var micListener: Endpoint? = null

    private var nearby: NearbyLayer? = null
    private var auth: AuthLayer? = null
    private var dmsdp: DmsdpLayer? = null
    private var hisight: HiSightLayer? = null

    private var started = false
    private var ended = false
    private var activeLayer = SessionLayer.SESSION

    private var authAnswered = false
    private var authSucceeded = false

    /** 认证给的 16 字节会话密钥：交给 dmsdp 和 hisight（它们各自复制）后清零。 */
    private var sessionKey: ByteArray? = null

    /** 手机报的 HiCar 版本：有线来自自己的认证，无线来自蓝牙上的认证（[ControlLink.phoneVersion]）。 */
    private var phoneVersion: String? = link.phoneVersion

    /** 等认证监听开好的业务 19 请求。 */
    private val pendingAuthRequests = ArrayList<NearbyOutput.SocketRequest>()

    /** 等认证成功、或等业务 20 监听开好的业务 20 请求。 */
    private val pendingDmsdpRequests = ArrayList<NearbyOutput.SocketRequest>()

    private var hisightHost = ""
    private var hisightPort = 0
    private var hisightEnded = false
    private var playAccepted = false

    /** SETUP 给出的回控端口，等视频连上后再连；0 表示没有待连的。 */
    private var pendingRemotePort = 0
    private var videoParameters: VideoParameters? = null
    private var framesDelivered = 0L

    /** 这一轮喂视频时交出的帧带的断档情况，喂完各写一条诊断。 */
    private val videoGaps = ArrayList<VideoGap>()

    private val sinkAdapter = AccessUnitSink { unit ->
        framesDelivered++
        unit.gapBefore?.let { videoGaps += it }
        try {
            videoSink.onAccessUnit(unit)
        } catch (e: Exception) {
            throw SinkFailure(describe(e))
        }
    }

    init {
        // 无线的 WiFi 阶段：认证已经在蓝牙上做完，会话密钥直接交进来，业务 20 的请求一到就可以回。
        link.takeSessionKey()?.let {
            sessionKey = it
            authAnswered = true
            authSucceeded = true
        }
    }

    override val isEnded: Boolean get() = ended

    override val nextTickAtMillis: Long?
        get() {
            if (ended) return null
            val handshake = nearby?.takeIf { control?.state == State.OPEN }?.nextTickAtMillis
            val device = dmsdp?.nextTickAtMillis
            return when {
                handshake == null -> device
                device == null -> handshake
                else -> minOf(handshake, device)
            }
        }

    /** 已经交给视频回调的帧数。 */
    val videoFrames: Long get() = framesDelivered

    override fun start(nowMillis: Long): List<SessionOutput> {
        check(!started) { "start 只能调用一次" }
        started = true
        return session { out ->
            val listener = listen(SessionChannel.CONTROL, link.bindHost, link.port, link.requireLocalHost, out)
            controlListener = listener
            control = listener.accept
        }
    }

    override fun onListening(id: Int, port: Int, nowMillis: Long): List<SessionOutput> = session { out ->
        val ep = endpoints[id] ?: return@session
        val bound = ep.kind == Kind.LISTENER || (ep.kind == Kind.UDP && ep === audioListener)
        if (!bound || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        ep.port = port
        when (ep) {
            controlListener -> out.add(SessionEvent.ControlListening(port))
            authListener -> answerAll(pendingAuthRequests, port, out)
            dmsdpListener -> answerAll(pendingDmsdpRequests, port, out)
            dmsdpControlListener -> {
                val layer = dmsdp ?: return@session
                handleDmsdp(call(SessionLayer.DMSDP) { layer.controlListenerReady(port) }, out)
            }
            audioListener -> onAudioListening(nowMillis, out)
            micListener -> {
                val layer = dmsdp ?: return@session
                handleDmsdp(call(SessionLayer.DMSDP) { layer.micListenerReady(port) }, out)
            }
        }
    }

    override fun onAccepted(id: Int, remoteHost: String, nowMillis: Long): List<SessionOutput> = session { out ->
        val ep = endpoints[id] ?: return@session
        if (ep.kind != Kind.TCP || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        ep.remoteHost = remoteHost
        when (ep) {
            control -> {
                out.add(SessionEvent.PhoneConnected(remoteHost))
                val layer = layers.nearby()
                nearby = layer
                handleNearby(call(SessionLayer.NEARBY) { layer.start(nowMillis) }, out)
            }
            authConn -> auth = layers.auth()
            dmsdpConn -> {
                val key = checkNotNull(sessionKey) { "认证成功之前不该开业务 20 监听" }
                val layer = layers.dmsdp(key, remoteHost, phoneVersion)
                dmsdp = layer
                handleDmsdp(call(SessionLayer.DMSDP) { layer.onConnected(DmsdpChannel.SESSION, nowMillis) }, out)
            }
            dmsdpControl -> {
                val layer = checkNotNull(dmsdp)
                handleDmsdp(call(SessionLayer.DMSDP) { layer.onConnected(DmsdpChannel.CONTROL, nowMillis) }, out)
            }
            audioListener?.accept -> {
                val layer = checkNotNull(dmsdp)
                handleDmsdp(call(SessionLayer.DMSDP) { layer.onConnected(DmsdpChannel.AUDIO, nowMillis) }, out)
            }
            micListener?.accept -> {
                val layer = checkNotNull(dmsdp)
                handleDmsdp(call(SessionLayer.DMSDP) { layer.onConnected(DmsdpChannel.MIC, nowMillis) }, out)
            }
        }
    }

    override fun onConnected(id: Int, nowMillis: Long): List<SessionOutput> = session { out ->
        val ep = endpoints[id] ?: return@session
        if (ep.kind != Kind.TCP || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        val hs = hisight ?: return@session
        when (ep) {
            hisightControl -> handleHiSight(call(SessionLayer.HISIGHT) { hs.start() }, out)
            video -> {
                handleHiSight(call(SessionLayer.HISIGHT) { hs.onVideoConnected() }, out)
                // 视频连上之后才去连回控，两条都连上后投屏层才发 PLAY。
                if (!ended && pendingRemotePort != 0) {
                    val port = pendingRemotePort
                    pendingRemotePort = 0
                    remote = connect(SessionChannel.REMOTE_CONTROL, hisightHost, port, out)
                }
            }
            remote -> handleHiSight(call(SessionLayer.HISIGHT) { hs.onRemoteControlConnected() }, out)
        }
    }

    override fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<SessionOutput> =
        session { out ->
            val ep = endpoints[id] ?: return@session
            if (ep.kind != Kind.TCP || ep.state != State.OPEN) return@session
            when (ep) {
                control -> {
                    val layer = checkNotNull(nearby)
                    handleNearby(call(SessionLayer.NEARBY) { layer.feed(data, offset, length) }, out)
                }
                authConn -> {
                    val layer = checkNotNull(auth)
                    handleAuth(call(SessionLayer.AUTH) { layer.feed(data, offset, length) }, out)
                }
                dmsdpConn -> feedDmsdp(DmsdpChannel.SESSION, data, offset, length, nowMillis, out)
                dmsdpControl -> feedDmsdp(DmsdpChannel.CONTROL, data, offset, length, nowMillis, out)
                audioListener?.accept -> feedDmsdp(DmsdpChannel.AUDIO, data, offset, length, nowMillis, out)
                micListener?.accept -> feedDmsdp(DmsdpChannel.MIC, data, offset, length, nowMillis, out)
                hisightControl -> {
                    val hs = checkNotNull(hisight)
                    val wallClock = config.wallClock()
                    handleHiSight(call(SessionLayer.HISIGHT) { hs.feedControl(data, offset, length, wallClock) }, out)
                }
                video -> feedVideo(data, offset, length, out)
                remote -> {
                    // 协议没有定义回控连接上手机发来的数据，投屏层收到即判为协议错误。
                    val hs = checkNotNull(hisight)
                    call(SessionLayer.HISIGHT) { hs.feedRemoteControl(data, offset, length) }
                }
            }
        }

    /**
     * UDP 保活数据报。格式不对时直接结束会话：保活 socket 已 connect 到手机，只会收到手机的包，格式不对说明两端对不上。
     */
    override fun onDatagram(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<SessionOutput> =
        session { out ->
            val ep = endpoints[id] ?: return@session
            if (ep.state != State.OPEN) return@session
            val layer = checkNotNull(dmsdp)
            when {
                ep === keepAlive -> handleDmsdp(call(SessionLayer.DMSDP) { layer.onDatagram(data, offset, length, nowMillis) }, out)
                ep === audioListener && ep.kind == Kind.UDP -> {
                    // UDP 没有「连上」：第一个数据报到达就当手机开始发了，之后每个数据报是完整的一帧，按到达顺序喂。
                    if (!audioUdpHeard) {
                        audioUdpHeard = true
                        handleDmsdp(call(SessionLayer.DMSDP) { layer.onConnected(DmsdpChannel.AUDIO, nowMillis) }, out)
                    }
                    handleDmsdp(call(SessionLayer.DMSDP) { layer.feed(DmsdpChannel.AUDIO, data, offset, length, nowMillis) }, out)
                }
            }
        }

    override fun onClosed(id: Int, nowMillis: Long): List<SessionOutput> = session { out ->
        val ep = endpoints[id] ?: return@session
        if (ep.kind != Kind.TCP || ep.state != State.OPEN) return@session
        close(ep, out)
        when (ep) {
            control -> onControlClosed(out)
            authConn -> {
                // 认证成功之后手机关掉认证连接是正常的；之前关掉就是认证失败。
                if (!authSucceeded) handleAuth(call(SessionLayer.AUTH) { checkNotNull(auth).onConnectionClosed() }, out)
            }
            dmsdpConn -> {
                handleDmsdp(call(SessionLayer.DMSDP) { checkNotNull(dmsdp).onClosed(DmsdpChannel.SESSION, nowMillis) }, out)
                if (!ended && dmsdpControlListener == null) {
                    finish(EndReason.PeerClosed(SessionChannel.DMSDP_SESSION, "还没协商出设备控制端口"), out)
                }
            }
            dmsdpControl -> {
                handleDmsdp(call(SessionLayer.DMSDP) { checkNotNull(dmsdp).onClosed(DmsdpChannel.CONTROL, nowMillis) }, out)
                if (!ended) {
                    if (hisightActive()) {
                        diag(SessionLayer.SESSION, "设备控制连接被手机关闭，投屏继续", out)
                    } else {
                        finish(EndReason.PeerClosed(SessionChannel.DMSDP_CONTROL, "投屏还没开始"), out)
                    }
                }
            }
            hisightControl -> onHiSightControlClosed(out)
            video -> handleHiSight(call(SessionLayer.HISIGHT) { checkNotNull(hisight).onVideoClosed() }, out)
            remote -> handleHiSight(call(SessionLayer.HISIGHT) { checkNotNull(hisight).onRemoteControlClosed() }, out)
            // 音频连接被手机关掉只影响声音。
            audioListener?.accept ->
                handleDmsdp(call(SessionLayer.DMSDP) { checkNotNull(dmsdp).onClosed(DmsdpChannel.AUDIO, nowMillis) }, out)
            // 麦克风连接被手机关掉同样只影响麦克风。
            micListener?.accept ->
                handleDmsdp(call(SessionLayer.DMSDP) { checkNotNull(dmsdp).onClosed(DmsdpChannel.MIC, nowMillis) }, out)
        }
    }

    override fun onIoError(id: Int, message: String, nowMillis: Long): List<SessionOutput> = session { out ->
        val ep = endpoints[id] ?: return@session
        if (ep.state == State.CLOSED) return@session
        if (isAudio(ep)) {
            onAudioIoError(ep, message, nowMillis, out)
            return@session
        }
        if (isMic(ep)) {
            onMicIoError(ep, message, nowMillis, out)
            return@session
        }
        // 除音频、麦克风以外，任何一条还在用的连接、监听或 UDP 出错都结束整个会话。
        finish(EndReason.NetworkError(ep.channel, message), out)
    }

    override fun onTick(nowMillis: Long): List<SessionOutput> = session { out ->
        val n = nearby
        if (n != null && control?.state == State.OPEN) {
            handleNearby(call(SessionLayer.NEARBY) { n.onTick(nowMillis) }, out)
        }
        val d = dmsdp
        if (!ended && d != null) handleDmsdp(call(SessionLayer.DMSDP) { d.onTick(nowMillis) }, out)
    }

    override fun stop(nowMillis: Long): List<SessionOutput> = session { out -> finish(EndReason.LocalStop, out) }

    /**
     * 发一次触摸或按键（需要 [ReceiverConfig.touchInput] / [ReceiverConfig.keyInput] / [ReceiverConfig.mediaKeyInput]，
     * 且手机在协商里选回了）。
     * 回控连接还没就绪或会话已结束时返回空列表。输入不合法时抛 [IllegalArgumentException]、
     * 动作顺序不对时抛 [IllegalStateException]，会话不受影响。与其他方法一样要在协议线程上调用。
     */
    fun sendInput(input: HiSightInput): List<SessionOutput> {
        if (ended || hisightEnded) return emptyList()
        val hs = hisight ?: return emptyList()
        val outputs = hs.sendInput(input)
        val out = Out()
        for (o in outputs) if (o is HiSightOutput.Send) write(hisightEndpoint(o.channel), o.bytes, out)
        return out.list
    }

    /**
     * 送一块车机麦克风的声音（格式按手机这次要的，见 [io.github.gcbond.openhc.core.dmsdp.audio.MicParameters]）。
     * 麦克风连接还没建立或会话已结束时返回空列表。与其他方法一样要在协议线程上调用；麦克风的问题不结束会话。
     */
    fun sendMicAudio(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<SessionOutput> {
        if (ended) return emptyList()
        val layer = dmsdp ?: return emptyList()
        val conn = micListener?.accept ?: return emptyList()
        if (conn.state != State.OPEN) return emptyList()
        val out = Out()
        try {
            handleDmsdp(layer.sendMicAudio(data, offset, length, nowMillis), out)
        } catch (e: RuntimeException) {
            diag(SessionLayer.DMSDP, "麦克风送声音出错（${describe(e)}），已忽略", out)
        }
        return out.list
    }

    /**
     * 把车机这边的音频焦点变化告诉手机（例如车机别的应用拿走了焦点，让手机暂停）。设备会话还没加密、
     * 没声明扬声器或会话已结束时返回空列表。与其他方法一样要在协议线程上调用。
     */
    fun sendAudioFocus(change: CarAudioFocus): List<SessionOutput> {
        if (ended) return emptyList()
        val layer = dmsdp ?: return emptyList()
        return session { out -> handleDmsdp(call(SessionLayer.DMSDP) { layer.sendAudioFocus(change) }, out) }
    }

    // ---------------- nearby ----------------

    private fun handleNearby(outputs: List<NearbyOutput>, out: Out) {
        for (o in outputs) {
            if (ended) return
            when (o) {
                is NearbyOutput.Send -> write(control, o.bytes, out)
                is NearbyOutput.HandshakeCompleted -> out.add(SessionEvent.ControlHandshakeCompleted(o.encrypted))
                NearbyOutput.HandshakeTimedOut -> finish(EndReason.ControlHandshakeTimeout, out)
                is NearbyOutput.SocketRequest -> onSocketRequest(o, out)
                is NearbyOutput.Note -> diag(SessionLayer.NEARBY, o.text, out)
            }
        }
    }

    /**
     * 建 socket 请求的处理规则：
     * - 业务 19：第一次请求时在本机地址开认证监听，开好后回端口；之后的重复请求回同一个端口。
     * - 业务 20（控制通道的通道号、标签 DMSDP）：认证握手之前拒绝；握手之后、认证成功之前先留着；
     *   认证成功后开监听、回端口，重复请求回同一个端口。
     * - 其他业务一律拒绝。
     */
    private fun onSocketRequest(request: NearbyOutput.SocketRequest, out: Out) {
        when {
            request.businessId == NearbySession.BUSINESS_AUTH && link.preAuthenticated ->
                reject(request, "认证已在蓝牙上做完", out)
            request.businessId == NearbySession.BUSINESS_AUTH -> {
                val listener = authListener
                when {
                    listener == null -> {
                        pendingAuthRequests += request
                        val opened = listen(SessionChannel.AUTH, config.localIp, 0, null, out)
                        authListener = opened
                        authConn = opened.accept
                    }
                    listener.state == State.OPEN -> answer(request, listener.port, out)
                    else -> pendingAuthRequests += request
                }
            }
            isDmsdpRequest(request) -> when {
                authSucceeded -> acceptDmsdp(request, out)
                authAnswered -> {
                    pendingDmsdpRequests += request
                    diag(SessionLayer.SESSION, "设备会话请求先于认证完成到达，认证成功后再回端口", out)
                }
                else -> reject(request, "认证还没开始", out)
            }
            else -> reject(request, "不支持的业务", out)
        }
    }

    private fun isDmsdpRequest(request: NearbyOutput.SocketRequest) =
        request.businessId == NearbySession.BUSINESS_DMSDP && request.channel == link.channel &&
            request.tag == NearbySession.TAG_DMSDP

    private fun acceptDmsdp(request: NearbyOutput.SocketRequest, out: Out) {
        val listener = dmsdpListener
        when {
            listener == null -> {
                pendingDmsdpRequests += request
                val opened = listen(SessionChannel.DMSDP_SESSION, config.localIp, 0, null, out)
                dmsdpListener = opened
                dmsdpConn = opened.accept
            }
            listener.state == State.OPEN -> answer(request, listener.port, out)
            else -> pendingDmsdpRequests += request
        }
    }

    private fun answerAll(requests: MutableList<NearbyOutput.SocketRequest>, port: Int, out: Out) {
        val waiting = ArrayList(requests)
        requests.clear()
        for (request in waiting) {
            if (ended) return
            answer(request, port, out)
        }
    }

    private fun answer(request: NearbyOutput.SocketRequest, port: Int, out: Out) {
        val layer = checkNotNull(nearby)
        handleNearby(call(SessionLayer.NEARBY) { layer.accept(request, port) }, out)
    }

    private fun reject(request: NearbyOutput.SocketRequest, why: String, out: Out) {
        diag(SessionLayer.SESSION, "拒绝建 socket 请求（$request）：$why", out)
        val layer = checkNotNull(nearby)
        handleNearby(call(SessionLayer.NEARBY) { layer.reject(request) }, out)
    }

    /** 控制通道被手机关闭：设备会话的监听已经建立并回过端口才继续，否则结束。 */
    private fun onControlClosed(out: Out) {
        val listener = dmsdpListener
        when {
            !authSucceeded -> finish(EndReason.PeerClosed(SessionChannel.CONTROL, "认证完成前"), out)
            listener == null -> finish(EndReason.PeerClosed(SessionChannel.CONTROL, "设备会话还没开始"), out)
            listener.state != State.OPEN || pendingDmsdpRequests.isNotEmpty() ->
                finish(EndReason.PeerClosed(SessionChannel.CONTROL, "设备会话请求还没应答"), out)
            else -> diag(SessionLayer.SESSION, "控制通道被手机关闭，设备会话继续", out)
        }
    }

    // ---------------- auth ----------------

    private fun handleAuth(actions: List<HiCarAuthAction>, out: Out) {
        for (a in actions) {
            if (ended) return
            when (a) {
                is HiCarAuthAction.Send -> write(authConn, a.bytes, out)
                is HiCarAuthAction.HandshakeAnswered -> {
                    authAnswered = true
                    out.add(SessionEvent.AuthStarted(a.mode))
                    a.pinCode?.let { out.add(SessionEvent.ShowPinCode(it)) }
                }
                is HiCarAuthAction.Succeeded -> {
                    sessionKey = a.sessionKey.copyOf()
                    a.sessionKey.fill(0)
                    authSucceeded = true
                    phoneVersion = a.phoneVersion
                    out.add(SessionEvent.Authenticated(a.mode))
                    if (pendingDmsdpRequests.isNotEmpty()) {
                        val waiting = ArrayList(pendingDmsdpRequests)
                        pendingDmsdpRequests.clear()
                        for (request in waiting) acceptDmsdp(request, out)
                    }
                }
                is HiCarAuthAction.Failed -> finish(EndReason.AuthFailed(a.failure), out)
                is HiCarAuthAction.Note -> diag(SessionLayer.AUTH, a.text, out)
            }
        }
    }

    // ---------------- dmsdp ----------------

    private fun feedDmsdp(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, now: Long, out: Out) {
        val layer = checkNotNull(dmsdp)
        handleDmsdp(call(SessionLayer.DMSDP) { layer.feed(channel, data, offset, length, now) }, out)
    }

    /** 主动给手机发一条设备数据（经 DMSDP 层加密打包，写到设备控制连接）。要在协议线程上调用（SocketRunner.submit）。 */
    fun sendDeviceData(module: Long, type: Int, data: ByteArray): List<SessionOutput> {
        val out = Out()
        handleDmsdp(dmsdp?.sendDeviceData(module, type, data) ?: emptyList(), out)
        return out.list
    }

    private fun handleDmsdp(outputs: List<DmsdpOutput>, out: Out) {
        for (o in outputs) {
            if (ended) return
            when (o) {
                is DmsdpAction.Send -> write(dmsdpEndpoint(o.channel), o.bytes, out)
                is DmsdpAction.SendDatagram -> {
                    val udp = keepAlive
                    if (udp != null && udp.state == State.OPEN) out.add(SessionAction.SendDatagram(udp.id, o.bytes))
                }
                DmsdpAction.OpenControlListener -> if (dmsdpControlListener == null) {
                    val opened = listen(SessionChannel.DMSDP_CONTROL, config.localIp, 0, null, out)
                    dmsdpControlListener = opened
                    dmsdpControl = opened.accept
                }
                is DmsdpAction.OpenKeepAliveSocket -> openKeepAlive(o.remotePort, out)
                is DmsdpAction.Connect -> diag(SessionLayer.DMSDP, "文件分享没有启用，忽略连接请求（${o.channel}）", out)
                is DmsdpAction.Close -> close(dmsdpEndpoint(o.channel), out)
                is DmsdpAction.OpenAudioListeners -> openAudioListeners(o.transport == AudioTransport.UDP, out)
                DmsdpAction.CloseAudioTransport -> closeAudio(out)
                DmsdpAction.OpenMicListener -> openMicListener(out)
                DmsdpAction.CloseMicTransport -> closeMic(out)
                is DmsdpEvent.EncryptionEnabled -> out.add(SessionEvent.DeviceSessionReady(o.keepAliveMode))
                is DmsdpEvent.KeepAliveTimeout -> finish(EndReason.KeepAliveTimeout(o.mode), out)
                is DmsdpEvent.DisplayPortAnnounced -> beginHiSight(o.port, out)
                is DmsdpEvent.CarDesktopRequested -> finish(EndReason.CarDesktopRequested(o.detail), out)
                is DmsdpEvent.MediaDataReceived -> {
                    diag(SessionLayer.DMSDP, o.toString(), out)
                    out.add(SessionEvent.MediaMetadataUpdated(o.info))
                }
                // 显示服务状态（命令 103）。action=202 对显示服务 = 手机暂停投屏（投屏界面点「车机」图标，或
                // 融合桌面磁贴点击——两者同报文，回桌面都是对的）。v22 曾把 202 当成结束连接（自杀），v25 撤掉；
                // 上游 0.2.5 给了正确形态：**不结束会话**，报 ProjectionPaused 让界面回车机桌面，连接、声音、
                // 方向盘按键照常，从桌面点 OpenHC 回到投屏。刚连上时手机会在服务开始后约 0.7 秒自发 202（实车），
                // app 层的 CarHome 用「设备会话就绪后 5 秒内的第一次暂停忽略」吃掉这个启动竞态。
                is DmsdpEvent.ServiceStatusChanged -> {
                    diag(SessionLayer.DMSDP, o.toString(), out)
                    if (o.action == SERVICE_ACTION_PAUSE && o.serviceType == SERVICE_TYPE_DISPLAY) {
                        out.add(SessionEvent.ProjectionPaused)
                    }
                }
                is DmsdpEvent -> diag(SessionLayer.DMSDP, o.toString(), out)
            }
        }
    }

    /** UDP 保活：绑本机地址的临时端口，connect 到设备控制连接的对端（手机）和它给的端口。只开一次。 */
    private fun openKeepAlive(remotePort: Int, out: Out) {
        if (keepAlive != null) return
        val phone = dmsdpControl?.remoteHost ?: return
        val udp = newEndpoint(SessionChannel.KEEPALIVE, Kind.UDP)
        udp.state = State.OPEN
        udp.remoteHost = phone
        keepAlive = udp
        out.add(SessionAction.OpenUdp(udp.id, SessionChannel.KEEPALIVE, config.localIp, phone, remotePort))
    }

    private fun dmsdpEndpoint(channel: DmsdpChannel): Endpoint? = when (channel) {
        DmsdpChannel.SESSION -> dmsdpConn
        DmsdpChannel.CONTROL -> dmsdpControl
        // 文件分享没有启用，不会有这两条连接。
        DmsdpChannel.FILE_CONTROL, DmsdpChannel.FILE_DATA -> null
        DmsdpChannel.AUDIO -> audioListener?.accept
        DmsdpChannel.MIC -> micListener?.accept
    }

    // ---------------- 音频数据会话 ----------------

    /**
     * dmsdp 要为音频数据会话开端口：先关掉上一次的，再在车机 IP 上开数据端口（偶数 P）。
     * SETUP 里写 `client_port=P--1`（第二个是 RTCP 口，写 -1 表示不建），手机先回 200 再连 P。
     */
    private fun openAudioListeners(udp: Boolean, out: Out) {
        closeAudio(out)
        audioAttempts = 0
        audioUdp = udp
        tryAudioPorts(out)
    }

    /** TCP 时开监听；UDP 时绑 UDP 端口收数据报（手机不连，直接往这里发）。 */
    private fun tryAudioPorts(out: Out) {
        audioAttempts++
        val port = AUDIO_PORT_MIN + 2 * config.random.nextInt((AUDIO_PORT_MAX - AUDIO_PORT_MIN) / 2 + 1)
        audioPort = port
        audioUdpHeard = false
        audioListener = if (audioUdp) bindUdp(SessionChannel.AUDIO, config.localIp, port, out) else listen(SessionChannel.AUDIO, config.localIp, port, null, out)
    }

    /** 监听开好了才交回 dmsdp 发 SETUP（先监听、后通告）。 */
    private fun onAudioListening(nowMillis: Long, out: Out) {
        val data = audioListener ?: return
        if (data.state != State.OPEN) return
        val layer = dmsdp ?: return
        handleDmsdp(call(SessionLayer.DMSDP) { layer.audioListenersReady(audioPort, nowMillis) }, out)
    }

    /**
     * 音频的监听或连接出错：端口被占（监听还没开好就失败）换一对端口重试；别的情况只关这一条。
     * 声音的问题都不结束会话。
     */
    private fun onAudioIoError(ep: Endpoint, message: String, nowMillis: Long, out: Out) {
        val layer = dmsdp ?: return
        val listenerFailed = ep === audioListener && ep.state == State.OPENING
        if (listenerFailed) {
            closeAudio(out)
            if (audioAttempts < AUDIO_PORT_ATTEMPTS) {
                diag(SessionLayer.SESSION, "音频端口开不了（$message），换一个端口再试", out)
                tryAudioPorts(out)
            } else {
                handleDmsdp(call(SessionLayer.DMSDP) { layer.audioListenersFailed(message) }, out)
            }
            return
        }
        // UDP 时数据端口本身就是「连接」：收到过数据报之后出错，按数据连接断开处理。
        val connection = ep === audioListener?.accept || (ep === audioListener && ep.kind == Kind.UDP && audioUdpHeard)
        val wasOpen = ep.state == State.OPEN
        close(ep, out)
        diag(SessionLayer.SESSION, "${ep.channel.label}出错（$message），只影响声音", out)
        if (connection && wasOpen) handleDmsdp(call(SessionLayer.DMSDP) { layer.onClosed(DmsdpChannel.AUDIO, nowMillis) }, out)
    }

    private fun closeAudio(out: Out) {
        for (ep in listOf(audioListener?.accept, audioListener)) close(ep, out)
        audioListener = null
        audioPort = 0
    }

    private fun isAudio(ep: Endpoint): Boolean = ep === audioListener || ep === audioListener?.accept

    // ---------------- 麦克风数据会话 ----------------

    /** 手机发来麦克风 SETUP：先关掉上一次的，再在车机 IP 上开一个监听（端口由系统分配），开好才回 200。 */
    private fun openMicListener(out: Out) {
        closeMic(out)
        micListener = listen(SessionChannel.MIC, config.localIp, 0, null, out)
    }

    /** 麦克风的监听或连接出错：只关这一条、告诉 dmsdp，不结束会话。 */
    private fun onMicIoError(ep: Endpoint, message: String, nowMillis: Long, out: Out) {
        val layer = dmsdp ?: return
        val listener = micListener
        if (ep === listener && ep.state == State.OPENING) {
            closeMic(out)
            handleDmsdp(call(SessionLayer.DMSDP) { layer.micListenerFailed(message) }, out)
            return
        }
        val wasOpenConnection = ep === listener?.accept && ep.state == State.OPEN
        close(ep, out)
        diag(SessionLayer.SESSION, "${ep.channel.label}出错（$message），只影响麦克风", out)
        if (wasOpenConnection) handleDmsdp(call(SessionLayer.DMSDP) { layer.onClosed(DmsdpChannel.MIC, nowMillis) }, out)
    }

    private fun closeMic(out: Out) {
        close(micListener?.accept, out)
        close(micListener, out)
        micListener = null
    }

    private fun isMic(ep: Endpoint): Boolean = ep === micListener || ep === micListener?.accept

    // ---------------- hisight ----------------

    /**
     * 手机通告投屏端口：去连业务 20 连接的对端（手机）的这个端口。同一端口重复通告忽略，
     * 换了端口按协议错误结束。
     */
    private fun beginHiSight(port: Int, out: Out) {
        if (hisight != null) {
            if (port == hisightPort) {
                diag(SessionLayer.SESSION, "手机重复通告同一个投屏端口，忽略", out)
            } else {
                finish(EndReason.ProtocolError(SessionLayer.DMSDP, "手机重复通告了不同的投屏端口"), out)
            }
            return
        }
        val key = checkNotNull(sessionKey) { "没有会话密钥" }
        val host = checkNotNull(dmsdpConn?.remoteHost) { "没有业务 20 连接" }
        hisightHost = host
        hisightPort = port
        hisight = layers.hisight(key, sinkAdapter)
        // dmsdp 与 hisight 都已拿到自己的副本，这里不再需要。
        wipeKey()
        out.add(SessionEvent.ProjectionRequested(port))
        hisightControl = connect(SessionChannel.HISIGHT_CONTROL, host, port, out)
    }

    private fun handleHiSight(outputs: List<HiSightOutput>, out: Out) {
        for (o in outputs) {
            if (ended) return
            when (o) {
                is HiSightOutput.Send -> write(hisightEndpoint(o.channel), o.bytes, out)
                is HiSightOutput.Connect -> when (o.channel) {
                    HiSightChannel.VIDEO -> if (video == null) video = connect(SessionChannel.VIDEO, hisightHost, o.port, out)
                    HiSightChannel.REMOTE_CONTROL -> if (remote == null) {
                        if (video?.state == State.OPEN) {
                            remote = connect(SessionChannel.REMOTE_CONTROL, hisightHost, o.port, out)
                        } else {
                            pendingRemotePort = o.port
                        }
                    }
                    HiSightChannel.CONTROL -> diag(SessionLayer.HISIGHT, "忽略投屏层对控制连接的连接请求", out)
                }
                is HiSightOutput.StreamNegotiated -> {
                    val params = VideoParameters(o.width, o.height, o.fps, o.touchInput, o.keyInput, o.textInput)
                    videoParameters = params
                    out.add(SessionEvent.VideoNegotiated(params))
                }
                HiSightOutput.PlayAccepted -> {
                    playAccepted = true
                    out.add(SessionEvent.PlayAccepted)
                }
                HiSightOutput.CloseAll -> {
                    close(hisightControl, out)
                    close(video, out)
                    close(remote, out)
                }
                is HiSightOutput.Note -> diag(SessionLayer.HISIGHT, o.text, out)
                is HiSightOutput.Ended -> {
                    hisightEnded = true
                    finish(EndReason.ProjectionEnded(o.reason), out)
                }
            }
        }
    }

    private fun feedVideo(data: ByteArray, offset: Int, length: Int, out: Out) {
        val hs = checkNotNull(hisight)
        val before = framesDelivered
        call(SessionLayer.HISIGHT) { hs.feedVideo(data, offset, length) }
        for (gap in videoGaps) {
            diag(SessionLayer.HISIGHT, "视频断档：$gap（手机发送端拥塞时会整批丢掉普通帧），从下一帧接上，解码等关键帧", out)
        }
        videoGaps.clear()
        if (before == 0L && framesDelivered > 0L) out.add(SessionEvent.VideoStarted(videoParameters))
    }

    /**
     * 投屏控制连接被手机关闭：PLAY 已被接受、视频还在推流时继续收视频，
     * 等视频连接结束再收尾；否则投屏随之结束。
     */
    private fun onHiSightControlClosed(out: Out) {
        if (playAccepted && video?.state == State.OPEN) {
            diag(SessionLayer.SESSION, "投屏控制连接被手机关闭，视频还在推流，继续接收到视频连接结束", out)
            return
        }
        handleHiSight(call(SessionLayer.HISIGHT) { checkNotNull(hisight).onControlClosed() }, out)
    }

    private fun hisightActive() = hisight != null && !hisightEnded

    private fun hisightEndpoint(channel: HiSightChannel): Endpoint? = when (channel) {
        HiSightChannel.CONTROL -> hisightControl
        HiSightChannel.VIDEO -> video
        HiSightChannel.REMOTE_CONTROL -> remote
    }

    // ---------------- 结束 ----------------

    private fun finish(reason: EndReason, out: Out) {
        if (ended) return
        ended = true
        val hs = hisight
        if (hs != null && !hisightEnded) {
            hisightEnded = true
            // 投屏层自己判断还能不能发 TEARDOWN（加密已协商、控制连接还在）；发不出去的写会被丢掉。
            // 出错前已经产生的说明（例如手机的能力选择原文）也在这里交出来，照样写进诊断日志。
            for (o in quietly { hs.shutdown() }) {
                when (o) {
                    is HiSightOutput.Send -> write(hisightEndpoint(o.channel), o.bytes, out)
                    is HiSightOutput.Note -> diag(SessionLayer.HISIGHT, o.text, out)
                    else -> Unit
                }
            }
        }
        val d = dmsdp
        if (d != null) {
            for (o in quietly { d.close() }) {
                if (o is DmsdpAction.Send) write(dmsdpEndpoint(o.channel), o.bytes, out)
            }
        }
        for (ep in listOf(
            hisightControl, video, remote,
            audioListener?.accept, audioListener,
            micListener?.accept, micListener,
            keepAlive, dmsdpControl, dmsdpControlListener, dmsdpConn, dmsdpListener,
            authConn, authListener,
            control, controlListener,
        )) {
            close(ep, out)
        }
        wipeKey()
        pendingAuthRequests.clear()
        pendingDmsdpRequests.clear()
        out.add(SessionEvent.Ended(reason))
    }

    /** 收尾时调用各层：已经在结束了，再出错也只能忽略。 */
    private inline fun <T> quietly(block: () -> List<T>): List<T> = try {
        block()
    } catch (e: Exception) {
        emptyList()
    }

    // ---------------- 工具 ----------------

    /**
     * 每个入口的统一外壳：会话已结束时什么都不做；某一层抛协议错误（或编排器自己的缺陷）时按顺序收尾，
     * 不把异常抛给调用方。
     */
    private inline fun session(block: (Out) -> Unit): List<SessionOutput> {
        if (ended) return emptyList()
        val out = Out()
        try {
            block(out)
        } catch (e: MalformedMessageException) {
            finish(EndReason.ProtocolError(activeLayer, e.message ?: "数据格式不对"), out)
        } catch (e: SinkFailure) {
            finish(EndReason.VideoSinkFailed(e.message ?: ""), out)
        } catch (e: Exception) {
            finish(EndReason.InternalError("$activeLayer：${describe(e)}"), out)
        }
        return out.list
    }

    /** 调某一层：记下当前层，出错时用来标明是哪一层。 */
    private inline fun <T> call(layer: SessionLayer, block: () -> T): T {
        activeLayer = layer
        return block()
    }

    private fun newEndpoint(channel: SessionChannel, kind: Kind): Endpoint {
        val ep = Endpoint(ids.next(), channel, kind)
        endpoints[ep.id] = ep
        return ep
    }

    private fun listen(channel: SessionChannel, host: String, port: Int, requireLocal: String?, out: Out): Endpoint {
        val listener = newEndpoint(channel, Kind.LISTENER)
        val accepted = newEndpoint(channel, Kind.TCP)
        listener.accept = accepted
        out.add(SessionAction.Listen(listener.id, channel, host, port, accepted.id, requireLocal))
        return listener
    }

    private fun bindUdp(channel: SessionChannel, host: String, port: Int, out: Out): Endpoint {
        val ep = newEndpoint(channel, Kind.UDP)
        out.add(SessionAction.BindUdp(ep.id, channel, host, port))
        return ep
    }

    private fun connect(channel: SessionChannel, host: String, port: Int, out: Out): Endpoint {
        val ep = newEndpoint(channel, Kind.TCP)
        ep.remoteHost = host
        out.add(SessionAction.Connect(ep.id, channel, host, port))
        return ep
    }

    /** 只往已建立、没关闭的连接写；连接已经没了的写直接丢掉（例如对端先关了连接之后的 TEARDOWN）。 */
    private fun write(ep: Endpoint?, bytes: ByteArray, out: Out) {
        if (ep != null && ep.state == State.OPEN) out.add(SessionAction.Write(ep.id, bytes))
    }

    private fun close(ep: Endpoint?, out: Out) {
        if (ep == null || ep.state == State.CLOSED) return
        ep.state = State.CLOSED
        out.add(SessionAction.Close(ep.id))
    }

    private fun diag(layer: SessionLayer, message: String, out: Out) {
        out.add(SessionEvent.Diagnostic(layer, message))
    }

    private fun wipeKey() {
        sessionKey?.fill(0)
        sessionKey = null
    }

    override fun toString() = "ReceiverSession(已结束=$ended, 认证=$authSucceeded, 投屏=${hisight != null}, 帧=$framesDelivered)"

    private companion object {
        /** 设备服务状态通知（命令 103）的动作：202 是服务暂停（投屏界面上点「车机」图标时，手机对显示服务发这个）。 */
        const val SERVICE_ACTION_PAUSE = 202

        /** 设备服务类型：显示（投屏画面）。 */
        const val SERVICE_TYPE_DISPLAY = 8L
        /** 音频端口从这个范围里挑偶数（本机的选择：RTP 习惯用偶数口，不是协议规定）。 */
        const val AUDIO_PORT_MIN = 20_000
        const val AUDIO_PORT_MAX = 59_998
        const val AUDIO_PORT_ATTEMPTS = 8

        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
