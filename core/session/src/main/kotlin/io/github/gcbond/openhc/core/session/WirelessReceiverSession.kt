package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.auth.HiCarAuthAction
import io.github.gcbond.openhc.core.auth.PinCodes
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.hisight.AccessUnitSink
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.core.nearby.DataChannelRefusal
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.nearby.WifiConnectionInfo
import io.github.gcbond.openhc.core.nearby.WifiMode
import java.util.UUID

/**
 * 一次无线投屏连接的会话编排。和 [ReceiverSession] 一样不做 I/O：调用方把 I/O 结果交进来，
 * 它返回一串按顺序执行的 [WirelessOutput]。蓝牙、WiFi 资源和 TCP 资源共用一套 id，结果按 id 交回同名方法。
 *
 * 前半段在蓝牙上，由本类驱动（交接顺序详见 core/session/README.md「无线连接」）：
 * 1. 开蓝牙控制监听（RFCOMM，服务 UUID FE35）→ [WirelessEvent.PairingReady]：显示二维码和连接码。
 * 2. 手机连上 → 控制握手（车机是应答方、不报 TLV7）。控制监听这时先不关（见第 3 步）。
 * 3. 手机请求认证子通道（消息层，业务 19，协议 0）→ 开 RFCOMM 监听、拿到通道号再回给手机；手机连上后关掉这个监听
 *    和控制监听（控制监听要是先关了，它的通道号空出来会分给认证子通道，同一对设备同一个通道号连不了第二条），
 *    认证与有线相同（应答里 `usb_connection` 为 false、不下发连接码），得到会话密钥。认证成功这时才开热点：
 *    等扫码、等回连、没通过认证的连接都不开，车机自己连着的 WiFi 不受影响；手机约 1 秒后才发协商请求，热点和它同时进行。
 * 4. 手机发数据通道协商请求 → 回车机当组主和热点的频率（热点还没开好就等开好再回）→ 手机确认。
 * 5. 在车机的热点地址上开随机端口的 TCP 监听。
 * 6. 监听开好 → 向手机要 MAC → 手机回 MAC → 发连接串（热点格式）。
 *
 * 后半段复用 [ReceiverSession]：第 5 步建一个内嵌实例（控制通道是通道 3、认证已完成），手机连上 WiFi 上的 TCP 后，
 * 车机在这条控制连接上先当一次握手发起方，之后的设备会话和投屏与有线完全相同。WiFi 控制连接接上之后，
 * 手机会关掉两条蓝牙连接，这是正常的。
 *
 * 车机主动回连（[startReconnect]，和 [start] 二选一）只换前 3 步：车机连手机的蓝牙控制服务（不开监听、不出二维码），
 * 车机当握手发起方；握手完成后车机请手机开认证子通道，手机同意后车机按它给的通道号连过去，手机在上面先发认证握手。
 * 认证之后（第 4 步起）与手机连上来时完全相同（2026-11-22 实车：认证后手机发的就是同一条数据通道协商）。
 *
 * 结束时（任何原因）：先让内嵌的有线编排器按它的顺序收尾，再关蓝牙认证子通道、蓝牙控制通道，关掉热点，
 * 最后输出 [WirelessEvent.Ended]。之后所有方法都返回空列表。
 *
 * 不是线程安全的：所有方法在同一个线程上串行调用；视频帧也在这个线程上（[onData] 里）同步交给 [videoSink]。
 */
