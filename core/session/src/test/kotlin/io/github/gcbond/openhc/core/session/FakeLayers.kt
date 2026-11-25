package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.core.common.identity.NearbyKeyMaterial
import io.github.gcbond.openhc.core.dmsdp.DmsdpAction
import io.github.gcbond.openhc.core.dmsdp.DmsdpChannel
import io.github.gcbond.openhc.core.dmsdp.DmsdpOutput
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.hisight.AccessUnit
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightChannel
import io.github.gcbond.openhc.core.hisight.HiSightEndReason
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.hisight.HiSightOutput
import java.util.TimeZone

/** 测试用的合成值：一眼能看出来是假的。 */
internal object Synthetic {
    const val LOCAL_IP = "192.0.2.10"
    const val PHONE_IP = "192.0.2.20"
    const val PORT = 32000
    const val BLUETOOTH = "0A:1B:2C:3D:4E:5F"
    const val ENVELOPE_VERSION = "X_0@00000000000000"
    val KEY = ByteArray(16) { it.toByte() }

    val IDENTITY = IdentityAssets(
        host = HostIdentity("ABCDEF", "01", "OpenHC 测试", "测试车机"),
        nearbyKeys = NearbyKeyMaterial(ByteArray(16) { 1 }, ByteArray(16) { 2 }, ByteArray(16) { 3 }),
        authService = AuthServiceIdentity("org.example.auth", "EXAMPLE"),
        authEnvelopeVersion = ENVELOPE_VERSION,
    )

    fun config(
        localIp: String = LOCAL_IP,
        port: Int = PORT,
        display: DisplaySettings = DisplaySettings(1280, 720, 30, dpi = 160, density = 1.0),
        controlBindHost: String = ReceiverConfig.WILDCARD,
        controlLocalFilter: Boolean = true,
        wallMillis: Long = 1_767_312_000_000L,
        audio: AudioSettings? = null,
    ) = ReceiverConfig(
        identity = IDENTITY,
        bluetoothAddress = BLUETOOTH,
        localIp = localIp,
        controlPort = port,
        display = display,
        authEnvelopeVersion = ENVELOPE_VERSION,
        trustStore = InMemoryTrustStore(),
        controlBindHost = controlBindHost,
        controlLocalFilter = controlLocalFilter,
        wallClock = { wallMillis },
        timeZone = TimeZone.getTimeZone("UTC"),
        audio = audio,
    )

    fun authRequest() = NearbyOutput.SocketRequest(19, 6, "hicarAuth", null)
    fun dmsdpRequest() = NearbyOutput.SocketRequest(20, 6, "DMSDP", null)

    /** [AccessUnit] 的构造函数只在投屏层内部可见；编排层只做透传，测试里用反射拿一个空实例当标记。 */
    fun accessUnit(): AccessUnit = AccessUnit::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance()
    }
}

internal fun String.bytes() = toByteArray(Charsets.UTF_8)
internal fun ByteArray.text(offset: Int = 0, length: Int = size - offset) = String(this, offset, length, Charsets.UTF_8)

/** 按调用顺序记下各层被调了什么，方便断言交接顺序。 */
internal class FakeLayers : Layers {
    val calls = ArrayList<String>()
    val nearby = FakeNearby(calls)
    val auth = FakeAuth(calls)
    val dmsdp = FakeDmsdp(calls)
    val hisight = FakeHiSight(calls)

    var dmsdpKey: ByteArray? = null
    var dmsdpPhoneIp: String? = null
    var hisightKey: ByteArray? = null

    override fun nearby(): NearbyLayer {
        calls += "nearby.create"
        return nearby
    }

    override fun auth(): AuthLayer {
        calls += "auth.create"
        return auth
    }

    override fun dmsdp(sessionKey: ByteArray, phoneIp: String, phoneVersion: String?): DmsdpLayer {
        calls += "dmsdp.create($phoneIp)"
        // 与真实的层一样自己复制一份。
        dmsdpKey = sessionKey.copyOf()
        dmsdpPhoneIp = phoneIp
        return dmsdp
    }

