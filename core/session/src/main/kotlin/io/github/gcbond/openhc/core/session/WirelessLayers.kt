package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AccessoryAuthenticatorFactory
import io.github.gcbond.openhc.core.auth.HiCarAuthSession
import io.github.gcbond.openhc.core.auth.KotlinAccessoryAuthenticator
import io.github.gcbond.openhc.core.dmsdp.audio.AudioStreamListener
import io.github.gcbond.openhc.core.nearby.DataChannelRefusal
import io.github.gcbond.openhc.core.nearby.NearbyAction
import io.github.gcbond.openhc.core.nearby.NearbyEvent
import io.github.gcbond.openhc.core.nearby.NearbyLink
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.nearby.WifiCapability
import io.github.gcbond.openhc.core.nearby.WifiConnectionInfo
import java.util.UUID

/*
 * 无线编排器看到的各层接口，和 Layers.kt 的思路一样：正式实现原样转给各层的会话对象，
 * 单元测试换成假的层，只验证交接顺序和异常路径。
 */

internal interface WirelessLayers {
    /** 蓝牙控制通道连上时创建：手机连上来的车机是应答方，车机主动回连时 [initiator] 为 true。 */
    fun bluetoothNearby(initiator: Boolean): BluetoothNearbyLayer

    /** 手机连上认证子通道时创建；[pinCode] 是二维码和屏幕上的连接码。 */
    fun auth(pinCode: String): AuthLayer

    /** WiFi 阶段内嵌的有线编排器用的各层；[config] 是按车机 WiFi 地址建的配置。 */
    fun wifiPhase(config: ReceiverConfig): Layers
}

/** 蓝牙控制通道上 nearby 层的输出（nearby 的事件只能由它自己构造，这里转一层）。 */
internal sealed class BluetoothOutput {
    class Send(val bytes: ByteArray) : BluetoothOutput()
    class HandshakeCompleted(val encrypted: Boolean) : BluetoothOutput()
    object HandshakeTimedOut : BluetoothOutput()

    /** 手机请求开子通道；[rfcomm] 为 true 是协议 0 的 RFCOMM 子通道请求。[handle] 回应时原样交回。 */
    class SocketRequest(val businessId: Int, val tag: String, val rfcomm: Boolean, val handle: Any?) : BluetoothOutput() {
        override fun toString() = "SocketRequest(业务=$businessId, 标签=$tag, RFCOMM=$rfcomm)"
    }

    class DataChannelRequest(val dataChannel: Int, val capability: WifiCapability, val handle: Any?) : BluetoothOutput()
    class DataChannelResult(val accepted: Boolean, val result: Int, val frequencyMhz: Int?) : BluetoothOutput()
    class PeerMac(val mac: String) : BluetoothOutput()
    object PeerBusy : BluetoothOutput()

    /** 车机主动回连：手机同意开认证子通道，[port] 是它新开的 RFCOMM 通道号，[uuid] 是它登记的服务 UUID（没带为 null）。 */
    class AuthChannelReady(val port: Int, val uuid: UUID?) : BluetoothOutput() {
        override fun toString() = "AuthChannelReady(通道 $port)"
    }

    /** 车机主动回连：手机没同意开认证子通道。 */
    class AuthChannelRejected(val status: Int) : BluetoothOutput() {
        override fun toString() = "AuthChannelRejected(状态 $status)"
    }

    /** 不影响流程的事件，[text] 可以直接写日志。 */
    class Note(val text: String) : BluetoothOutput()
}

internal interface BluetoothNearbyLayer {
    val nextTickAtMillis: Long?
    fun start(nowMillis: Long): List<BluetoothOutput>
    fun feed(data: ByteArray, offset: Int, length: Int): List<BluetoothOutput>
    fun onTick(nowMillis: Long): List<BluetoothOutput>

    /** 车机主动回连：握手完成后请手机开认证子通道，[tag] 是业务标签（手机设备名的 UTF-8）。 */
    fun requestAuthChannel(tag: ByteArray): List<BluetoothOutput>

    /** 子通道要监听的服务 UUID。 */
    fun rfcommUuid(request: BluetoothOutput.SocketRequest): UUID
    fun acceptRfcomm(request: BluetoothOutput.SocketRequest, rfcommChannel: Int): List<BluetoothOutput>
    fun reject(request: BluetoothOutput.SocketRequest): List<BluetoothOutput>
    fun acceptDataChannel(request: BluetoothOutput.DataChannelRequest, frequencyMhz: Int): List<BluetoothOutput>
    fun rejectDataChannel(request: BluetoothOutput.DataChannelRequest, refusal: DataChannelRefusal): List<BluetoothOutput>
    fun requestPeerMac(): List<BluetoothOutput>
    fun sendConnectionInfo(info: WifiConnectionInfo): List<BluetoothOutput>
}

