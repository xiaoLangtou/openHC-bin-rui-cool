package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.SocketRunner
import io.github.gcbond.openhc.core.session.WifiNetwork
import io.github.gcbond.openhc.core.session.WirelessAction
import io.github.gcbond.openhc.core.session.WirelessChannel
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.core.session.WirelessOutput
import io.github.gcbond.openhc.core.session.WirelessRunnerBridge
import io.github.gcbond.openhc.platform.bluetooth.RfcommClient
import io.github.gcbond.openhc.platform.bluetooth.RfcommEndpoint
import io.github.gcbond.openhc.platform.bluetooth.RfcommEvents
import io.github.gcbond.openhc.core.nearby.WifiMode
import io.github.gcbond.openhc.platform.wifi.DirectGroup
import io.github.gcbond.openhc.platform.wifi.DirectGroupEvents
import io.github.gcbond.openhc.platform.wifi.DirectMacSource
import io.github.gcbond.openhc.platform.wifi.HotspotMacSource
import io.github.gcbond.openhc.platform.wifi.LocalHotspot
import io.github.gcbond.openhc.platform.wifi.LocalHotspotEvents
import io.github.gcbond.openhc.platform.wifi.LocalHotspotHandle
import io.github.gcbond.openhc.platform.wifi.MacText
import java.util.UUID

/** 平台结果交回无线会话的入口（生产实现见 [RunnerResults]：一律经执行器的协议线程）。 */
internal interface WirelessResults {
    fun onListening(id: Int, port: Int)
    fun onAccepted(id: Int, remote: String)

    /** 车机主动连手机的蓝牙连接连上了（车机主动回连）。 */
    fun onConnected(id: Int)
    fun onData(id: Int, data: ByteArray)
    fun onClosed(id: Int)
    fun onIoError(id: Int, message: String)
    fun onWifiNetworkReady(id: Int, network: WifiNetwork)
}

/** 把结果排到执行器的协议线程上交给桥接（数据已经是新数组，不再复制）。 */
internal class RunnerResults : WirelessResults {
    lateinit var runner: SocketRunner
    lateinit var bridge: WirelessRunnerBridge

    override fun onListening(id: Int, port: Int) = runner.submit { now -> bridge.onListening(id, port, now) }

    override fun onAccepted(id: Int, remote: String) = runner.submit { now -> bridge.onAccepted(id, remote, now) }

    override fun onConnected(id: Int) = runner.submit { now -> bridge.onConnected(id, now) }

    override fun onData(id: Int, data: ByteArray) = runner.submit { now -> bridge.onData(id, data, 0, data.size, now) }

    override fun onClosed(id: Int) = runner.submit { now -> bridge.onClosed(id, now) }

    override fun onIoError(id: Int, message: String) = runner.submit { now -> bridge.onIoError(id, message, now) }

    override fun onWifiNetworkReady(id: Int, network: WifiNetwork) =
        runner.submit { now -> bridge.onWifiNetworkReady(id, network, now) }
}

/**
 * 无线会话的平台动作执行器：[WirelessRunnerBridge] 交出的蓝牙、WiFi 动作在这里接到平台层（RFCOMM 监听、本地热点），
 * 结果按会话给的 id 经 [results] 交回；无线事件转给 [onEvent]。
 *
 * - [execute] 在协议线程上调用，不阻塞：开监听、开热点、写都交给平台层自己的线程。
 * - 先把监听、连接、热点登记进表再让平台层开始，平台层报上来的结果按 id 查表，已经关掉的直接丢掉。
 *   同一个平台对象的回调在它自己的线程上按顺序发生，结果经执行器的队列按同样的顺序交回会话。
 * - 会话收尾时会对每个资源发关闭；执行器意外退出时没有，调度方最后调 [closeAll] 兜底。
 *
 * 线程安全：表的读写都加锁（平台线程查表、协议线程登记和关闭、调度线程兜底关闭）。
 */
