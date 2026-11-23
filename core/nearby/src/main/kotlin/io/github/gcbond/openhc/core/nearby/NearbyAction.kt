package io.github.gcbond.openhc.core.nearby

/**
 * [NearbySession] 每次调用返回的动作，**按列表顺序**处理：
 * [Send] 是要写到控制通道上的一帧，[NearbyEvent] 是解析出来的事件。
 */
sealed interface NearbyAction {

    /** 把 [frame] 原样写到控制通道（有线时就是手机连进来的第一条 TCP）上，一帧一次写。 */
    class Send internal constructor(val frame: ByteArray) : NearbyAction {
        override fun toString(): String = "Send(${frame.size} 字节)"
    }
}

/**
 * 控制通道上解析出来的事件。
 * 带设备标识的字段（对端摘要、蓝牙地址）不进 [toString]，日志里可以直接打印事件对象。
 */
sealed class NearbyEvent : NearbyAction {

    /**
     * 握手完成：版本、摘要、加密开关三类请求或应答都已见到，控制通道可以用了，
     * 之后手机会发建 socket 请求。
     *
     * @property peerVersion 对端的通道管理版本（手机发的是 5）；对端没发时为 null。
     * @property peerSummary 对端发来的摘要文本（去掉结尾 NUL）；对端没发时为 null。
     * @property encrypted 之后车机发出的帧是否加密。
     */
    class HandshakeCompleted internal constructor(
        val peerVersion: Long?,
        val peerSummary: String?,
        val encrypted: Boolean,
    ) : NearbyEvent() {
        override fun toString(): String = "HandshakeCompleted(对端版本=$peerVersion, 加密=$encrypted)"
    }

    /**
     * 连接建立后 [NearbySession.HANDSHAKE_TIMEOUT_MILLIS] 内握手没完成。会话已作废，
     * 之后喂进来的数据一律忽略；调用方应关闭这条连接。
     */
    data object HandshakeTimedOut : NearbyEvent()

    /** 对端报来了自己的蓝牙地址文本（`AA:BB:CC:DD:EE:FF`）。 */
    class PeerBluetoothAddress internal constructor(val address: String) : NearbyEvent() {
        override fun toString(): String = "PeerBluetoothAddress(${address.length} 字符，内容不显示)"
    }

    /**
     * 对端请求车机为某项业务新开一条 TCP。调用方决定接受还是拒绝：
     * 接受时先开好监听，再用 [NearbySession.acceptSocket] 把端口回给手机；
     * 拒绝用 [NearbySession.rejectSocket]。可以晚一些再回，例如等认证完成后再回 DMSDP 的请求。
     * 手机可能为同一业务重复发请求：每个事件都要回，回同一个已在监听的端口，不要拒绝或回 0。
     *
     * 只有能用 TCP、而且握手已完成的请求才会走到这里；其余的会话自己回拒绝，见 [SocketRequestRejected]。
     * 蓝牙控制通道上协议 0 的请求（要车机开 RFCOMM 子通道）也会走到这里，用 [NearbySession.acceptRfcommSocket] 回应。
     *
     * @property channel 请求所在的通道号（有线是 [NearbySession.CHANNEL_USB]；蓝牙通道上请求填 0 的按 2 给出）。
     * @property businessId 业务号，例如 [NearbySession.BUSINESS_AUTH]、[NearbySession.BUSINESS_DMSDP]。
     * @property protocol 对端支持的协议能力位，bit0 = TCP。
     */
    class SocketRequested internal constructor(
        internal val outerType: Int,
        val channel: Int,
        val businessId: Int,
        internal val rawTag: ByteArray,
        val protocol: Int,
        uuid: ByteArray?,
    ) : NearbyEvent() {
        private val uuidBytes = uuid?.copyOf()

        /** 业务标签，例如认证是 `hicarAuth`。 */
        val tag: String = String(rawTag, Charsets.UTF_8)

        /** 请求末尾可选的 uuid；没有时为 null。 */
        val uuid: ByteArray? get() = uuidBytes?.copyOf()

        override fun toString(): String =
            "SocketRequested(外层类型=$outerType, 通道=$channel, 业务=$businessId, 标签=$tag, 协议=$protocol)"
    }

    /** 会话按协议规则自动回了拒绝的建 socket 请求（握手还没完成，对端要的协议里没有 TCP，或 RFCOMM 请求的 uuid 不对）。 */
    class SocketRequestRejected internal constructor(
        val channel: Int,
        val businessId: Int,
        val tag: String,
        val reason: String,
    ) : NearbyEvent() {
        override fun toString(): String = "SocketRequestRejected(通道=$channel, 业务=$businessId, 标签=$tag：$reason)"
    }