    override fun hisight(sessionKey: ByteArray, sink: AccessUnitSink): HiSightLayer {
        calls += "hisight.create"
        hisightKey = sessionKey.copyOf()
        hisight.sink = sink
        return hisight
    }
}

/** 假的 nearby：手机发来的字节当成文本命令，交给 [onFeed] 决定输出。 */
internal class FakeNearby(private val calls: MutableList<String>) : NearbyLayer {
    override var nextTickAtMillis: Long? = null
    var onFeed: (String) -> List<NearbyOutput> = { emptyList() }
    var onTickHandler: (Long) -> List<NearbyOutput> = { emptyList() }

    override fun start(nowMillis: Long): List<NearbyOutput> {
        calls += "nearby.start"
        return listOf(NearbyOutput.Send("connect-id".bytes()))
    }

    override fun feed(data: ByteArray, offset: Int, length: Int): List<NearbyOutput> {
        val text = data.text(offset, length)
        calls += "nearby.feed($text)"
        return onFeed(text)
    }

    override fun onTick(nowMillis: Long): List<NearbyOutput> = onTickHandler(nowMillis)

    override fun accept(request: NearbyOutput.SocketRequest, port: Int): List<NearbyOutput> {
        calls += "nearby.accept(${request.businessId},$port)"
        return listOf(NearbyOutput.Send("accept:${request.businessId}:$port".bytes()))
    }

    override fun reject(request: NearbyOutput.SocketRequest): List<NearbyOutput> {
        calls += "nearby.reject(${request.businessId})"
        return listOf(NearbyOutput.Send("reject:${request.businessId}".bytes()))
    }
}

/** 假的认证：命令 `hello` 回握手（首次配对、连接码 123456），`done` 认证成功（密钥 00..0f）；`-trusted` 结尾的是免码回连。 */
internal class FakeAuth(private val calls: MutableList<String>) : AuthLayer {
    var onFeed: (String) -> List<HiCarAuthAction> = { text ->
        when (text) {
            "hello" -> listOf(
                HiCarAuthAction.Send("hello-reply".bytes()),
                HiCarAuthAction.HandshakeAnswered("peer", AuthMode.PIN_PAIRING, "123456"),
            )
            "done" -> listOf(
                HiCarAuthAction.Send("done-reply".bytes()),
                HiCarAuthAction.Succeeded(Synthetic.KEY.copyOf(), "peer", AuthMode.PIN_PAIRING),
            )
            // 再次连接（免码）：握手不带连接码。
            "hello-trusted" -> listOf(
                HiCarAuthAction.Send("hello-reply".bytes()),
                HiCarAuthAction.HandshakeAnswered("peer", AuthMode.TRUSTED_RECONNECT, null),
            )
            "done-trusted" -> listOf(
                HiCarAuthAction.Send("done-reply".bytes()),
                HiCarAuthAction.Succeeded(Synthetic.KEY.copyOf(), "peer", AuthMode.TRUSTED_RECONNECT),
            )
            else -> emptyList()
        }
    }
    var lastSessionKey: ByteArray? = null

    override fun feed(data: ByteArray, offset: Int, length: Int): List<HiCarAuthAction> {
        val text = data.text(offset, length)
        calls += "auth.feed($text)"
        val out = onFeed(text)
        out.filterIsInstance<HiCarAuthAction.Succeeded>().forEach { lastSessionKey = it.sessionKey }
        return out
    }

    override fun onConnectionClosed(): List<HiCarAuthAction> {
        calls += "auth.closed"
        return listOf(HiCarAuthAction.Failed(AuthFailure.ConnectionClosed(false)))
    }
}

/**
 * 假的设备会话：业务 20 上的 `nego` 要求开控制监听；控制连接上 `crypto` 选 UDP 保活（手机端口 50000），
 * `display` 通告投屏端口 7000。
 */
