package io.github.gcbond.openhc.core.nearby

import io.github.gcbond.openhc.core.common.ByteReader
import io.github.gcbond.openhc.core.common.ByteWriter
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.core.common.identity.DeviceId
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.common.identity.NearbyKeyMaterial
import java.security.SecureRandom
import java.util.UUID

/**
 * nearby 层的入口：车机一端（服务端）在一条控制通道上的协议状态机，以及 carInfo、deviceId。
 *
 * 不做任何 I/O：收到的字节用 [feed] 喂进来，要发出去的帧和解析出的事件都在返回值里，
 * 按列表顺序处理；时间由调用方传入（单调时钟毫秒）。不是线程安全的，同一会话的调用要串行。
 *
 * 一次有线连接的用法：
 * 1. USB：用 [carInfo] 生成 91 字节，按 `CAR_INFO_*` 常量发一次厂商控制传输（主机到设备）。
 * 2. 手机连上 carInfo 里的 IP:端口后，为这条 TCP 新建一个会话，调 [start]，把返回的帧发出去。
 * 3. 每读到数据就调 [feed]；[nextTickAtMillis] 不为 null 时，到点调 [onTick]（握手超时）。
 * 4. 握手完成后手机会发 [NearbyEvent.SocketRequested]：
 *    - 业务 [BUSINESS_AUTH]：开一个 TCP 监听，用 [acceptSocket] 把端口回给手机。手机连上这个端口后，
 *      上面跑的是配对认证（交给 core:auth），**认证消息不经过 nearby 控制通道**。
 *    - 业务 [BUSINESS_DMSDP]：认证完成之后再 [acceptSocket]，在那之前先把事件留着；
 *      手机连上后的数据交给 core:dmsdp。
 *    - 不认识的业务用 [rejectSocket] 拒绝。
 *
 * 无线连接（[link] 不是 [NearbyLink.USB]）时的差别：
 * - 蓝牙控制通道（[NearbyLink.BLUETOOTH]）：不报 TLV7；手机在消息层请求为认证开 RFCOMM 子通道（业务 19、协议 0），
 *   调用方用 [rfcommServiceUuid] 开好 RFCOMM 监听后用 [acceptRfcommSocket] 回通道号；之后手机在资源层发数据通道协商
 *   （[NearbyEvent.DataChannelRequested]），车机用 [acceptDataChannel] 回频率，手机回结果（[NearbyEvent.DataChannelResult]）；
 *   车机建好网络、开好 TCP 监听后用 [requestWifiPeerAddress] 要手机 MAC（[NearbyEvent.WifiPeerAddress]），
 *   再用 [sendWifiConnectionInfo] 发连接串。
 * - WiFi 控制通道（[NearbyLink.WIFI]）：[start] 发完 connectId 后，车机再发一帧明文的版本、deviceId、加密请求
 *   （车机在这条通道上先当一次发起方）；不报 TLV7；之后与有线相同，手机请求业务 20。
 * - 车机主动回连（蓝牙控制通道、[initiator] 为 true，车机是 RFCOMM 客户端）：[start] 和 WiFi 通道一样先当发起方；
 *   握手完成后车机用 [requestAuthChannel] 请手机为认证新开 RFCOMM 子通道（命令 3），手机回命令 4
 *   （[NearbyEvent.AuthChannelReady] 带通道号，或 [NearbyEvent.AuthChannelRejected]），车机按通道号连过去；
 *   认证之后的数据通道协商、要 MAC、发连接串与手机连上来时完全相同。
 *
 * @param bluetoothAddress 车机蓝牙地址 `AA:BB:CC:DD:EE:FF`；取不到传 null。
 *   取不到或是 `02:00:00:00:00:00` 时 deviceId 按退化规则算，见 [deviceId]。
 *   [carInfo]、[deviceId] 和这里必须传同一个地址，三处的 deviceId 才同源。
 * @param random 生成 connectId 和每帧加密种子；测试里可以传固定序列的实现。只会调用它的 `nextBytes`。
 * @param link 控制通道的承载，默认有线，见 [NearbyLink]。
 * @param initiator 车机是不是这条控制通道的发起方（握手里先发版本、deviceId、加密请求）。WiFi 通道总是；
 *   蓝牙通道只有车机主动回连时是；有线不会是。
 */
