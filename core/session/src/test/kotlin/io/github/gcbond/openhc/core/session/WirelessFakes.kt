package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.nearby.WifiMode
import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.nearby.DataChannelRefusal
import io.github.gcbond.openhc.core.nearby.NearbySession
import io.github.gcbond.openhc.core.nearby.WifiCapability
import io.github.gcbond.openhc.core.nearby.WifiChannels
import io.github.gcbond.openhc.core.nearby.WifiConnectionInfo
import java.util.TimeZone
import java.util.UUID

/** 无线测试用的合成值。 */
internal object WirelessSynthetic {
    const val PIN = "135790"
    const val PHONE_BLUETOOTH = "02:66:77:88:99:AA"
    const val PHONE_MAC = "02:aa:bb:cc:dd:ee"

    /** 车机在热点上的地址（热点网关）。 */
    const val HOTSPOT_IP = "192.168.88.1"

    /** 手机在热点上的地址：车机按网段给手机挑的第一个空闲地址。 */
    const val PHONE_WIFI_IP = "192.168.88.2"

    /** 热点的口令（合成值）。 */
    const val HOTSPOT_PASSPHRASE = "12345678"

    /** 平台开好的热点：网络名、BSSID、口令都是合成值。 */
    fun hotspot(frequencyMhz: Int = 5745, localIp: String = HOTSPOT_IP) =
        WifiNetwork(localIp, "OpenHC-5678", "0A:1B:2C:3D:4E:5F", HOTSPOT_PASSPHRASE, frequencyMhz)

    /** 车机建的直连组（组主地址 192.168.49.1）。 */
    fun direct(frequencyMhz: Int = 5745) =
        WifiNetwork("192.168.49.1", "DIRECT-ab-OpenHC", "0A:1B:2C:3D:4E:5F", HOTSPOT_PASSPHRASE, frequencyMhz, WifiMode.DIRECT)

    /** 手机能力：组主、组成员都行，2.4G、5G 都支持，9 个 5G 信道全有，当前没连 WiFi。 */
    fun capability(channels: Collection<Int> = WifiChannels.CHANNELS_5G) = WifiCapability(
        canBeGroupOwner = true,
        canBeGroupClient = true,
        supports2g = true,
        supports5g = true,
        channelBitmap = WifiChannels.bitmapOf(channels),
        dualBand = true,
        connectedFrequencyMhz = 0,
    )

    fun config(
        timeouts: WirelessTimeouts = WirelessTimeouts(),
        supportsUsb: Boolean = true,
    ) = WirelessReceiverConfig(
        identity = Synthetic.IDENTITY,
        bluetoothAddress = Synthetic.BLUETOOTH,
        display = DisplaySettings(1280, 720, 30, dpi = 160, density = 1.0),
        trustStore = InMemoryTrustStore(),
        supportsUsb = supportsUsb,
        timeZone = TimeZone.getTimeZone("UTC"),
        wallClock = { 1_767_312_000_000L },
        pin = PIN,
        timeouts = timeouts,
    )
}

/**
 * 假的蓝牙 nearby 层：手机发来的字节当成文本命令，交给 [onFeed]；默认命令：
 * `hello` 握手完成并请求认证子通道，`auth?` 再请求一次，`other?` 请求别的业务，
 * `nego` 数据通道协商（能力见 [capability]），`nego-ble` 要的不是 WiFi，`confirm` 确认协商（频率照抄车机给的），
 * `refuse` 手机忙，`mac` 报 MAC，`busy` 回忙。
 */
/** 假手机在主动回连应答里带的服务 UUID（和默认的 FE36 不同，便于核对车机用的是哪个）。 */
internal val RECONNECT_UUID: java.util.UUID = java.util.UUID.fromString("0000fe36-0000-1000-8000-00805f9b0001")

internal class FakeBluetoothNearby(private val calls: MutableList<String>) : BluetoothNearbyLayer {
    override var nextTickAtMillis: Long? = null
    var capability: WifiCapability = WirelessSynthetic.capability()
    var uuid: UUID = NearbySession.DEFAULT_SUBCHANNEL_UUID
    var lastFrequency = 0
    var lastInfo: WifiConnectionInfo? = null
    var onTickHandler: (Long) -> List<BluetoothOutput> = { emptyList() }
    var onFeed: (String) -> List<BluetoothOutput> = { text ->
        when (text) {
            "hello" -> listOf(BluetoothOutput.HandshakeCompleted(true), authRequest())
            "auth?" -> listOf(authRequest())
            "other?" -> listOf(BluetoothOutput.SocketRequest(20, "DMSDP", false, null))
            "nego" -> listOf(BluetoothOutput.DataChannelRequest(NearbySession.CHANNEL_WIFI, capability, null))
            "nego-ble" -> listOf(BluetoothOutput.DataChannelRequest(1, capability, null))
            "confirm" -> listOf(BluetoothOutput.DataChannelResult(true, 1, lastFrequency))
            "refuse" -> listOf(BluetoothOutput.DataChannelResult(false, 2, null))
            "mac" -> listOf(BluetoothOutput.PeerMac(WirelessSynthetic.PHONE_MAC))
            "busy" -> listOf(BluetoothOutput.PeerBusy)
            // 车机主动回连：握手完成（手机不请求开子通道），手机对车机请求的应答。
            "hs" -> listOf(BluetoothOutput.HandshakeCompleted(true))
            "cmd4-ok" -> listOf(BluetoothOutput.AuthChannelReady(8, null))
            "cmd4-uuid" -> listOf(BluetoothOutput.AuthChannelReady(9, RECONNECT_UUID))
            "cmd4-no" -> listOf(BluetoothOutput.AuthChannelRejected(1))
            else -> emptyList()
        }
    }