internal class FakeDmsdp(private val calls: MutableList<String>) : DmsdpLayer {
    override var nextTickAtMillis: Long? = null
    var onFeed: (DmsdpChannel, String) -> List<DmsdpOutput> = { channel, text ->
        when {
            channel == DmsdpChannel.SESSION && text == "nego" -> listOf(DmsdpAction.OpenControlListener)
            channel == DmsdpChannel.CONTROL && text == "crypto" -> listOf(
                DmsdpAction.Send(DmsdpChannel.CONTROL, "crypto-ok".bytes()),
                DmsdpAction.OpenKeepAliveSocket(50000),
                io.github.gcbond.openhc.core.dmsdp.DmsdpEvent.EncryptionEnabled(io.github.gcbond.openhc.core.dmsdp.KeepAliveMode.UDP),
            )
            channel == DmsdpChannel.CONTROL && text == "display" ->
                listOf(io.github.gcbond.openhc.core.dmsdp.DmsdpEvent.DisplayPortAnnounced(7000))
            else -> emptyList()
        }
    }
    var onTickHandler: (Long) -> List<DmsdpOutput> = { emptyList() }
    var onClosedHandler: (DmsdpChannel) -> List<DmsdpOutput> = { emptyList() }
    var onDatagramHandler: (String) -> List<DmsdpOutput> = { emptyList() }