class NearbySession(
    host: HostIdentity,
    keys: NearbyKeyMaterial,
    bluetoothAddress: String?,
    private val random: SecureRandom,
    val link: NearbyLink = NearbyLink.USB,
    val initiator: Boolean = link == NearbyLink.WIFI,
) {
    init {
        require(link != NearbyLink.USB || !initiator) { "有线控制通道上车机只当应答方" }
        require(link != NearbyLink.WIFI || initiator) { "WiFi 控制通道上车机总是发起方" }
    }

    private val identity = NearbyIdentity(host, bluetoothAddress)
    private val cipher = NearbyFrameCipher(keys)
    private val decoder = NearbyFrameDecoder()

    /** 握手里报给对端的本机 deviceId 文本（tag 4）和蓝牙地址文本（tag 7 / 8）。 */
    private val ownDeviceIdText = identity.deviceIdHex.toByteArray(Charsets.US_ASCII)
    private val ownAddressText = identity.bluetoothAddressText.toByteArray(Charsets.US_ASCII)

    private var started = false
    private var closed = false
    private var handshakeComplete = false
    private var deadline: Long? = null

    /** 本端发出的帧是否加密，由对端的加密开关（tag 5 / 6）决定。 */
    private var encrypted = false
    private var seen = 0
    private var peerVersion: Long? = null
    private var peerSummary: ByteArray? = null
    private var peerDeviceId: ByteArray? = null
    private var peerBluetooth: String? = null
    private var sentBluetoothRequest = false

    /** 车机发出的认证子通道请求的业务标签（[requestAuthChannel]）；还没发为 null。 */
    private var authRequestTag: ByteArray? = null
    private var authAnswered = false

    /** 本机 deviceId 的 64 位大写十六进制文本，与 [Companion.deviceId] 相同。 */
    val deviceId: String get() = identity.deviceIdHex

    /** 下次希望 [onTick] 被调用的时间（单调时钟毫秒）；没有待到期的定时时为 null。 */
    val nextTickAtMillis: Long? get() = deadline

    /**
     * 控制连接建立后立刻调用：发明文的 connectId（tag 15），并开始握手计时。
     * 车机是应答方时（有线、手机连上来的蓝牙通道）不主动发版本、摘要、加密请求，等手机发。
     * 车机是发起方时（[initiator]：WiFi 通道、车机主动回连的蓝牙通道）紧接着再发一帧明文
     * `TLV1{02} TLV3(deviceId) TLV5{01}`，之后照常应答手机的请求。
     */
    fun start(nowMillis: Long): List<NearbyAction> {
        check(!started) { "start 只能调用一次" }
        started = true
        deadline = nowMillis + HANDSHAKE_TIMEOUT_MILLIS
        val out = ArrayList<NearbyAction>(2)
        val payload = encodeTlv(ChannelManagement.TAG_CONNECT_ID, ChannelManagement.connectIdValue(nextConnectId()))
        send(ChannelManagement.OUTER_TYPE, payload, out)
        if (initiator) {
            val request = ByteWriter()
                .tlv(ChannelManagement.TAG_VERSION_REQUEST, LOCAL_VERSION_VALUE)
                .tlv(ChannelManagement.TAG_SUMMARY_REQUEST, ownDeviceIdText)
                .tlv(ChannelManagement.TAG_ENCRYPTION_REQUEST, ENCRYPTION_ON)
                .toByteArray()
            send(ChannelManagement.OUTER_TYPE, request, out)
        }
        return out
    }

    /** 喂入从控制通道读到的字节（可以是半帧，也可以是好几帧）。 */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<NearbyAction> {
        check(started) { "先调用 start" }
        if (closed) return emptyList()
        decoder.feed(data, offset, length)
        val out = ArrayList<NearbyAction>()
        while (true) {
            val pending = decoder.buffered
            val frame = try {
                decoder.next()
            } catch (e: MalformedMessageException) {
                out.add(NearbyEvent.InputDiscarded(e.message ?: "拆帧失败", pending))
                null
            } ?: break
            handleFrame(frame, out)
        }
        return out
    }

    /** 到 [nextTickAtMillis] 时调用；握手超时会返回 [NearbyEvent.HandshakeTimedOut]。 */
    fun onTick(nowMillis: Long): List<NearbyAction> {
        val due = deadline ?: return emptyList()
        if (nowMillis < due) return emptyList()
        deadline = null
        closed = true
        decoder.clear()
        return listOf(NearbyEvent.HandshakeTimedOut)
    }

    /**
     * 接受建 socket 请求：调用方已经在 [port] 上开好 TCP 监听。
     * 应答走请求所在的那一层，按当前加密状态发出。
     */
    fun acceptSocket(request: NearbyEvent.SocketRequested, port: Int): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        require(port in 1..65535) { "端口必须在 1～65535 之间，实际 $port" }
        return respond(request.outerType, SocketMessages.STATUS_ACCEPTED, port, request.channel,
            request.businessId, request.rawTag, SocketMessages.PROTOCOL_TCP)
    }

    /** 拒绝建 socket 请求：回状态 1、端口 0、协议 0。 */
    fun rejectSocket(request: NearbyEvent.SocketRequested): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        return respond(request.outerType, SocketMessages.STATUS_REJECTED, 0, request.channel,
            request.businessId, request.rawTag, 0)
    }

    /**
     * 接受蓝牙控制通道上的 RFCOMM 子通道请求：调用方已经用 [rfcommServiceUuid] 开好 RFCOMM 监听（不加密），
     * [rfcommChannel] 是系统分配给它的通道号。应答：状态 0、端口位填通道号，通道、业务号、标签、协议照抄请求，
     * 末尾带 16 字节 UUID。手机按通道号直接连过来（不查 SDP）。
     */
    fun acceptRfcommSocket(request: NearbyEvent.SocketRequested, rfcommChannel: Int): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        check(link == NearbyLink.BLUETOOTH) { "只有蓝牙控制通道上才开 RFCOMM 子通道" }
        require(rfcommChannel in 1..MAX_RFCOMM_CHANNEL) { "RFCOMM 通道号必须在 1～$MAX_RFCOMM_CHANNEL 之间，实际 $rfcommChannel" }
        return respond(request.outerType, SocketMessages.STATUS_ACCEPTED, rfcommChannel, request.channel,
            request.businessId, request.rawTag, request.protocol, uuidBytes(rfcommServiceUuid(request)))
    }

    /**
     * 车机主动回连时，握手完成后调用：在消息层发命令 3，请手机为认证新开一条 RFCOMM 子通道
     * （通道 = 蓝牙通道、业务 [BUSINESS_AUTH]、协议 0、不带 UUID）。[tag] 是业务标签（手机的设备名，UTF-8），
     * 手机在命令 4 里原样回送，车机按业务号和它认出应答。只能调用一次。
     */
    fun requestAuthChannel(tag: ByteArray): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        check(link == NearbyLink.BLUETOOTH && initiator) { "只有车机主动连上的蓝牙控制通道才请求认证子通道" }
        check(handshakeComplete) { "控制通道握手还没完成" }
        check(authRequestTag == null) { "认证子通道只请求一次" }
        require(tag.isNotEmpty()) { "业务标签不能为空" }
        authRequestTag = tag.copyOf()
        val request = SocketMessages.Request(NearbyLink.BLUETOOTH.channel, BUSINESS_AUTH, tag.copyOf(), 0, null)
        val out = ArrayList<NearbyAction>(1)
        send(SocketMessages.OUTER_TYPE_MESSAGE, encodeTlv(SocketMessages.CMD_CREATE_REQUEST, SocketMessages.encodeRequest(request)), out)
        return out
    }

    /**
     * 接受数据通道协商（回资源层命令 2）：车机当组主，[frequencyMhz] 是车机马上要建（或已经开着）的网络的真实频率，
     * 手机会照抄它去找网。
     */
    fun acceptDataChannel(request: NearbyEvent.DataChannelRequested, frequencyMhz: Int): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        require(WifiChannels.channelOf(frequencyMhz) != null) { "不是 2.4 GHz 或 5 GHz 的信道频率: $frequencyMhz" }
        val out = ArrayList<NearbyAction>(1)
        send(SocketMessages.OUTER_TYPE_RESOURCE,
            DataChannelMessages.acceptPayload(request.dataChannel, ROLE_GROUP_OWNER, frequencyMhz), out)
        return out
    }

    /** 拒绝数据通道协商（回资源层命令 2，结果 2 或 255）。 */
    fun rejectDataChannel(request: NearbyEvent.DataChannelRequested, refusal: DataChannelRefusal): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        val out = ArrayList<NearbyAction>(1)
        send(SocketMessages.OUTER_TYPE_RESOURCE, DataChannelMessages.refusePayload(request.dataChannel, refusal), out)
        return out
    }

    /** 向手机要它的 WiFi MAC（WiFi 组网层 tag 1）。车机要在网络建好、TCP 监听开好之后再发。 */
    fun requestWifiPeerAddress(): List<NearbyAction> =
        wifiLink(NearbyLink.BLUETOOTH, encodeTlv(WifiLinkMessages.TAG_GET_PEER_MAC))

    /** 发连接串（WiFi 组网层 tag 3）：手机据此加入车机的网络、连 TCP。 */
    fun sendWifiConnectionInfo(info: WifiConnectionInfo): List<NearbyAction> =
        wifiLink(NearbyLink.BLUETOOTH, encodeTlv(WifiLinkMessages.TAG_CONNECTION_INFO, info.encode().toByteArray(Charsets.UTF_8)))

    /** 请求断开数据通道（WiFi 组网层 tag 5，在 WiFi 控制通道上发），对端会回 tag 6。 */
    fun requestWifiDisconnect(): List<NearbyAction> =
        wifiLink(NearbyLink.WIFI, encodeTlv(WifiLinkMessages.TAG_DISCONNECT))

    private fun wifiLink(required: NearbyLink, payload: ByteArray): List<NearbyAction> {
        check(!closed) { "控制通道已作废" }
        check(link == required) { "这条消息只在 ${required.name} 控制通道上发" }
        check(handshakeComplete) { "控制通道握手还没完成" }
        val out = ArrayList<NearbyAction>(1)
        send(WifiLinkMessages.OUTER_TYPE, payload, out)
        return out
    }

    private fun handleFrame(frame: NearbyFrame, out: MutableList<NearbyAction>) {
        try {
            val payload = cipher.open(frame)
            when (frame.outerType) {
                ChannelManagement.OUTER_TYPE -> handleChannelManagement(payload, out)
                SocketMessages.OUTER_TYPE_MESSAGE, SocketMessages.OUTER_TYPE_RESOURCE ->
                    handleSocketLayer(frame.outerType, payload, out)
                WifiLinkMessages.OUTER_TYPE ->
                    if (link == NearbyLink.USB) handleUnknownLayer(frame.outerType, payload, out) else handleWifiLink(payload, out)
                else -> handleUnknownLayer(frame.outerType, payload, out)
            }
        } catch (e: MalformedMessageException) {
            // 写明是哪一层，方便看诊断日志时对上是哪条消息。
            out.add(NearbyEvent.InputDiscarded("外层类型 ${frame.outerType}：${e.message ?: "载荷不合法"}", frame.payloadView().size))
        }
    }

    /**
     * 车机作为服务端的处理：逐条处理请求，应答按请求顺序拼成一帧发回。
     * 加密开关一收到就生效，所以同一帧里的应答已经按新状态发。
     * 有线时，加密开启、对端已知（收到过摘要）但还不知道对端蓝牙地址时，先单独发一帧 tag 7
     * 报本机蓝牙地址（手机会回 tag 8），再发应答；这一帧只发一次。蓝牙和 WiFi 通道上对端地址已知，不发。
     */
    private fun handleChannelManagement(payload: ByteArray, out: MutableList<NearbyAction>) {
        val items = ChannelManagement.decode(payload)
        val replies = ByteWriter()
        for (item in items) {
            when (item) {
                is ChannelManagement.Item.Version -> {
                    peerVersion = item.version
                    seen = seen or SEEN_VERSION
                    if (item.request) replies.tlv(ChannelManagement.TAG_VERSION_RESPONSE, LOCAL_VERSION_VALUE)
                }
                is ChannelManagement.Item.Summary -> {
                    seen = seen or SEEN_SUMMARY
                    if (item.request) {
                        peerSummary = item.raw
                        replies.tlv(ChannelManagement.TAG_SUMMARY_RESPONSE, ownDeviceIdText)
                    } else {
                        peerDeviceId = item.raw
                    }
                }
                is ChannelManagement.Item.Encryption -> {
                    encrypted = item.enabled
                    seen = seen or SEEN_ENCRYPTION
                    val value = byteArrayOf(if (item.enabled) 1 else 0)
                    if (item.request) replies.tlv(ChannelManagement.TAG_ENCRYPTION_RESPONSE, value)
                }
                is ChannelManagement.Item.BluetoothAddress -> {
                    val text = item.text
                    peerBluetooth = text
                    out.add(NearbyEvent.PeerBluetoothAddress(text))
                    if (item.request) replies.tlv(ChannelManagement.TAG_BLUETOOTH_RESPONSE, ownAddressText)
                }
                is ChannelManagement.Item.ConnectId -> Unit
                is ChannelManagement.Item.Unknown ->
                    out.add(NearbyEvent.Unhandled(ChannelManagement.OUTER_TYPE, item.tlv.tag, item.tlv.valueView()))
            }
        }
        val peerKnown = peerSummary != null || peerDeviceId != null
        if (link == NearbyLink.USB && encrypted && peerKnown && peerBluetooth == null && !sentBluetoothRequest) {
            sentBluetoothRequest = true
            send(ChannelManagement.OUTER_TYPE, encodeTlv(ChannelManagement.TAG_BLUETOOTH_REQUEST, ownAddressText), out)
        }
        if (replies.size > 0) send(ChannelManagement.OUTER_TYPE, replies.toByteArray(), out)
        if (!handshakeComplete && seen == SEEN_ALL) {
            handshakeComplete = true
            deadline = null
            val summary = peerSummary?.let { ChannelManagement.textOf(it) }
            out.add(NearbyEvent.HandshakeCompleted(peerVersion, summary, encrypted))
        }
    }

    /** 资源层和消息层的一条，先整帧解完再动作。 */
    private sealed class LayerItem {
        class Socket(val request: SocketMessages.Request) : LayerItem()
        class SocketResponse(val response: SocketMessages.Response, val tlv: Tlv) : LayerItem()
        class DataChannel(val request: DataChannelMessages.Request) : LayerItem()
        class Result(val result: DataChannelMessages.Result) : LayerItem()
        object Probe : LayerItem()
        class Other(val tlv: Tlv) : LayerItem()
    }

    /**
     * 资源层和消息层：先把整帧解完再动作，避免解到一半出错时已经回了一部分应答。
     * 握手没完成时收到的建 socket 请求直接拒绝；对端要的协议里没有 TCP 也拒绝（车机只会开 TCP），
     * 蓝牙控制通道上协议 0 的请求除外：那是要车机开 RFCOMM 子通道。
     * 蓝牙控制通道上另外处理消息层的探活（命令 1，回空的命令 2）和资源层的数据通道协商（命令 1、7）。
     */
    private fun handleSocketLayer(outerType: Int, payload: ByteArray, out: MutableList<NearbyAction>) {
        val items = Tlv.parseAll(payload).map { parseLayerItem(outerType, it) }
        for (item in items) {
            when (item) {
                is LayerItem.Socket -> onSocketRequest(outerType, item.request, out)
                is LayerItem.SocketResponse -> onSocketResponse(item, out)
                is LayerItem.DataChannel ->
                    out.add(NearbyEvent.DataChannelRequested(item.request.dataChannel, item.request.capability))
                is LayerItem.Result -> {
                    val r = item.result
                    out.add(NearbyEvent.DataChannelResult(r.dataChannel, r.result, r.role, r.frequencyMhz))
                }
                LayerItem.Probe -> send(SocketMessages.OUTER_TYPE_MESSAGE, encodeTlv(MESSAGE_PROBE_ACK), out)
                is LayerItem.Other -> out.add(NearbyEvent.Unhandled(outerType, item.tlv.tag, item.tlv.valueView()))
            }
        }
    }

    private fun parseLayerItem(outerType: Int, t: Tlv): LayerItem =
        try {
            parseLayerItemValue(outerType, t)
        } catch (e: MalformedMessageException) {
            throw MalformedMessageException("命令 ${t.tag}（${t.length} 字节）：${e.message}", e)
        }

    private fun parseLayerItemValue(outerType: Int, t: Tlv): LayerItem {
        val bluetooth = link == NearbyLink.BLUETOOTH
        return when {
            t.tag == SocketMessages.CMD_CREATE_REQUEST -> LayerItem.Socket(SocketMessages.parseRequest(t.valueView()))
            bluetooth && initiator && outerType == SocketMessages.OUTER_TYPE_MESSAGE && t.tag == SocketMessages.CMD_CREATE_RESPONSE ->
                LayerItem.SocketResponse(SocketMessages.parseResponse(t.valueView()), t)
            bluetooth && outerType == SocketMessages.OUTER_TYPE_MESSAGE && t.tag == MESSAGE_PROBE -> LayerItem.Probe
            bluetooth && outerType == SocketMessages.OUTER_TYPE_RESOURCE && t.tag == DataChannelMessages.CMD_REQUEST ->
                LayerItem.DataChannel(DataChannelMessages.parseRequest(t.valueView()))
            bluetooth && outerType == SocketMessages.OUTER_TYPE_RESOURCE && t.tag == DataChannelMessages.CMD_RESULT ->
                LayerItem.Result(DataChannelMessages.parseResult(t.valueView()))
            else -> LayerItem.Other(t)
        }
    }

    /**
     * 命令 4：车机发出的认证子通道请求（[requestAuthChannel]）的应答。按业务号和标签认出是自己发的那条，
     * 只认第一条；认不出、没发过请求或已经回过的原样交出（[NearbyEvent.Unhandled]）。
     * 同意但通道号不在 1～[MAX_RFCOMM_CHANNEL] 的按拒绝算；UUID 不是 16 字节的不要。
     */
    private fun onSocketResponse(item: LayerItem.SocketResponse, out: MutableList<NearbyAction>) {
        val r = item.response
        val pending = authRequestTag
        if (pending == null || authAnswered || r.businessId != BUSINESS_AUTH || !r.tag.contentEquals(pending)) {
            out.add(NearbyEvent.Unhandled(SocketMessages.OUTER_TYPE_MESSAGE, item.tlv.tag, item.tlv.valueView()))
            return
        }
        authAnswered = true
        when {
            r.status != SocketMessages.STATUS_ACCEPTED -> out.add(NearbyEvent.AuthChannelRejected(r.status))
            r.port !in 1..MAX_RFCOMM_CHANNEL -> {
                out.add(NearbyEvent.InputDiscarded("认证子通道应答里的通道号 ${r.port} 不在 1～$MAX_RFCOMM_CHANNEL", 0))
                out.add(NearbyEvent.AuthChannelRejected(SocketMessages.STATUS_REJECTED))
            }
            else -> out.add(NearbyEvent.AuthChannelReady(r.port, r.uuid?.takeIf { it.size == UUID_BYTES }))
        }
    }

    private fun onSocketRequest(outerType: Int, req: SocketMessages.Request, out: MutableList<NearbyAction>) {
        val rfcomm = link == NearbyLink.BLUETOOTH && req.protocol and SocketMessages.PROTOCOL_TCP == 0
        // 蓝牙控制通道上通道字段可能填 0，按蓝牙通道（2）处理，应答里也回 2。
        val channel = if (link == NearbyLink.BLUETOOTH && req.channel == 0) NearbyLink.BLUETOOTH.channel else req.channel
        val refusal = when {
            !handshakeComplete -> "控制通道握手还没完成"
            rfcomm && req.uuid != null && req.uuid.size != UUID_BYTES -> "RFCOMM 子通道请求里的 uuid 不是 $UUID_BYTES 字节"
            rfcomm -> null
            req.protocol and SocketMessages.PROTOCOL_TCP == 0 -> "对端请求的协议里没有 TCP"
            else -> null
        }
        if (refusal == null) {
            out.add(NearbyEvent.SocketRequested(outerType, channel, req.businessId, req.tag, req.protocol, req.uuid))
        } else {
            out.addAll(respond(outerType, SocketMessages.STATUS_REJECTED, 0, channel, req.businessId, req.tag, 0))
            val tagText = String(req.tag, Charsets.UTF_8)
            out.add(NearbyEvent.SocketRequestRejected(channel, req.businessId, tagText, refusal))
        }
    }

    /**
     * WiFi 组网层（外层类型 33）：手机回的 MAC 或 `busy`、断开请求与应答。先整帧解完再动作；
     * 收到断开请求自动回断开应答。车机才会发的 tag（1、3、4）和不认识的 tag 原样交出。
     */
    private fun handleWifiLink(payload: ByteArray, out: MutableList<NearbyAction>) {
        val items = Tlv.parseAll(payload)
        val macs = items.map { if (it.tag == WifiLinkMessages.TAG_PEER_MAC) WifiLinkMessages.parsePeerMac(it.valueView()) else null }
        for ((i, t) in items.withIndex()) {
            when (t.tag) {
                WifiLinkMessages.TAG_PEER_MAC -> out.add(macs[i]?.let { NearbyEvent.WifiPeerAddress(it) } ?: NearbyEvent.WifiPeerBusy)
                WifiLinkMessages.TAG_DISCONNECT -> {
                    send(WifiLinkMessages.OUTER_TYPE, encodeTlv(WifiLinkMessages.TAG_DISCONNECT_ACK), out)
                    out.add(NearbyEvent.WifiDisconnectRequested)
                }
                WifiLinkMessages.TAG_DISCONNECT_ACK -> out.add(NearbyEvent.WifiDisconnectAnswered)
                else -> out.add(NearbyEvent.Unhandled(WifiLinkMessages.OUTER_TYPE, t.tag, t.valueView()))
            }
        }
    }

    /** 外层类型 1（用途未知）和其他不认识的外层类型：格式未知，按 TLV 试拆，拆不开就整个交出去。 */
    private fun handleUnknownLayer(outerType: Int, payload: ByteArray, out: MutableList<NearbyAction>) {
        val items = try {
            Tlv.parseAll(payload)
        } catch (e: MalformedMessageException) {
            null
        }
        if (items == null || items.isEmpty()) {
            out.add(NearbyEvent.Unhandled(outerType, null, payload))
        } else {
            for (t in items) out.add(NearbyEvent.Unhandled(outerType, t.tag, t.valueView()))
        }
    }

    private fun respond(
        outerType: Int, status: Int, port: Int, channel: Int, businessId: Int, tag: ByteArray, protocol: Int,
        uuid: ByteArray? = null,
    ): List<NearbyAction> {
        val out = ArrayList<NearbyAction>(1)
        send(outerType, SocketMessages.responsePayload(status, port, channel, businessId, tag, protocol, uuid), out)
        return out
    }

    private fun send(outerType: Int, payload: ByteArray, out: MutableList<NearbyAction>) {
        val frame = if (encrypted) {
            cipher.seal(outerType, payload, nextSeed())
        } else {
            NearbyFrame.plain(outerType, payload)
        }
        out.add(NearbyAction.Send(frame.encode()))
    }

    private fun nextSeed(): ByteArray = ByteArray(NearbyFrame.SEED_LENGTH).also { random.nextBytes(it) }

    /** connectId 取 100000～999999：取 4 个随机字节当无符号数，对 900000 取余（这点偏差无所谓）。 */
    private fun nextConnectId(): Int {
        val raw = ByteArray(4).also { random.nextBytes(it) }
        val span = (ChannelManagement.CONNECT_ID_MAX - ChannelManagement.CONNECT_ID_MIN + 1).toLong()
        return (ChannelManagement.CONNECT_ID_MIN + ByteReader(raw).u32Be() % span).toInt()
    }

    override fun toString(): String =
        "NearbySession(${link.name}, 握手完成=$handshakeComplete, 加密=$encrypted, 已作废=$closed)"

    companion object {
        /** carInfo 控制传输的 bmRequestType：主机到设备、厂商请求、目标为设备。 */
        const val CAR_INFO_REQUEST_TYPE = 0x40

        /** carInfo 控制传输的 bRequest。 */
        const val CAR_INFO_REQUEST = 0x34

        /** carInfo 控制传输的 wValue。 */
        const val CAR_INFO_VALUE = 0

        /** carInfo 控制传输的 wIndex。 */
        const val CAR_INFO_INDEX = 0xA0

        /** carInfo 的长度（控制传输的 wLength）。 */
        const val CAR_INFO_LENGTH = 91

        /** 有线连接时控制通道的通道号。 */
        const val CHANNEL_USB = 6

        /** 无线连接：经典蓝牙控制通道的通道号。 */
        const val CHANNEL_BLUETOOTH = 2

        /** 无线连接：WiFi 控制通道（也是数据通道协商里请求的数据通道）的通道号。 */
        const val CHANNEL_WIFI = 3

        /** 车机蓝牙控制通道的 RFCOMM 服务 UUID：手机对车机地址查 SDP 找这个 UUID 拿通道号再连。 */
        val BLUETOOTH_SERVICE_UUID: UUID = UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB")

        /** 手机的 RFCOMM 子通道请求没带 uuid 时用的 UUID。 */
        val DEFAULT_SUBCHANNEL_UUID: UUID = UUID.fromString("0000FE36-0000-1000-8000-00805F9B34FB")

        /** 数据通道协商里的角色：1 = 组主，2 = 组成员。车机只当组主。 */
        const val ROLE_GROUP_OWNER = 1
        const val ROLE_GROUP_CLIENT = 2

        /** RFCOMM 通道号的上限。 */
        const val MAX_RFCOMM_CHANNEL = 30

        /** RFCOMM 子通道要监听的服务 UUID：请求里带了 16 字节 uuid 就用它，没带用 [DEFAULT_SUBCHANNEL_UUID]。 */
        fun rfcommServiceUuid(request: NearbyEvent.SocketRequested): UUID {
            val raw = request.uuid ?: return DEFAULT_SUBCHANNEL_UUID
            require(raw.size == UUID_BYTES) { "uuid 应为 $UUID_BYTES 字节" }
            var high = 0L
            var low = 0L
            for (i in 0 until 8) {
                high = (high shl 8) or (raw[i].toLong() and 0xFF)
                low = (low shl 8) or (raw[8 + i].toLong() and 0xFF)
            }
            return UUID(high, low)
        }

        /** 配对认证的业务号与标签：手机请求车机为它新开一条 TCP，认证消息走那条 TCP。 */
        const val BUSINESS_AUTH = 19
        const val TAG_AUTH = "hicarAuth"

        /** 设备会话（core:dmsdp）的业务号与标签，在认证完成后请求。 */
        const val BUSINESS_DMSDP = 20
        const val TAG_DMSDP = "DMSDP"

        /** 连接建立后多久内握手必须完成（手机那边同样约 5 秒没回应就断开）。 */
        const val HANDSHAKE_TIMEOUT_MILLIS = 5_000L

        /**
         * 本机 deviceId：SHA-256(UTF-8(大写十六进制(车型编号 3 字节 + 子编号 1 字节 + 蓝牙地址 6 字节)))
         * 的 64 位大写十六进制。蓝牙地址取不到（null）或是 `02:00:00:00:00:00` 时，哈希改用 6 个 0 字节。
         * 认证层的本机标识、设备会话里的本机标识都用这个值。算法在 core:common 的 [DeviceId]，这里转调。
         */
        fun deviceId(host: HostIdentity, bluetoothAddress: String?): String = DeviceId.hex(host, bluetoothAddress)

        /**
         * 生成 91 字节 carInfo。
         *
         * @param hostIp 车机在 USB 网卡上的 IP，必须是正好 14 个字符的规范 IPv4，
         *   例如 `192.168.66.100`～`192.168.66.253`；手机切成网卡后来连这个地址。
         * @param port 车机监听控制连接的 TCP 端口（1～65535）。
         * @throws IllegalArgumentException 参数不合法时，错误信息写明原因。
         */
        fun carInfo(host: HostIdentity, bluetoothAddress: String?, hostIp: String, port: Int): ByteArray =
            CarInfo.create(NearbyIdentity(host, bluetoothAddress), hostIp, port).encode()

        private const val SEEN_VERSION = 1
        private const val SEEN_SUMMARY = 2
        private const val SEEN_ENCRYPTION = 4
        private const val SEEN_ALL = SEEN_VERSION or SEEN_SUMMARY or SEEN_ENCRYPTION

        private val LOCAL_VERSION_VALUE = byteArrayOf(ChannelManagement.LOCAL_VERSION.toByte())
        private val ENCRYPTION_ON = byteArrayOf(1)

        private const val UUID_BYTES = 16

        /** 消息层的探活：收到命令 1 回空的命令 2。 */
        private const val MESSAGE_PROBE = 1
        private const val MESSAGE_PROBE_ACK = 2

        private fun uuidBytes(uuid: UUID): ByteArray {
            val out = ByteArray(UUID_BYTES)
            for (i in 0 until 8) {
                out[i] = (uuid.mostSignificantBits ushr (56 - 8 * i)).toByte()
                out[8 + i] = (uuid.leastSignificantBits ushr (56 - 8 * i)).toByte()
            }
            return out
        }
    }
}
