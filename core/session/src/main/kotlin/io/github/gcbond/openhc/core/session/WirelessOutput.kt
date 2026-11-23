package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.nearby.WifiMode
import java.util.UUID

/**
 * [WirelessReceiverSession] 每个方法的返回，**按顺序处理**：
 * - [Common]：与有线相同的 TCP 动作和会话事件（[SessionAction] / [SessionEvent]），照有线的规则执行；
 * - [WirelessAction]：要平台层执行的蓝牙、WiFi 动作；
 * - [WirelessEvent]：无线特有的事件，[WirelessEvent.Ended] 总在最后。
 *
 * 用 [SocketRunner] 执行时，经 [WirelessRunnerBridge] 分流，它保证两边按这里的顺序执行。
 */
sealed interface WirelessOutput {
    /** 与有线相同的输出。 */
    class Common(val output: SessionOutput) : WirelessOutput {
        override fun toString() = output.toString()
    }
}

/** 无线连接里的平台资源，日志和结束原因里用。 */
enum class WirelessChannel(val label: String) {
    /**
     * 蓝牙控制连接（服务 UUID 见 [io.github.gcbond.openhc.core.nearby.NearbySession.BLUETOOTH_SERVICE_UUID]）：
     * 扫码时是手机连进来的，车机主动回连时是车机连手机的。
     */
    BLUETOOTH_CONTROL("蓝牙控制通道"),

    /** 认证用的 RFCOMM 子通道。 */
    BLUETOOTH_AUTH("蓝牙认证子通道"),

    /** 车机开的热点。 */
    WIFI_NETWORK("WiFi 网络"),
}

/**
 * 要平台层做的蓝牙、WiFi 动作。资源用编排器分配的 [id] 标识（与 TCP 资源的 id 不重复），
 * 结果按 id 交回 [WirelessReceiverSession] 对应的方法。
 */
sealed class WirelessAction : WirelessOutput {
    abstract val id: Int

    /**
     * 开 RFCOMM 服务端：不加密、不要求认证，带 SDP 服务记录（UUID = [uuid]，服务名随意）。
     * - 开好后调 `onListening(id, 通道号)`。[needChannelNumber] 为 true 时通道号要回给手机，必须交回系统分配的
     *   真实通道号（1～30）；拿不到就交 0，会话会拒绝这次请求并结束。为 false 时交 0 也行。
     * - **只接受一条连接**，这条连接的 id 是 [acceptId]：接受后调 `onAccepted(acceptId, 对端蓝牙地址)`，
     *   读到的数据调 `onData(acceptId, …)`，读到结尾调 `onClosed(acceptId)`，出错调 `onIoError(acceptId, …)`。
     * - 开不了调 `onIoError(id, 说明)`。
     */
    class ListenRfcomm(
        override val id: Int,
        val channel: WirelessChannel,
        val uuid: UUID,
        val acceptId: Int,
        val needChannelNumber: Boolean,
    ) : WirelessAction() {
        override fun toString() = "ListenRfcomm($id, ${channel.name}, $uuid, 接受为 $acceptId)"
    }

    /** 把 [bytes] 原样写到 RFCOMM 连接 [id]（已是完整的线上字节）。写失败调 `onIoError`。 */
    /**
     * 车机主动连手机的 RFCOMM 服务（车机主动回连，不加密）。[rfcommChannel] 不为 null 时按这个通道号直连
     * （手机在应答里给的）；平台拿不到按通道号直连的接口时，按 [uuid] 查手机的 SDP 记录再连。为 null 时按 [uuid] 查 SDP。
     * 连上交 [WirelessReceiverSession.onConnected]，连不上交 [WirelessReceiverSession.onIoError]；
     * 之后的收发、关闭和监听接受的连接一样（[RfcommWrite]、[RfcommClose]）。
     */
    class ConnectRfcomm(
        override val id: Int,
        val channel: WirelessChannel,
        val address: String,
        val uuid: UUID,
        val rfcommChannel: Int?,
    ) : WirelessAction() {
        override fun toString() = "ConnectRfcomm(${channel.label}, ${rfcommChannel?.let { "通道 $it" } ?: "按 SDP"}，地址不显示)"
    }

    class RfcommWrite(override val id: Int, val bytes: ByteArray) : WirelessAction() {
        override fun toString() = "RfcommWrite($id, ${bytes.size} 字节)"
    }

    /** 关闭 RFCOMM 监听或连接 [id]。之后它的数据和错误都不用再交回。 */
    class RfcommClose(override val id: Int) : WirelessAction() {
        override fun toString() = "RfcommClose($id)"
    }

    /**
     * 建手机要加入的 WiFi 网络：车机开热点，或者建 WiFi 直连组、自己当组主（平台按自己的设置选，交回的
     * [WifiNetwork.mode] 说明是哪种）。名称、密码、信道以平台交回的实际值为准。成功调 `onWifiNetworkReady(id, 网络信息)`；
     * 失败调 `onIoError(id, 说明)`；建好之后网络没了调 `onClosed(id)`。
     */
    class StartWifiNetwork(override val id: Int) : WirelessAction() {
        override fun toString() = "StartWifiNetwork($id)"
    }

    /** 关掉 [StartWifiNetwork] 建的网络（热点或直连组）。 */
    class StopWifiNetwork(override val id: Int) : WirelessAction() {
        override fun toString() = "StopWifiNetwork($id)"
    }
}