    override fun feed(channel: DmsdpChannel, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> {
        val text = data.text(offset, length)
        calls += "dmsdp.feed($channel,$text)"
        return onFeed(channel, text)
    }

    override fun controlListenerReady(port: Int): List<DmsdpOutput> {
        calls += "dmsdp.controlListenerReady($port)"
        return listOf(DmsdpAction.Send(DmsdpChannel.SESSION, "control-port:$port".bytes()))
    }

    override fun onConnected(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput> {
        calls += "dmsdp.onConnected($channel)"
        return if (channel == DmsdpChannel.CONTROL) listOf(DmsdpAction.Send(DmsdpChannel.CONTROL, "OPTIONS".bytes())) else emptyList()
    }

    override fun onClosed(channel: DmsdpChannel, nowMillis: Long): List<DmsdpOutput> {
        calls += "dmsdp.onClosed($channel)"
        return onClosedHandler(channel)
    }

    override fun onDatagram(data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<DmsdpOutput> {
        val text = data.text(offset, length)
        calls += "dmsdp.onDatagram($text)"
        return onDatagramHandler(text)
    }

    override fun onTick(nowMillis: Long): List<DmsdpOutput> = onTickHandler(nowMillis)

    override fun close(): List<DmsdpOutput> {
        calls += "dmsdp.close"
        return emptyList()
    }

    /** 音频端口开好：默认回一条 `setup:端口`。 */
    var onAudioReady: (Int) -> List<DmsdpOutput> = { port -> listOf(DmsdpAction.Send(DmsdpChannel.CONTROL, "setup:$port".bytes())) }

    override fun audioListenersReady(port: Int, nowMillis: Long): List<DmsdpOutput> {
        calls += "dmsdp.audioListenersReady($port)"
        return onAudioReady(port)
    }

    override fun audioListenersFailed(reason: String): List<DmsdpOutput> {
        calls += "dmsdp.audioListenersFailed"
        return emptyList()
    }

    override fun sendAudioFocus(change: CarAudioFocus): List<DmsdpOutput> {
        calls += "dmsdp.sendAudioFocus(${change.type})"
        return listOf(DmsdpAction.Send(DmsdpChannel.CONTROL, "focus:${change.type}".bytes()))
    }
}

/**
 * 假的投屏层：`setup` 给出视频端口 7001、回控端口 7002；视频和回控都连上才发 PLAY；
 * `play-ok` 表示手机接受 PLAY；视频连接上收到的每段数据交出一帧 [unit]。
 */
internal class FakeHiSight(private val calls: MutableList<String>) : HiSightLayer {
    lateinit var sink: AccessUnitSink
    val unit: AccessUnit = Synthetic.accessUnit()
    private var videoConnected = false
    private var remoteConnected = false
    var ended = false
    var onFeedControl: (String) -> List<HiSightOutput> = { text ->
        when (text) {
            "setup" -> listOf(
                HiSightOutput.Send(HiSightChannel.CONTROL, "setup-ok".bytes()),
                HiSightOutput.StreamNegotiated(1280, 720, 30, touchInput = false, keyInput = false, textInput = false),
                HiSightOutput.Connect(HiSightChannel.VIDEO, 7001),
                HiSightOutput.Connect(HiSightChannel.REMOTE_CONTROL, 7002),
            )
            "play-ok" -> listOf(HiSightOutput.PlayAccepted)
            "teardown" -> end(HiSightEndReason.PEER_TEARDOWN, listOf(HiSightOutput.Send(HiSightChannel.CONTROL, "ok".bytes())))
            else -> emptyList()
        }
    }

    private fun end(reason: HiSightEndReason, before: List<HiSightOutput> = emptyList()): List<HiSightOutput> {
        if (ended) return emptyList()
        ended = true
        return before + listOf(
            HiSightOutput.Send(HiSightChannel.CONTROL, "TEARDOWN".bytes()),
            HiSightOutput.CloseAll,
            HiSightOutput.Ended(reason),
        )
    }

    override fun start(): List<HiSightOutput> {
        calls += "hisight.start"
        return listOf(HiSightOutput.Send(HiSightChannel.CONTROL, "ANNOUNCE".bytes()))
    }

    override fun feedControl(data: ByteArray, offset: Int, length: Int, wallClockMillis: Long): List<HiSightOutput> {
        val text = data.text(offset, length)
        calls += "hisight.feedControl($text)"
        return onFeedControl(text)
    }

    override fun onControlClosed(): List<HiSightOutput> {
        calls += "hisight.onControlClosed"
        if (ended) return emptyList()
        ended = true
        return listOf(HiSightOutput.CloseAll, HiSightOutput.Ended(HiSightEndReason.CONTROL_CLOSED))
    }

    override fun onVideoConnected(): List<HiSightOutput> {
        calls += "hisight.onVideoConnected"
        videoConnected = true
        return maybePlay()
    }

    override fun onRemoteControlConnected(): List<HiSightOutput> {
        calls += "hisight.onRemoteControlConnected"
        remoteConnected = true
        return maybePlay()
    }

    private fun maybePlay() =
        if (videoConnected && remoteConnected) listOf(HiSightOutput.Send(HiSightChannel.CONTROL, "PLAY".bytes())) else emptyList()

    override fun feedVideo(data: ByteArray, offset: Int, length: Int) {
        calls += "hisight.feedVideo($length)"
        if (!ended) sink.onAccessUnit(unit)
    }

    override fun onVideoClosed(): List<HiSightOutput> {
        calls += "hisight.onVideoClosed"
        return end(HiSightEndReason.VIDEO_CLOSED)
    }

    override fun feedRemoteControl(data: ByteArray, offset: Int, length: Int) {
        calls += "hisight.feedRemoteControl($length)"
        throw io.github.gcbond.openhc.core.common.MalformedMessageException("回控连接收到未定义的入站数据")
    }

    override fun onRemoteControlClosed(): List<HiSightOutput> {
        calls += "hisight.onRemoteControlClosed"
        return end(HiSightEndReason.REMOTE_CONTROL_CLOSED)
    }

    override fun sendInput(input: HiSightInput): List<HiSightOutput> {
        calls += "hisight.sendInput"
        return listOf(HiSightOutput.Send(HiSightChannel.REMOTE_CONTROL, "input".bytes()))
    }

    override fun shutdown(): List<HiSightOutput> {
        calls += "hisight.shutdown"
        return end(HiSightEndReason.LOCAL_SHUTDOWN)
    }
}