internal class WirelessPlatformExecutor(
    private val openRfcomm: (serviceName: String, uuid: UUID, events: RfcommEvents) -> RfcommEndpoint,
    /** 车机主动连手机（车机主动回连）：[channel] 不为 null 时先按通道号直连，否则按 [uuid] 查 SDP。 */
    private val dialRfcomm: (address: String, uuid: UUID, channel: Int?, events: RfcommClient.Events) -> RfcommEndpoint,
    private val startHotspot: (LocalHotspotEvents) -> LocalHotspotHandle,
    /** 不为 null 时会话要的网络改建 WiFi 直连组（设置里「无线方式」选了直连）。 */
    private val startDirect: ((DirectGroupEvents) -> LocalHotspotHandle)? = null,
    private val results: WirelessResults,
    private val onEvent: (WirelessEvent) -> Unit,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    private val listeners = HashMap<Int, RfcommEndpoint>()
    private val connections = HashMap<Int, RfcommEndpoint>()

    /** 开着的热点：会话给的 id → 平台的热点。 */
    private val hotspots = HashMap<Int, LocalHotspotHandle>()
    private var closed = false

    /** 开好的热点（日志用：连接串里的信道号由它的频率换算，频率可能是推测值）。 */
    @Volatile
    private var readyHotspot: LocalHotspot? = null

    /** 建好的直连组（日志用）。 */
    @Volatile
    private var readyDirect: DirectGroup? = null

    /** 桥接的平台回调。 */
    fun execute(output: WirelessOutput) {
        when (output) {
            is WirelessAction.ListenRfcomm -> listen(output)
            is WirelessAction.ConnectRfcomm -> dial(output)
            is WirelessAction.RfcommWrite -> write(output)
            is WirelessAction.RfcommClose -> closeRfcomm(output.id)
            is WirelessAction.StartWifiNetwork -> {
                val direct = startDirect
                if (direct != null) startDirectGroup(output.id, direct) else startLocalHotspot(output.id)
            }
            is WirelessAction.StopWifiNetwork -> stopHotspot(output.id)
            is WirelessEvent -> {
                if (output is WirelessEvent.ConnectionInfoSent && output.channel == null) {
                    val group = readyDirect
                    log(
                        "连接串已发给手机（端口 ${output.port}，直连格式），等手机加入直连组（以手机连上 TCP 端口为准）" +
                            "（手机报的 MAC ${MacText.mask(output.phoneMac)}；组在 ${group?.frequencyMhz ?: "?"} MHz，系统交回的真实频率）",
                    )
                } else if (output is WirelessEvent.ConnectionInfoSent) {
                    log(
                        "连接串已发给手机（端口 ${output.port}），等手机连上车机热点（以手机连上 TCP 端口为准，热点看不到终端列表）" +
                            "（手机报的 MAC ${MacText.mask(output.phoneMac)}）",
                    )
                    val hotspot = readyHotspot
                    log(
                        "连接串端口后一段填信道号 ${output.channel}：按报给手机的频率 ${hotspot?.frequencyMhz ?: "?"} MHz 换算，" +
                            "频率来源：${hotspot?.frequencySource?.label ?: "未知"}",
                    )
                }
                onEvent(output)
            }
            // 桥接只把平台输出交到这里；万一来了 TCP 输出，记一笔不执行。
            is WirelessOutput.Common -> log("平台回调收到了 TCP 输出，忽略：$output")
        }
    }

    /** 关掉还开着的全部监听、连接和热点（会话没能按顺序收尾时兜底）。之后再来的动作一律不执行。 */
    fun closeAll() {
        val endpoints: List<RfcommEndpoint>
        val handles: List<LocalHotspotHandle>
        synchronized(lock) {
            closed = true
            endpoints = (listeners.values + connections.values).distinct()
            handles = hotspots.values.toList()
            listeners.clear()
            connections.clear()
            hotspots.clear()
        }
        if (endpoints.isNotEmpty() || handles.isNotEmpty()) log("兜底关闭：${endpoints.size} 个蓝牙监听或连接、${handles.size} 个热点")
        for (e in endpoints) {
            e.closeListener()
            e.closeConnection()
        }
        for (h in handles) h.stop()
    }

    // ---------------------------------------------------------------- 蓝牙

    private fun listen(action: WirelessAction.ListenRfcomm) {
        val label = action.channel.label
        val events = object : RfcommEvents {
            override fun onListening(channel: Int) = ifListening(action.id) {
                val port = if (channel in RFCOMM_CHANNELS) channel else 0
                log("$label：监听已开（UUID ${action.uuid}，通道号 ${if (port == 0) "读不到" else port}）")
                if (port == 0 && action.needChannelNumber) log("$label：拿不到通道号，手机连不上这个子通道，会话会结束")
                results.onListening(action.id, port)
            }

            override fun onListenFailed(message: String) = ifListening(action.id) {
                log("$label：$message")
                results.onIoError(action.id, message)
            }

            override fun onAccepted(remoteAddress: String) = ifConnected(action.acceptId) {
                log("$label：手机连上了（${MacText.mask(remoteAddress)}）")
                results.onAccepted(action.acceptId, remoteAddress)
            }

            override fun onData(data: ByteArray) = ifConnected(action.acceptId) { results.onData(action.acceptId, data) }

            override fun onClosed() = ifConnected(action.acceptId) {
                log("$label：手机关闭了连接")
                results.onClosed(action.acceptId)
            }

            override fun onConnectionError(message: String) = ifConnected(action.acceptId) {
                log("$label：$message")
                results.onIoError(action.acceptId, message)
            }
        }
        val name = if (action.channel == WirelessChannel.BLUETOOTH_CONTROL) CONTROL_SERVICE_NAME else AUTH_SERVICE_NAME
        val endpoint = openRfcomm(name, action.uuid, events)
        synchronized(lock) {
            if (!closed) {
                listeners[action.id] = endpoint
                connections[action.acceptId] = endpoint
            }
        }
        if (isClosed()) {
            endpoint.closeListener()
            endpoint.closeConnection()
            return
        }
        endpoint.start()
    }

    private fun dial(action: WirelessAction.ConnectRfcomm) {
        val label = action.channel.label
        val how = action.rfcommChannel?.let { "通道 $it" } ?: "按 SDP 查 ${action.uuid}"
        log("$label：车机去连手机（${MacText.mask(action.address)}，$how）")
        val events = object : RfcommClient.Events {
            override fun onConnected() = ifConnected(action.id) {
                log("$label：连上手机了")
                results.onConnected(action.id)
            }

            override fun onConnectFailed(message: String) = ifConnected(action.id) {
                log("$label：$message")
                results.onIoError(action.id, message)
            }

            override fun onData(data: ByteArray) = ifConnected(action.id) { results.onData(action.id, data) }

            override fun onClosed() = ifConnected(action.id) {
                log("$label：手机关闭了连接")
                results.onClosed(action.id)
            }

            override fun onConnectionError(message: String) = ifConnected(action.id) {
                log("$label：$message")
                results.onIoError(action.id, message)
            }

            override fun onNote(message: String) = ifConnected(action.id) { log("$label：$message") }
        }
        val endpoint = dialRfcomm(action.address, action.uuid, action.rfcommChannel, events)
        synchronized(lock) {
            if (!closed) connections[action.id] = endpoint
        }
        if (isClosed()) {
            endpoint.closeConnection()
            return
        }
        endpoint.start()
    }

    private fun write(action: WirelessAction.RfcommWrite) {
        val endpoint = synchronized(lock) { connections[action.id] }
        if (endpoint == null) {
            log("写到已经关闭或不存在的蓝牙连接 ${action.id}，丢弃 ${action.bytes.size} 字节")
            return
        }
        // 写在平台层的线程上异步做：复制一份，不依赖会话之后怎么用这个数组。
        endpoint.write(action.bytes.copyOf())
    }

    /** 关监听或连接；收尾时会对没连上的连接 id 也发一次，这时只是不再接受。 */
    private fun closeRfcomm(id: Int) {
        val listener: RfcommEndpoint?
        val connection: RfcommEndpoint?
        synchronized(lock) {
            listener = listeners.remove(id)
            connection = connections.remove(id)
        }
        listener?.closeListener()
        connection?.closeConnection()
    }

    // ---------------------------------------------------------------- WiFi

    /** 开热点：名称、密码、频率都由系统定，开好后交回实际值（频率多半是推测值）。会话在手机认证通过后才要热点。 */
    private fun startLocalHotspot(id: Int) {
        val events = object : LocalHotspotEvents {
            override fun onReady(hotspot: LocalHotspot) = ifHotspot(id) {
                if (!hotspot.frequencySource.verified) {
                    log("热点的频率是推测值（${hotspot.frequencySource.label}）：手机找不到网时先查这个")
                }
                if (hotspot.bssidSource == HotspotMacSource.PLACEHOLDER) log("读不到热点网卡的真实 MAC，连接串里的 BSSID 用占位值")
                readyHotspot = hotspot
                results.onWifiNetworkReady(
                    id,
                    WifiNetwork(hotspot.localIp, hotspot.networkName, hotspot.bssid, hotspot.passphrase, hotspot.frequencyMhz),
                )
            }

            override fun onFailed(message: String) = ifHotspot(id) { results.onIoError(id, message) }

            override fun onLost(message: String) = ifHotspot(id) {
                log(message)
                results.onClosed(id)
            }
        }
        val handle = startHotspot(events)
        synchronized(lock) { if (!closed) hotspots[id] = handle }
        if (isClosed()) return handle.stop()
        log("开始开热点（手机已经认证通过）")
        handle.start()
    }

    private fun stopHotspot(id: Int) {
        val handle = synchronized(lock) { hotspots.remove(id) } ?: return
        log(if (startDirect != null) "拆掉直连组" else "关掉热点")
        handle.stop()
    }

    /** 建直连组：组名、口令、频率都由本机定，建好后交回系统读回的实际值；连接串用直连格式。 */
    private fun startDirectGroup(id: Int, factory: (DirectGroupEvents) -> LocalHotspotHandle) {
        val events = object : DirectGroupEvents {
            override fun onReady(group: DirectGroup) = ifHotspot(id) {
                if (group.macSource == DirectMacSource.PLACEHOLDER) log("读不到直连网卡的真实 MAC，连接串里的组主 MAC 用占位值")
                readyDirect = group
                results.onWifiNetworkReady(
                    id,
                    WifiNetwork(group.localIp, group.networkName, group.ownerMac, group.passphrase, group.frequencyMhz, WifiMode.DIRECT),
                )
            }

            override fun onFailed(message: String) = ifHotspot(id) { results.onIoError(id, message) }

            override fun onLost(message: String) = ifHotspot(id) {
                log(message)
                results.onClosed(id)
            }
        }
        val handle = factory(events)
        synchronized(lock) { if (!closed) hotspots[id] = handle }
        if (isClosed()) return handle.stop()
        log("开始建直连组（手机已经认证通过）")
        handle.start()
    }

    // ---------------------------------------------------------------- 工具

    private inline fun ifListening(id: Int, block: () -> Unit) {
        if (synchronized(lock) { listeners.containsKey(id) }) block()
    }

    private inline fun ifConnected(id: Int, block: () -> Unit) {
        if (synchronized(lock) { connections.containsKey(id) }) block()
    }

    private inline fun ifHotspot(id: Int, block: () -> Unit) {
        if (synchronized(lock) { hotspots.containsKey(id) }) block()
    }

    private fun isClosed() = synchronized(lock) { closed }

    companion object {
        /** SDP 里登记的服务名，手机不看名字。 */
        const val CONTROL_SERVICE_NAME = "OpenHC"
        const val AUTH_SERVICE_NAME = "OpenHC-Auth"

        private val RFCOMM_CHANNELS = 1..30
    }
}