/** 正式实现。[audio] 是 WiFi 阶段音频数据会话的出口，交给内嵌有线编排器的 dmsdp 层。 */
internal class ProductionWirelessLayers(
    private val config: WirelessReceiverConfig,
    private val audio: AudioStreamListener = AudioStreamListener.NONE,
) : WirelessLayers {
    override fun bluetoothNearby(initiator: Boolean): BluetoothNearbyLayer = BluetoothNearbyAdapter(
        NearbySession(
            config.identity.host, config.identity.nearbyKeys, config.bluetoothAddress, config.random,
            NearbyLink.BLUETOOTH, initiator,
        ),
    )

    override fun auth(pinCode: String): AuthLayer {
        val factory = AccessoryAuthenticatorFactory { KotlinAccessoryAuthenticator(it, config.trustStore, config.random) }
        val info = config.authDeviceInfo()
        val session = HiCarAuthSession(config.authConfig(pinCode), factory, config.random) { info }
        return object : AuthLayer {
            override fun feed(data: ByteArray, offset: Int, length: Int) = session.feed(data, offset, length)
            override fun onConnectionClosed() = session.onConnectionClosed()
        }
    }

    override fun wifiPhase(config: ReceiverConfig): Layers = WifiPhaseLayers(config, ProductionLayers(config, audio))
}

/** WiFi 阶段：nearby 换成 WiFi 控制通道（车机先当一次握手发起方、不报 TLV7），其余各层与有线相同。 */
internal class WifiPhaseLayers(
    private val config: ReceiverConfig,
    private val base: Layers = ProductionLayers(config),
) : Layers by base {
    override fun nearby(): NearbyLayer = NearbyAdapter(
        NearbySession(config.identity.host, config.identity.nearbyKeys, config.bluetoothAddress, config.random, NearbyLink.WIFI),
    )
}

/** 把蓝牙控制通道上 nearby 层的动作和事件转成 [BluetoothOutput]。 */
internal class BluetoothNearbyAdapter(private val session: NearbySession) : BluetoothNearbyLayer {
    override val nextTickAtMillis: Long? get() = session.nextTickAtMillis

    override fun start(nowMillis: Long) = convert(session.start(nowMillis))

    override fun feed(data: ByteArray, offset: Int, length: Int) = convert(session.feed(data, offset, length))

    override fun onTick(nowMillis: Long) = convert(session.onTick(nowMillis))

    override fun requestAuthChannel(tag: ByteArray) = convert(session.requestAuthChannel(tag))

    override fun rfcommUuid(request: BluetoothOutput.SocketRequest): UUID =
        NearbySession.rfcommServiceUuid(request.handle as NearbyEvent.SocketRequested)

    override fun acceptRfcomm(request: BluetoothOutput.SocketRequest, rfcommChannel: Int) =
        convert(session.acceptRfcommSocket(request.handle as NearbyEvent.SocketRequested, rfcommChannel))

    override fun reject(request: BluetoothOutput.SocketRequest) =
        convert(session.rejectSocket(request.handle as NearbyEvent.SocketRequested))

    override fun acceptDataChannel(request: BluetoothOutput.DataChannelRequest, frequencyMhz: Int) =
        convert(session.acceptDataChannel(request.handle as NearbyEvent.DataChannelRequested, frequencyMhz))

    override fun rejectDataChannel(request: BluetoothOutput.DataChannelRequest, refusal: DataChannelRefusal) =
        convert(session.rejectDataChannel(request.handle as NearbyEvent.DataChannelRequested, refusal))

    override fun requestPeerMac() = convert(session.requestWifiPeerAddress())

    override fun sendConnectionInfo(info: WifiConnectionInfo) = convert(session.sendWifiConnectionInfo(info))

    private fun convert(actions: List<NearbyAction>): List<BluetoothOutput> = actions.map { action ->
        when (action) {
            is NearbyAction.Send -> BluetoothOutput.Send(action.frame)
            is NearbyEvent.HandshakeCompleted -> BluetoothOutput.HandshakeCompleted(action.encrypted)
            NearbyEvent.HandshakeTimedOut -> BluetoothOutput.HandshakeTimedOut
            is NearbyEvent.SocketRequested ->
                BluetoothOutput.SocketRequest(action.businessId, action.tag, action.protocol and PROTOCOL_TCP == 0, action)
            is NearbyEvent.DataChannelRequested ->
                BluetoothOutput.DataChannelRequest(action.dataChannel, action.capability, action)
            is NearbyEvent.DataChannelResult ->
                BluetoothOutput.DataChannelResult(action.accepted, action.result, action.frequencyMhz)
            is NearbyEvent.WifiPeerAddress -> BluetoothOutput.PeerMac(action.address)
            NearbyEvent.WifiPeerBusy -> BluetoothOutput.PeerBusy
            is NearbyEvent.AuthChannelReady -> BluetoothOutput.AuthChannelReady(action.port, action.uuid?.let(::uuidOf))
            is NearbyEvent.AuthChannelRejected -> BluetoothOutput.AuthChannelRejected(action.status)
            // 其余事件（自动拒绝、未处理条目、丢弃的数据等）的 toString 都不含设备标识。
            is NearbyEvent -> BluetoothOutput.Note(action.toString())
        }
    }

    private companion object {
        /** 建 socket 请求的协议能力位 bit0 = TCP；蓝牙通道上不含 TCP 的请求是要开 RFCOMM 子通道。 */
        const val PROTOCOL_TCP = 1

        /** 16 字节大端 UUID（nearby 层已经保证长度）。 */
        fun uuidOf(raw: ByteArray): UUID {
            var high = 0L
            var low = 0L
            for (i in 0 until 8) high = (high shl 8) or (raw[i].toLong() and 0xFF)
            for (i in 8 until 16) low = (low shl 8) or (raw[i].toLong() and 0xFF)
            return UUID(high, low)
        }
    }
}