/** 平台建好网络后交回的实际信息，全部原样写进给手机的连接串。 */
class WifiNetwork(
    /** 车机在这个网络上的 IPv4（热点网关或直连组主），手机连它。 */
    val localIp: String,
    /** 网络名（热点的 SSID，或直连组名）。 */
    val ssid: String,
    /** 热点网卡或直连网卡的 MAC（BSSID / 组主 MAC）。 */
    val bssid: String,
    /** WPA2-PSK 明文密码。 */
    val passphrase: String,
    /** 实际的中心频率（MHz）。手机按它找网，应当是真实值；平台读不到真实信道时只能交回推测值。 */
    val frequencyMhz: Int,
    /** 热点还是直连组，决定连接串的格式。 */
    val mode: WifiMode = WifiMode.HOTSPOT,
) {
    override fun toString() = "WifiNetwork(${if (mode == WifiMode.DIRECT) "直连组" else "热点"} $localIp, $frequencyMhz MHz，网络名与密码不显示)"
}

/** 无线特有的事件，界面和日志用。toString 不含连接码、地址和密码。 */
sealed class WirelessEvent : WirelessOutput {

    /** 蓝牙监听已开好：把二维码（[qrCode]）和 6 位连接码（[pinCode]）显示给用户。 */
    class PairingReady(val qrCode: String, val pinCode: String) : WirelessEvent() {
        override fun toString() = "PairingReady(内容不显示)"
    }

    /** 手机经蓝牙连上了车机（扫码或回连）。[address] 是手机的蓝牙地址。 */
    class PhoneBluetoothConnected(val address: String) : WirelessEvent() {
        override fun toString() = "PhoneBluetoothConnected(地址不显示)"
    }

    /** 蓝牙控制通道握手完成。 */
    class BluetoothHandshakeCompleted(val encrypted: Boolean) : WirelessEvent() {
        override fun toString() = "BluetoothHandshakeCompleted(加密=$encrypted)"
    }

    /** 数据通道协商完成：手机确认了频率（热点这时已经开好），接下来在热点上开控制监听。 */
    class DataChannelNegotiated(val frequencyMhz: Int) : WirelessEvent() {
        override fun toString() = "DataChannelNegotiated($frequencyMhz MHz)"
    }

    /** 车机的热点已开好（常在手机发协商请求之前）。 */
    class WifiNetworkReady(val frequencyMhz: Int) : WirelessEvent() {
        override fun toString() = "WifiNetworkReady($frequencyMhz MHz)"
    }

    /**
     * 连接串已发给手机，等手机连上热点、连车机的 TCP 端口 [port]。[phoneMac] 是手机报的 WiFi MAC（小写），只用于日志；
     * 会话以手机连上这个端口为准。
     */
    class ConnectionInfoSent(
        val port: Int,
        val phoneMac: String,
        /** 连接串端口后那一段填的信道号（由报给手机的频率换算）；只有热点格式有，直连为 null。 */
        val channel: Int?,
    ) : WirelessEvent() {
        override fun toString() = "ConnectionInfoSent($port，${channel?.let { "信道 $it" } ?: "直连"})"
    }

    /** 会话结束。只出现一次，总在最后；之前已给出关闭全部资源的动作。 */
    /**
     * 车机主动回连时，手机暂时没同意开认证子通道（[status]）。多半是手机弹了确认框（例如手机开着热点，要问用户关不关），
     * 控制通道先留着等手机上的确认；等不到会以 [WirelessEndReason.ReconnectFailed] 结束。
     */
    class ReconnectAwaitingPhone(val status: Int) : WirelessEvent() {
        override fun toString() = "ReconnectAwaitingPhone(状态=$status)"
    }

    class Ended(val reason: WirelessEndReason) : WirelessEvent() {
        override fun toString() = "Ended($reason)"
    }
}

/** 无线连接的阶段，结束原因里用。 */
enum class WirelessStep(val label: String) {
    BLUETOOTH("蓝牙控制通道"),
    AUTH("认证"),
    NEGOTIATION("数据通道协商"),
    WIFI_NETWORK("WiFi 组网"),
    PHONE_JOIN("手机加入 WiFi"),
}

/** 无线会话结束的原因。toString 不含密钥和设备标识。 */
sealed class WirelessEndReason {
    /** 本机调用了 stop。 */
    object LocalStop : WirelessEndReason() {
        override fun toString() = "本机停止"
    }

    /** WiFi 阶段（与有线相同的设备会话和投屏）结束了，[reason] 是那一段给的原因。 */
    class Wired(val reason: EndReason) : WirelessEndReason() {
        override fun toString() = reason.toString()
    }

    class AuthFailed(val failure: AuthFailure) : WirelessEndReason() {
        override fun toString() = "认证失败（$failure）"
    }

    /** 建立 WiFi 数据通道之前的某一步失败（超时、手机拒绝、对端关闭、平台出错、数据不合法等）。 */
    class Failed(val step: WirelessStep, val detail: String) : WirelessEndReason() {
        override fun toString() = "${step.label}失败（$detail）"
    }

    /**
     * 车机主动回连没成：连不上手机、手机不同意、关了连接或一直没回应。[needsPairing] 为 true 表示手机要求输码配对
     * （手机不认这台车了），要重新扫码。
     */
    class ReconnectFailed(val detail: String, val needsPairing: Boolean = false) : WirelessEndReason() {
        override fun toString() = "主动回连没成（$detail）"
    }

    /** 编排器自己出了不该出的错（程序缺陷），已尽量按正常顺序收尾。 */
    class InternalError(val message: String) : WirelessEndReason() {
        override fun toString() = "内部错误（$message）"
    }
}