    fun authRequest() = BluetoothOutput.SocketRequest(NearbySession.BUSINESS_AUTH, NearbySession.TAG_AUTH, true, null)

    override fun start(nowMillis: Long): List<BluetoothOutput> {
        calls += "bt.start"
        return listOf(BluetoothOutput.Send("bt-connect-id".bytes()))
    }

    override fun feed(data: ByteArray, offset: Int, length: Int): List<BluetoothOutput> {
        val text = data.text(offset, length)
        calls += "bt.feed($text)"
        return onFeed(text)
    }

    override fun onTick(nowMillis: Long) = onTickHandler(nowMillis)

    override fun requestAuthChannel(tag: ByteArray): List<BluetoothOutput> {
        calls += "bt.requestAuthChannel(${tag.toString(Charsets.UTF_8)})"
        return listOf(BluetoothOutput.Send("authreq".bytes()))
    }

    override fun rfcommUuid(request: BluetoothOutput.SocketRequest): UUID = uuid

    override fun acceptRfcomm(request: BluetoothOutput.SocketRequest, rfcommChannel: Int): List<BluetoothOutput> {
        calls += "bt.acceptRfcomm(${request.businessId},$rfcommChannel)"
        return listOf(BluetoothOutput.Send("rfcomm:$rfcommChannel".bytes()))
    }

    override fun reject(request: BluetoothOutput.SocketRequest): List<BluetoothOutput> {
        calls += "bt.reject(${request.businessId})"
        return listOf(BluetoothOutput.Send("reject:${request.businessId}".bytes()))
    }

    override fun acceptDataChannel(request: BluetoothOutput.DataChannelRequest, frequencyMhz: Int): List<BluetoothOutput> {
        calls += "bt.acceptDataChannel($frequencyMhz)"
        lastFrequency = frequencyMhz
        return listOf(BluetoothOutput.Send("dc:$frequencyMhz".bytes()))
    }

    override fun rejectDataChannel(request: BluetoothOutput.DataChannelRequest, refusal: DataChannelRefusal): List<BluetoothOutput> {
        calls += "bt.rejectDataChannel(${refusal.name})"
        return listOf(BluetoothOutput.Send("dc-refused:${refusal.code}".bytes()))
    }

    override fun requestPeerMac(): List<BluetoothOutput> {
        calls += "bt.requestPeerMac"
        return listOf(BluetoothOutput.Send("mac?".bytes()))
    }

    override fun sendConnectionInfo(info: WifiConnectionInfo): List<BluetoothOutput> {
        calls += "bt.sendConnectionInfo"
        lastInfo = info
        return listOf(BluetoothOutput.Send("info".bytes()))
    }
}

/** 假的无线各层：蓝牙 nearby 和认证用假的，WiFi 阶段用有线测试的 [FakeLayers]。 */
internal class FakeWirelessLayers : WirelessLayers {
    val calls = ArrayList<String>()
    val bluetooth = FakeBluetoothNearby(calls)
    val auth = FakeAuth(calls)
    val wifi = FakeLayers()
    var authPin: String? = null
    var wifiConfig: ReceiverConfig? = null

    init {
        // WiFi 控制通道上：`dmsdp?` 握手完成并请求业务 20（通道 3），`auth?` 请求业务 19。
        wifi.nearby.onFeed = { text ->
            when (text) {
                "dmsdp?" -> listOf(NearbyOutput.HandshakeCompleted(true), NearbyOutput.SocketRequest(20, 3, "DMSDP", null))
                "dmsdp-usb?" -> listOf(NearbyOutput.HandshakeCompleted(true), NearbyOutput.SocketRequest(20, 6, "DMSDP", null))
                "auth?" -> listOf(NearbyOutput.HandshakeCompleted(true), NearbyOutput.SocketRequest(19, 3, "hicarAuth", null))
                else -> emptyList()
            }
        }
    }

    override fun bluetoothNearby(initiator: Boolean): BluetoothNearbyLayer {
        calls += if (initiator) "bt.create(发起方)" else "bt.create"
        return bluetooth
    }

    override fun auth(pinCode: String): AuthLayer {
        calls += "auth.create"
        authPin = pinCode
        return auth
    }

    override fun wifiPhase(config: ReceiverConfig): Layers {
        calls += "wifi.create(${config.localIp})"
        wifiConfig = config
        return wifi
    }
}