class WirelessReceiverSession internal constructor(
    private val config: WirelessReceiverConfig,
    private val videoSink: AccessUnitSink,
    private val layers: WirelessLayers,
) {
    /**
     * @param audio WiFi 阶段音频数据会话的出口（[WirelessReceiverConfig.audio] 打开时才用得到），规则同
     *   [ReceiverSession] 的同名参数。
     */
    constructor(
        config: WirelessReceiverConfig,
        videoSink: AccessUnitSink,
        audio: AudioStreamListener = AudioStreamListener.NONE,
    ) : this(config, videoSink, ProductionWirelessLayers(config, audio))

    private enum class State { OPENING, OPEN, CLOSED }

    private class Endpoint(val id: Int, val channel: WirelessChannel, val listener: Boolean) {
        var state = State.OPENING
        var port = 0

        /** 监听接受的那条连接。 */
        var accept: Endpoint? = null
    }

    private class Out {
        val list = ArrayList<WirelessOutput>(4)

        fun add(output: WirelessOutput) {
            list += output
        }

        fun common(output: SessionOutput) {
            list += WirelessOutput.Common(output)
        }
    }

    /** 主动回连的目标：手机的蓝牙地址（规范格式）和业务标签。 */
    private class ReconnectTarget(val address: String, val tag: ByteArray)

    private val ids = IdSource()
    private val endpoints = HashMap<Int, Endpoint>()

    private var controlListener: Endpoint? = null
    private var control: Endpoint? = null
    private var authListener: Endpoint? = null
    private var authConn: Endpoint? = null
    private var network: Endpoint? = null

    private var nearby: BluetoothNearbyLayer? = null
    private var auth: AuthLayer? = null
    private var wired: ReceiverSession? = null

    private var started = false
    private var ended = false
    private var activeStep = WirelessStep.BLUETOOTH

    /** 本次会话的连接码：二维码里、屏幕上显示的就是它，手机首次配对时认证用它。 */
    val pinCode: String = config.pin ?: PinCodes.random(config.random)

    private var authSucceeded = false

    /** 认证给的 16 字节会话密钥：交给内嵌的有线编排器（它自己复制）后清零。 */
    private var sessionKey: ByteArray? = null

    /** 车机主动回连的目标手机（[startReconnect]）；手机连上来的连接为 null。 */
    private var reconnect: ReconnectTarget? = null

    /** 主动回连：等手机应答（或等手机上的确认）的截止时间。 */
    private var reconnectDeadline: Long? = null

    /** 主动回连：手机暂时拒绝过、正在等手机上的确认。 */
    private var awaitingPhone = false

    /** 等认证子通道监听开好的请求。 */
    private val pendingAuthRequests = ArrayList<BluetoothOutput.SocketRequest>()

    /** 等热点开好再回的协商请求。 */
    private var pendingDataChannel: BluetoothOutput.DataChannelRequest? = null

    /** 回给手机的频率；0 表示还没回过协商应答。 */
    private var negotiatedFrequency = 0
    private var negotiationConfirmed = false
    private var networkInfo: WifiNetwork? = null

    /** WiFi 控制监听的实际端口；0 表示还没开好。 */
    private var wifiPort = 0
    private var macRequested = false
    private var connectionInfoSent = false

    /** 手机已经连上 WiFi 上的控制端口：此后蓝牙连接被关掉是正常的。 */
    private var phoneOnWifi = false

    private var authAcceptDeadline: Long? = null
    private var authDeadline: Long? = null
    private var networkDeadline: Long? = null
    private var joinDeadline: Long? = null

    /** 是否已经输出过 [WirelessEvent.Ended]。 */
    val isEnded: Boolean get() = ended

    /** 下次希望 [onTick] 被调用的时间（单调时钟毫秒）；没有定时任务时为 null。 */
    val nextTickAtMillis: Long?
        get() {
            if (ended) return null
            val handshake = nearby?.takeIf { control?.state == State.OPEN }?.nextTickAtMillis
            return listOfNotNull(
                handshake, reconnectDeadline, authAcceptDeadline, authDeadline, networkDeadline, joinDeadline, wired?.nextTickAtMillis,
            ).minOrNull()
        }

    /** WiFi 阶段内嵌的有线编排器已经交给视频回调的帧数。 */
    val videoFrames: Long get() = wired?.videoFrames ?: 0L

    /** 开始：开蓝牙控制监听。只能调用一次。热点等手机认证通过才开（见 [handleAuth]）。 */
    fun start(nowMillis: Long): List<WirelessOutput> {
        check(!started) { "start 只能调用一次" }
        started = true
        return session(nowMillis) { out ->
            val listener = listenRfcomm(WirelessChannel.BLUETOOTH_CONTROL, NearbySession.BLUETOOTH_SERVICE_UUID, false, out)
            controlListener = listener
            control = listener.accept
        }
    }

    /**
     * 车机主动回连：连手机 [phoneAddress] 的蓝牙控制服务，不开监听、不出二维码。只能调用一次（和 [start] 二选一）。
     * [phoneName] 是请手机开认证子通道时的业务标签（手机的设备名；实车只验证过和手机蓝牙名一致的情况）。
     * 认证要输码（手机不认这台车了）就以 [WirelessEndReason.ReconnectFailed]（要重新配对）结束，因为这时没有二维码可显示。
     */
    fun startReconnect(phoneAddress: String, phoneName: String, nowMillis: Long): List<WirelessOutput> {
        check(!started) { "start 只能调用一次" }
        require(phoneName.isNotBlank()) { "手机名不能为空" }
        val address = BluetoothAddress.format(BluetoothAddress.parse(phoneAddress))
        started = true
        reconnect = ReconnectTarget(address, phoneName.toByteArray(Charsets.UTF_8))
        return session(nowMillis) { out ->
            val ep = newEndpoint(WirelessChannel.BLUETOOTH_CONTROL, listener = false)
            control = ep
            out.add(
                WirelessAction.ConnectRfcomm(ep.id, WirelessChannel.BLUETOOTH_CONTROL, address, NearbySession.BLUETOOTH_SERVICE_UUID, null),
            )
        }
    }

    /** 监听已开好：TCP 监听交实际端口；RFCOMM 监听交通道号（拿不到交 0）。 */
    fun onListening(id: Int, port: Int, nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val ep = endpoints[id]
        if (ep == null) {
            routeWired(nowMillis, out) { it.onListening(id, port, nowMillis) }
            return@session
        }
        if (!ep.listener || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        ep.port = port
        when (ep) {
            controlListener -> out.add(WirelessEvent.PairingReady(config.qrCode(pinCode), pinCode))
            authListener -> onAuthListening(port, nowMillis, out)
        }
    }

    /** 监听接受了一条连接：TCP 交对端 IPv4，RFCOMM 交对端蓝牙地址。 */
    fun onAccepted(id: Int, remote: String, nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val ep = endpoints[id]
        if (ep == null) {
            routeWired(nowMillis, out) { it.onAccepted(id, remote, nowMillis) }
            return@session
        }
        if (ep.listener || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        when (ep) {
            control -> {
                out.add(WirelessEvent.PhoneBluetoothConnected(remote))
                // 控制通道的监听先不关：它一关，它占的 RFCOMM 通道号就空出来，接着开的认证子通道监听会分到同一个号。
                // 手机和车机之间控制通道正占着这个号的连接，同一对设备同一个通道号不能再开第二条，手机连认证子通道必失败。
                // 等认证子通道连上再一起关（监听本身只接受一条连接，开着也不会接第二部手机）。
                val layer = layers.bluetoothNearby(initiator = false)
                nearby = layer
                handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.start(nowMillis) }, nowMillis, out)
            }
            authConn -> {
                // 手机连上认证子通道后，关掉它的监听，也关掉一直留着的控制通道监听（同时撤掉服务记录）。
                close(authListener, out)
                close(controlListener, out)
                authAcceptDeadline = null
                authDeadline = nowMillis + config.timeouts.authMillis
                auth = layers.auth(pinCode)
            }
        }
    }

    /** 主动发起的连接已连上：车机主动回连时的蓝牙控制通道、认证子通道，以及 WiFi 阶段的投屏连接（TCP）。 */
    fun onConnected(id: Int, nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val ep = endpoints[id]
        if (ep == null) {
            routeWired(nowMillis, out) { it.onConnected(id, nowMillis) }
            return@session
        }
        val target = reconnect
        if (target == null || ep.listener || ep.state != State.OPENING) return@session
        ep.state = State.OPEN
        when (ep) {
            control -> {
                out.add(WirelessEvent.PhoneBluetoothConnected(target.address))
                val layer = layers.bluetoothNearby(initiator = true)
                nearby = layer
                handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.start(nowMillis) }, nowMillis, out)
            }
            authConn -> {
                // 手机在认证子通道上先发认证握手，车机只应答。
                authAcceptDeadline = null
                authDeadline = nowMillis + config.timeouts.authMillis
                auth = layers.auth(pinCode)
            }
        }
    }

    /** 连接 [id]（TCP 或 RFCOMM）收到的字节。调用返回后 [data] 可以被调用方复用。 */
    fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<WirelessOutput> =
        session(nowMillis) { out ->
            val ep = endpoints[id]
            if (ep == null) {
                routeWired(nowMillis, out) { it.onData(id, data, offset, length, nowMillis) }
                return@session
            }
            if (ep.listener || ep.state != State.OPEN) return@session
            when (ep) {
                control -> {
                    val layer = checkNotNull(nearby)
                    handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.feed(data, offset, length) }, nowMillis, out)
                }
                authConn -> {
                    val layer = checkNotNull(auth)
                    handleAuth(call(WirelessStep.AUTH) { layer.feed(data, offset, length) }, nowMillis, out)
                }
            }
        }

    /** UDP 数据报（只有 WiFi 阶段的保活）。 */
    fun onDatagram(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long): List<WirelessOutput> =
        session(nowMillis) { out ->
            routeWired(nowMillis, out) { it.onDatagram(id, data, offset, length, nowMillis) }
        }

    /** 连接 [id] 被对端关闭；对 [WirelessAction.StartWifiNetwork] 的 id 表示网络没了。 */
    fun onClosed(id: Int, nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val ep = endpoints[id]
        if (ep == null) {
            routeWired(nowMillis, out) { it.onClosed(id, nowMillis) }
            return@session
        }
        if (ep.listener || ep.state != State.OPEN) return@session
        when (ep) {
            control -> {
                close(ep, out)
                when {
                    phoneOnWifi -> diag(SessionLayer.SESSION, "蓝牙控制通道被手机关闭（WiFi 已接上，会话继续）", out)
                    reconnect != null && !authSucceeded -> finish(
                        WirelessEndReason.ReconnectFailed(if (awaitingPhone) "手机上没确认（关了连接）" else "手机关了蓝牙控制通道"),
                        nowMillis, out,
                    )
                    else -> finish(WirelessEndReason.Failed(WirelessStep.BLUETOOTH, "WiFi 数据通道接上之前手机关闭了蓝牙控制通道"), nowMillis, out)
                }
            }
            authConn -> {
                close(ep, out)
                if (authSucceeded) {
                    diag(SessionLayer.SESSION, "认证子通道被手机关闭（认证已完成）", out)
                } else {
                    handleAuth(call(WirelessStep.AUTH) { checkNotNull(auth).onConnectionClosed() }, nowMillis, out)
                }
            }
            network -> {
                close(ep, out)
                finish(WirelessEndReason.Failed(WirelessStep.WIFI_NETWORK, "车机的 WiFi 网络没了"), nowMillis, out)
            }
        }
    }

    /** [id] 的监听、连接、收发或建网出错。 */
    fun onIoError(id: Int, message: String, nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val ep = endpoints[id]
        if (ep == null) {
            routeWired(nowMillis, out) { it.onIoError(id, message, nowMillis) }
            return@session
        }
        if (ep.state == State.CLOSED) return@session
        val settled = (ep === control && phoneOnWifi) || (ep === authConn && authSucceeded)
        when {
            // 手机关蓝牙连接时，系统常报成读出错而不是读到结尾：用完了的连接出错当作被关闭。
            settled -> {
                close(ep, out)
                diag(SessionLayer.SESSION, "${ep.channel.label}出错，已经用不到了，会话继续（$message）", out)
            }
            ep === control && reconnect != null && !authSucceeded -> {
                val what = if (ep.state == State.OPENING) "连不上手机" else "蓝牙控制通道出错"
                finish(WirelessEndReason.ReconnectFailed("$what：$message"), nowMillis, out)
            }
            ep === network -> {
                val what = if (ep.state == State.OPENING) "建网失败" else "网络出错"
                finish(WirelessEndReason.Failed(WirelessStep.WIFI_NETWORK, "$what：$message"), nowMillis, out)
            }
            ep.channel == WirelessChannel.BLUETOOTH_AUTH ->
                finish(WirelessEndReason.Failed(WirelessStep.AUTH, "${ep.channel.label}出错：$message"), nowMillis, out)
            else -> finish(WirelessEndReason.Failed(WirelessStep.BLUETOOTH, "${ep.channel.label}出错：$message"), nowMillis, out)
        }
    }

    /** [WirelessAction.StartWifiNetwork] 开好了热点，[network] 是实际信息。 */
    fun onWifiNetworkReady(id: Int, network: WifiNetwork, nowMillis: Long): List<WirelessOutput> =
        session(nowMillis) { out ->
            val ep = endpoints[id] ?: return@session
            if (ep !== this.network || ep.state != State.OPENING) return@session
            ep.state = State.OPEN
            networkDeadline = null
            val problem = checkNetwork(network)
            if (problem != null) {
                finish(WirelessEndReason.Failed(WirelessStep.WIFI_NETWORK, "网络信息不合法：$problem"), nowMillis, out)
                return@session
            }
            networkInfo = network
            out.add(WirelessEvent.WifiNetworkReady(network.frequencyMhz))
            pendingDataChannel?.let {
                pendingDataChannel = null
                answerWithHotspot(it, nowMillis, out)
            }
        }

    /** 推进定时任务。可以早调、晚调。 */
    fun onTick(nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out ->
        val n = nearby
        if (n != null && control?.state == State.OPEN) {
            handleBluetooth(call(WirelessStep.BLUETOOTH) { n.onTick(nowMillis) }, nowMillis, out)
        }
        if (due(reconnectDeadline, nowMillis)) {
            finish(WirelessEndReason.ReconnectFailed(if (awaitingPhone) "手机上一直没确认" else "手机一直没回应"), nowMillis, out)
        }
        if (due(authAcceptDeadline, nowMillis)) {
            val detail = if (authListener == null) {
                "车机 ${config.timeouts.reconnectAuthConnectMillis} 毫秒内没连上手机的认证子通道"
            } else {
                "手机 ${config.timeouts.authChannelAcceptMillis} 毫秒内没连上认证子通道"
            }
            close(authListener, out)
            finish(WirelessEndReason.Failed(WirelessStep.AUTH, detail), nowMillis, out)
        }
        if (due(authDeadline, nowMillis)) finish(WirelessEndReason.Failed(WirelessStep.AUTH, "认证超时"), nowMillis, out)
        if (due(networkDeadline, nowMillis)) finish(WirelessEndReason.Failed(WirelessStep.WIFI_NETWORK, "建网超时"), nowMillis, out)
        if (due(joinDeadline, nowMillis)) {
            finish(WirelessEndReason.Failed(WirelessStep.PHONE_JOIN, "手机没在时限内连上车机 WiFi 上的端口"), nowMillis, out)
        }
        if (!ended) routeWired(nowMillis, out) { it.onTick(nowMillis) }
    }

    /** 本机结束会话：按顺序收尾、关闭全部资源。 */
    fun stop(nowMillis: Long): List<WirelessOutput> = session(nowMillis) { out -> finish(WirelessEndReason.LocalStop, nowMillis, out) }

    /** 发一次触摸或按键，规则同 [ReceiverSession.sendInput]；投屏还没开始时返回空列表。 */
    fun sendInput(input: HiSightInput): List<WirelessOutput> {
        if (ended) return emptyList()
        val w = wired ?: return emptyList()
        return w.sendInput(input).map { WirelessOutput.Common(it) }
    }

    /** 把车机的音频焦点变化告诉手机，规则同 [ReceiverSession.sendAudioFocus]；WiFi 阶段之前返回空列表。 */
    fun sendAudioFocus(change: CarAudioFocus): List<WirelessOutput> {
        if (ended) return emptyList()
        val w = wired ?: return emptyList()
        return w.sendAudioFocus(change).map { WirelessOutput.Common(it) }
    }

    // ---------------- 蓝牙控制通道 ----------------

    private fun handleBluetooth(outputs: List<BluetoothOutput>, nowMillis: Long, out: Out) {
        for (o in outputs) {
            if (ended) return
            when (o) {
                is BluetoothOutput.Send -> write(control, o.bytes, out)
                is BluetoothOutput.HandshakeCompleted -> {
                    out.add(WirelessEvent.BluetoothHandshakeCompleted(o.encrypted))
                    if (reconnect != null) requestAuthChannel(nowMillis, out)
                }
                BluetoothOutput.HandshakeTimedOut -> finish(
                    WirelessEndReason.Failed(WirelessStep.BLUETOOTH, "连上后 ${NearbySession.HANDSHAKE_TIMEOUT_MILLIS / 1000} 秒内握手没完成"),
                    nowMillis, out,
                )
                is BluetoothOutput.SocketRequest -> onSocketRequest(o, nowMillis, out)
                is BluetoothOutput.DataChannelRequest -> onDataChannelRequest(o, nowMillis, out)
                is BluetoothOutput.DataChannelResult -> onDataChannelResult(o, nowMillis, out)
                is BluetoothOutput.PeerMac -> onPeerMac(o.mac, nowMillis, out)
                is BluetoothOutput.AuthChannelReady -> onAuthChannelReady(o, nowMillis, out)
                is BluetoothOutput.AuthChannelRejected -> onReconnectDeclined(o.status, nowMillis, out)
                BluetoothOutput.PeerBusy ->
                    finish(WirelessEndReason.Failed(WirelessStep.PHONE_JOIN, "手机回了忙"), nowMillis, out)
                is BluetoothOutput.Note -> diag(SessionLayer.NEARBY, o.text, out)
            }
        }
    }

    /**
     * 蓝牙控制通道上只开认证子通道（业务 19、协议 0）：第一次请求时开 RFCOMM 监听，拿到通道号再回；
     * 监听还开着时的重复请求回同一个通道号；手机已经连上之后的请求、其他业务一律拒绝。
     */
    private fun onSocketRequest(request: BluetoothOutput.SocketRequest, nowMillis: Long, out: Out) {
        if (request.businessId != NearbySession.BUSINESS_AUTH || !request.rfcomm) {
            rejectSubchannel(request, "蓝牙控制通道上只开认证子通道", nowMillis, out)
            return
        }
        if (reconnect != null) {
            // 主动回连时手机自己来请求（例如手机上确认之后）：车机还没按手机给的通道号去连，就照手机连上来时的做法接受。
            if (authConn != null && authListener == null) {
                rejectSubchannel(request, "车机已经在按手机给的通道号连认证子通道", nowMillis, out)
                return
            }
            reconnectDeadline = null
        }
        val listener = authListener
        when {
            listener == null -> {
                pendingAuthRequests += request
                val uuid = checkNotNull(nearby).rfcommUuid(request)
                val opened = listenRfcomm(WirelessChannel.BLUETOOTH_AUTH, uuid, true, out)
                authListener = opened
                authConn = opened.accept
            }
            listener.state == State.OPENING -> pendingAuthRequests += request
            listener.state == State.OPEN -> answerSubchannel(request, listener.port, nowMillis, out)
            else -> rejectSubchannel(request, "认证子通道已经连上", nowMillis, out)
        }
    }

    private fun onAuthListening(port: Int, nowMillis: Long, out: Out) {
        val waiting = ArrayList(pendingAuthRequests)
        pendingAuthRequests.clear()
        if (port !in 1..NearbySession.MAX_RFCOMM_CHANNEL) {
            // 通道号回不给手机，手机就连不上认证子通道：拒绝后结束。
            for (request in waiting) rejectSubchannel(request, "拿不到 RFCOMM 通道号", nowMillis, out)
            finish(WirelessEndReason.Failed(WirelessStep.AUTH, "认证子通道拿不到 RFCOMM 通道号"), nowMillis, out)
            return
        }
        authAcceptDeadline = nowMillis + config.timeouts.authChannelAcceptMillis
        for (request in waiting) {
            if (ended) return
            answerSubchannel(request, port, nowMillis, out)
        }
    }

    private fun answerSubchannel(request: BluetoothOutput.SocketRequest, channel: Int, nowMillis: Long, out: Out) {
        val layer = checkNotNull(nearby)
        handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.acceptRfcomm(request, channel) }, nowMillis, out)
    }

    private fun rejectSubchannel(request: BluetoothOutput.SocketRequest, why: String, nowMillis: Long, out: Out) {
        diag(SessionLayer.SESSION, "拒绝子通道请求（$request）：$why", out)
        val layer = checkNotNull(nearby)
        handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.reject(request) }, nowMillis, out)
    }

    // ---------------- 车机主动回连 ----------------

    private fun requestAuthChannel(nowMillis: Long, out: Out) {
        val target = checkNotNull(reconnect)
        val layer = checkNotNull(nearby)
        reconnectDeadline = nowMillis + config.timeouts.reconnectAnswerMillis
        handleBluetooth(call(WirelessStep.BLUETOOTH) { layer.requestAuthChannel(target.tag) }, nowMillis, out)
    }

    /** 手机同意开认证子通道：按它给的通道号连过去（平台拿不到直连接口时按它给的 UUID 查 SDP）。手机只等约 10 秒。 */
    private fun onAuthChannelReady(ready: BluetoothOutput.AuthChannelReady, nowMillis: Long, out: Out) {
        val target = reconnect
        if (target == null || authConn != null) {
            diag(SessionLayer.SESSION, "认证子通道已经有了，不理手机的应答（$ready）", out)
            return
        }
        reconnectDeadline = null
        val ep = newEndpoint(WirelessChannel.BLUETOOTH_AUTH, listener = false)
        authConn = ep
        authAcceptDeadline = nowMillis + config.timeouts.reconnectAuthConnectMillis
        out.add(
            WirelessAction.ConnectRfcomm(
                ep.id, WirelessChannel.BLUETOOTH_AUTH, target.address, ready.uuid ?: NearbySession.DEFAULT_SUBCHANNEL_UUID, ready.port,
            ),
        )
    }

    /**
     * 手机暂时没同意开认证子通道。被拒之后怎么办只在这里定。
     *
     * 实车（2026-11-22）：手机开着热点时会弹「连接要关热点」的确认框，约 10 秒后先回拒绝，控制通道却一直留着，
     * 用户点了取消才关；原厂车机拨通后也是留着连接等 120 秒。所以这里不结束：控制通道留着等手机上的确认，
     * 手机自己再来请求开认证子通道时照常接受（[onSocketRequest]），手机关掉连接或到时没等到就结束。
     * 用户在手机上点了连接之后手机具体怎么走（自己发请求，还是等车机再请求），待实车验证。
     */
    private fun onReconnectDeclined(status: Int, nowMillis: Long, out: Out) {
        if (reconnect == null || authConn != null) {
            diag(SessionLayer.SESSION, "认证子通道已经有了，不理手机的拒绝（状态 $status）", out)
            return
        }
        awaitingPhone = true
        reconnectDeadline = nowMillis + config.timeouts.reconnectConfirmMillis
        out.add(WirelessEvent.ReconnectAwaitingPhone(status))
    }

    // ---------------- 认证 ----------------

    private fun handleAuth(actions: List<HiCarAuthAction>, nowMillis: Long, out: Out) {
        for (a in actions) {
            if (ended) return
            when (a) {
                is HiCarAuthAction.Send -> write(authConn, a.bytes, out)
                // 连接码早已和二维码一起显示，这里不再单独给。主动回连时没有二维码：要输码就结束，请用户重新扫码。
                is HiCarAuthAction.HandshakeAnswered -> {
                    if (reconnect != null && a.mode == AuthMode.PIN_PAIRING) {
                        finish(WirelessEndReason.ReconnectFailed("手机要求输码配对", needsPairing = true), nowMillis, out)
                        return
                    }
                    out.common(SessionEvent.AuthStarted(a.mode))
                }
                is HiCarAuthAction.Succeeded -> {
                    sessionKey = a.sessionKey.copyOf()
                    a.sessionKey.fill(0)
                    authSucceeded = true
                    authDeadline = null
                    out.common(SessionEvent.Authenticated(a.mode))
                    // 认证通过才开热点。开热点会让单射频车机断开自己的 WiFi，等扫码、等回连、
                    // 没通过认证的连接都不该付这个代价；手机约 1 秒后才发协商请求，到时还没开好就等开好再回。
                    if (network == null) startNetwork(nowMillis, out)
                }
                is HiCarAuthAction.Failed -> finish(WirelessEndReason.AuthFailed(a.failure), nowMillis, out)
                is HiCarAuthAction.Note -> diag(SessionLayer.AUTH, a.text, out)
            }
        }
    }

    // ---------------- 数据通道协商与组网 ----------------

    /**
     * 协商请求：认证完成之前回忙；要的不是 WiFi 回没有可用数据通道；手机重发请求时回同一个频率。
     * 报给手机的是平台交回的热点频率（读不到真实信道的平台交回推测值），热点还没开好就等开好再回。
     */
    private fun onDataChannelRequest(request: BluetoothOutput.DataChannelRequest, nowMillis: Long, out: Out) {
        if (request.capability.unparsedFrom != null && negotiatedFrequency == 0) {
            diag(SessionLayer.SESSION, "手机报的 WiFi 能力没有全解开，只用解开的部分：${request.capability}", out)
        }
        when {
            !authSucceeded -> refuse(request, DataChannelRefusal.BUSY, "认证完成之前收到数据通道协商请求", nowMillis, out)
            request.dataChannel != NearbySession.CHANNEL_WIFI ->
                refuse(request, DataChannelRefusal.NO_CHANNEL, "手机要的数据通道 ${request.dataChannel} 不是 WiFi", nowMillis, out)
            negotiatedFrequency != 0 -> {
                diag(SessionLayer.SESSION, "手机重发了数据通道协商请求，回同一个频率", out)
                acceptDataChannel(request, negotiatedFrequency, nowMillis, out)
            }
            networkInfo != null -> answerWithHotspot(request, nowMillis, out)
            else -> {
                pendingDataChannel = request
                diag(SessionLayer.SESSION, "热点还没开好，协商请求等热点就绪再回", out)
            }
        }
    }

    private fun answerWithHotspot(request: BluetoothOutput.DataChannelRequest, nowMillis: Long, out: Out) {
        val frequency = checkNotNull(networkInfo).frequencyMhz
        if (!request.capability.canUse(frequency)) {
            refuse(request, DataChannelRefusal.NO_CHANNEL, "手机用不了热点所在的频率", nowMillis, out)
            finish(WirelessEndReason.Failed(WirelessStep.NEGOTIATION, "热点在 $frequency MHz，手机报的能力用不了"), nowMillis, out)
            return
        }
        acceptDataChannel(request, frequency, nowMillis, out)
    }

    private fun acceptDataChannel(request: BluetoothOutput.DataChannelRequest, frequency: Int, nowMillis: Long, out: Out) {
        negotiatedFrequency = frequency
        val layer = checkNotNull(nearby)
        handleBluetooth(call(WirelessStep.NEGOTIATION) { layer.acceptDataChannel(request, frequency) }, nowMillis, out)
    }

    private fun refuse(
        request: BluetoothOutput.DataChannelRequest, refusal: DataChannelRefusal, why: String, nowMillis: Long, out: Out,
    ) {
        diag(SessionLayer.SESSION, "拒绝数据通道协商（${refusal.name}）：$why", out)
        val layer = checkNotNull(nearby)
        handleBluetooth(call(WirelessStep.NEGOTIATION) { layer.rejectDataChannel(request, refusal) }, nowMillis, out)
    }

    /** 协商结果：成败只看结果值；手机确认的频率和车机给的不同只出诊断，连接串照样按车机给的频率发。 */
    private fun onDataChannelResult(result: BluetoothOutput.DataChannelResult, nowMillis: Long, out: Out) {
        when {
            negotiatedFrequency == 0 -> diag(SessionLayer.SESSION, "还没回协商应答就收到协商结果，忽略", out)
            !result.accepted -> {
                val why = if (result.result == DataChannelRefusal.NO_CHANNEL.code) "手机：没有可用的数据通道" else "手机忙（结果 ${result.result}）"
                finish(WirelessEndReason.Failed(WirelessStep.NEGOTIATION, why), nowMillis, out)
            }
            negotiationConfirmed -> diag(SessionLayer.SESSION, "手机重复确认了协商结果，忽略", out)
            else -> {
                val confirmed = result.frequencyMhz
                if (confirmed != null && confirmed != negotiatedFrequency) {
                    diag(SessionLayer.SESSION, "手机确认的频率 $confirmed MHz 和车机给的 $negotiatedFrequency MHz 不同", out)
                }
                negotiationConfirmed = true
                out.add(WirelessEvent.DataChannelNegotiated(negotiatedFrequency))
                // 只有热点开好了才会回协商应答：这时直接在热点上开控制监听。
                startWifiPhase(nowMillis, out)
            }
        }
    }

    private fun startNetwork(nowMillis: Long, out: Out) {
        val ep = newEndpoint(WirelessChannel.WIFI_NETWORK, listener = false)
        network = ep
        networkDeadline = nowMillis + config.timeouts.networkStartMillis
        out.add(WirelessAction.StartWifiNetwork(ep.id))
    }

    /** 网络信息拼得出合法的连接串才算合法；返回问题说明，合法返回 null。 */
    private fun checkNetwork(net: WifiNetwork): String? = try {
        // 端口这时还不知道，填一个合法值只为检查其余字段。
        connectionInfo(net, port = 1)
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "格式不对"
    } catch (e: IllegalStateException) {
        e.message ?: "格式不对"
    }

    private fun connectionInfo(net: WifiNetwork, port: Int) = WifiConnectionInfo(
        mode = net.mode,
        phoneIp = WifiConnectionInfo.pickPhoneAddress(net.localIp),
        ssid = net.ssid,
        bssid = net.bssid,
        passphrase = net.passphrase,
        frequencyMhz = net.frequencyMhz,
        port = port,
        carIp = net.localIp,
    )

    /** 网络就绪、协商已确认：建内嵌的有线编排器（通道 3、认证已完成），开 WiFi 上的控制监听。 */
    private fun startWifiPhase(nowMillis: Long, out: Out) {
        val net = checkNotNull(networkInfo)
        val key = checkNotNull(sessionKey) { "认证成功之前不该进 WiFi 阶段" }
        val phaseConfig = config.wifiPhaseConfig(net.localIp)
        wired = ReceiverSession(phaseConfig, videoSink, layers.wifiPhase(phaseConfig), ControlLink.wifi(net.localIp, key), ids)
        // 内嵌的编排器已经拿到自己的副本。
        wipeKey()
        routeWired(nowMillis, out) { it.start(nowMillis) }
    }

    /** WiFi 上的控制监听开好了：向手机要 MAC，开始等手机连进来。 */
    private fun onWifiControlListening(port: Int, nowMillis: Long, out: Out) {
        if (wifiPort != 0) return
        if (port !in 1..65535) {
            finish(WirelessEndReason.Failed(WirelessStep.PHONE_JOIN, "WiFi 控制监听没有拿到端口"), nowMillis, out)
            return
        }
        wifiPort = port
        macRequested = true
        joinDeadline = nowMillis + config.timeouts.phoneJoinMillis
        val layer = checkNotNull(nearby)
        handleBluetooth(call(WirelessStep.PHONE_JOIN) { layer.requestPeerMac() }, nowMillis, out)
    }

    /** 手机回了 MAC：发连接串（只发一次）。手机入网以连上 TCP 端口为准，MAC 只交给平台记日志。 */
    private fun onPeerMac(mac: String, nowMillis: Long, out: Out) {
        when {
            !macRequested -> diag(SessionLayer.SESSION, "还没要就收到了手机 MAC，忽略", out)
            connectionInfoSent -> diag(SessionLayer.SESSION, "手机重复报了 MAC，忽略", out)
            else -> {
                val info = connectionInfo(checkNotNull(networkInfo), wifiPort)
                connectionInfoSent = true
                val layer = checkNotNull(nearby)
                handleBluetooth(call(WirelessStep.PHONE_JOIN) { layer.sendConnectionInfo(info) }, nowMillis, out)
                if (!ended) out.add(WirelessEvent.ConnectionInfoSent(wifiPort, mac, info.hotspotChannel))
            }
        }
    }

    // ---------------- WiFi 阶段（内嵌的有线编排器） ----------------

    /**
     * 调内嵌的有线编排器，把它的输出转出去：控制监听开好（[SessionEvent.ControlListening]）不往外交，改为向手机要 MAC；
     * 手机连上控制端口时停止等待计时；它结束时（[SessionEvent.Ended]）接着收尾蓝牙和 WiFi。
     */
    private inline fun routeWired(nowMillis: Long, out: Out, block: (ReceiverSession) -> List<SessionOutput>) {
        val w = wired ?: return
        for (o in block(w)) {
            if (ended) return
            when (o) {
                is SessionEvent.ControlListening -> onWifiControlListening(o.port, nowMillis, out)
                is SessionEvent.PhoneConnected -> {
                    phoneOnWifi = true
                    joinDeadline = null
                    out.common(o)
                }
                is SessionEvent.Ended -> finish(WirelessEndReason.Wired(o.reason), nowMillis, out)
                else -> out.common(o)
            }
        }
    }

    // ---------------- 结束 ----------------

    private fun finish(reason: WirelessEndReason, nowMillis: Long, out: Out) {
        if (ended) return
        ended = true
        val w = wired
        if (w != null && !w.isEnded) {
            // 内嵌的编排器按它自己的顺序收尾（TEARDOWN、由上到下关闭）；它的 Ended 由这里的 Ended 代替。
            for (o in quietly { w.stop(nowMillis) }) if (o !is SessionEvent.Ended) out.common(o)
        }
        for (ep in listOf(authConn, authListener, control, controlListener, network)) close(ep, out)
        wipeKey()
        pendingAuthRequests.clear()
        pendingDataChannel = null
        reconnectDeadline = null
        authAcceptDeadline = null
        authDeadline = null
        networkDeadline = null
        joinDeadline = null
        out.add(WirelessEvent.Ended(reason))
    }

    private inline fun quietly(block: () -> List<SessionOutput>): List<SessionOutput> = try {
        block()
    } catch (e: Exception) {
        emptyList()
    }

    // ---------------- 工具 ----------------

    /** 每个入口的统一外壳：已结束什么都不做；某一层抛错时按顺序收尾，不把异常抛给调用方。 */
    private inline fun session(nowMillis: Long, block: (Out) -> Unit): List<WirelessOutput> {
        if (ended) return emptyList()
        val out = Out()
        try {
            block(out)
        } catch (e: MalformedMessageException) {
            finish(WirelessEndReason.Failed(activeStep, "数据格式不对：${e.message}"), nowMillis, out)
        } catch (e: Exception) {
            finish(WirelessEndReason.InternalError("${activeStep.label}：${describe(e)}"), nowMillis, out)
        }
        return out.list
    }

    private inline fun <T> call(step: WirelessStep, block: () -> T): T {
        activeStep = step
        return block()
    }

    private fun due(deadline: Long?, nowMillis: Long) = !ended && deadline != null && nowMillis >= deadline

    private fun newEndpoint(channel: WirelessChannel, listener: Boolean): Endpoint {
        val ep = Endpoint(ids.next(), channel, listener)
        endpoints[ep.id] = ep
        return ep
    }

    private fun listenRfcomm(channel: WirelessChannel, uuid: UUID, needChannelNumber: Boolean, out: Out): Endpoint {
        val listener = newEndpoint(channel, listener = true)
        val accepted = newEndpoint(channel, listener = false)
        listener.accept = accepted
        out.add(WirelessAction.ListenRfcomm(listener.id, channel, uuid, accepted.id, needChannelNumber))
        return listener
    }

    /** 只往已建立、没关闭的 RFCOMM 连接写；连接已经没了的写直接丢掉。 */
    private fun write(ep: Endpoint?, bytes: ByteArray, out: Out) {
        if (ep != null && ep.state == State.OPEN) out.add(WirelessAction.RfcommWrite(ep.id, bytes))
    }

    private fun close(ep: Endpoint?, out: Out) {
        if (ep == null || ep.state == State.CLOSED) return
        ep.state = State.CLOSED
        out.add(if (ep.channel == WirelessChannel.WIFI_NETWORK) WirelessAction.StopWifiNetwork(ep.id) else WirelessAction.RfcommClose(ep.id))
    }

    private fun diag(layer: SessionLayer, message: String, out: Out) {
        out.common(SessionEvent.Diagnostic(layer, message))
    }

    private fun wipeKey() {
        sessionKey?.fill(0)
        sessionKey = null
    }

    override fun toString() =
        "WirelessReceiverSession(已结束=$ended, 认证=$authSucceeded, 频率=$negotiatedFrequency, WiFi 已接上=$phoneOnWifi)"

    private companion object {
        fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { "：$it" } ?: "")
    }
}