    /**
     * 蓝牙控制通道上，手机请求车机准备一条数据通道（资源层命令 1）。调用方（知道认证是否完成、网络能不能建）决定：
     * 用 [NearbySession.acceptDataChannel] 回车机的角色和频率，或用 [NearbySession.rejectDataChannel] 拒绝。
     *
     * @property dataChannel 请求的数据通道（[NearbySession.CHANNEL_WIFI] = WiFi）。
     */
    class DataChannelRequested internal constructor(val dataChannel: Int, val capability: WifiCapability) : NearbyEvent() {
        override fun toString(): String = "DataChannelRequested(数据通道=$dataChannel, $capability)"
    }

    /**
     * 手机发来协商结果（资源层命令 7）。[accepted] 为 true 表示手机接受了车机给的频率；
     * 这时 [peerRole]（手机自己的角色，2 = 组成员）和 [frequencyMhz] 一般都有值。
     * 结果 255 表示没有可用数据通道，其他非 1 的值表示手机忙。
     */
    class DataChannelResult internal constructor(
        val dataChannel: Int,
        val result: Int,
        val peerRole: Int?,
        val frequencyMhz: Int?,
    ) : NearbyEvent() {
        val accepted: Boolean get() = result == DataChannelMessages.RESULT_OK

        override fun toString(): String =
            "DataChannelResult(数据通道=$dataChannel, 结果=$result, 角色=$peerRole, 频率=$frequencyMhz)"
    }

    /** 手机报来自己的 WiFi MAC（WiFi 组网层 tag 2，已统一成小写）。车机收到后发连接串。 */
    class WifiPeerAddress internal constructor(val address: String) : NearbyEvent() {
        override fun toString(): String = "WifiPeerAddress(内容不显示)"
    }

    /** 手机在 WiFi 组网层回了 `busy`：这次组网失败。 */
    data object WifiPeerBusy : NearbyEvent()

    /** 对端请求断开数据通道（WiFi 组网层 tag 5）；会话已经自动回了断开应答（tag 6）。 */
    data object WifiDisconnectRequested : NearbyEvent()

    /** 对端回了断开应答（WiFi 组网层 tag 6）。 */
    data object WifiDisconnectAnswered : NearbyEvent()

    /**
     * 还没弄懂、会话不处理的条目，原样交出来（通用 TLV 访问）。
     *
     * 载荷能拆成 TLV 时每条一个事件，[tag] 是 tag 或命令号；拆不成 TLV 时 [tag] 为 null，[value] 是整个载荷。
     * 目前会出现的有：手机首包里通道管理的 16、18，有线通道上资源层的数据通道协商（命令 1、2、7），
     * 消息层除建 socket（和蓝牙通道上的探活）外的命令，WiFi 组网层不认识的 tag，以及外层类型 1（用途未知）的全部内容。
     */
    class Unhandled internal constructor(val outerType: Int, val tag: Int?, value: ByteArray) : NearbyEvent() {
        private val bytes = value.copyOf()

        val value: ByteArray get() = bytes.copyOf()

        override fun toString(): String = "Unhandled(外层类型=$outerType, tag=$tag, ${bytes.size} 字节)"
    }

    /**
     * 收到的数据不合法，被丢弃了。拆帧失败（首字节不对、CRC 不符、结构非法）时丢弃的是整个接收缓冲，
     * 载荷内容不合法时只丢这一帧。连接不用断，后面的数据照常处理。
     */
    class InputDiscarded internal constructor(val reason: String, val byteCount: Int) : NearbyEvent() {
        override fun toString(): String = "InputDiscarded($reason，丢弃 $byteCount 字节)"
    }

    /**
     * 车机主动回连（[NearbySession.requestAuthChannel]）：手机同意为认证开子通道，回了它新开的 RFCOMM 通道号（[port]）。
     * 调用方据此连手机那个通道，手机在上面先发认证握手。[uuid] 是手机在应答里带的服务 UUID（没带时为 null，默认 FE36），
     * 手机同时把这个通道按它登记了 SDP，拿不到按通道号直连的接口时可以按它查。
     */
    class AuthChannelReady internal constructor(val port: Int, uuid: ByteArray?) : NearbyEvent() {
        private val uuidBytes = uuid?.copyOf()

        val uuid: ByteArray? get() = uuidBytes?.copyOf()

        override fun toString(): String = "AuthChannelReady(通道号=$port)"
    }

    /** 主动回连：手机拒绝为认证开子通道，[status] 是它回的状态码（非 0）。 */
    class AuthChannelRejected internal constructor(val status: Int) : NearbyEvent() {
        override fun toString(): String = "AuthChannelRejected(状态=$status)"
    }
}
