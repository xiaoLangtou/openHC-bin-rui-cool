package io.github.gcbond.openhc.core.session

import io.github.gcbond.openhc.core.dmsdp.audio.CarAudioFocus
import io.github.gcbond.openhc.core.hisight.HiSightInput
import java.util.ArrayDeque

/**
 * 用 [SocketRunner] 执行 [WirelessReceiverSession]：[WirelessOutput.Common]（TCP 动作和会话事件）交给执行器，
 * 蓝牙、WiFi 动作和无线事件（[WirelessAction] / [WirelessEvent]）交给 [platform]。
 *
 * 两边严格按会话给出的顺序执行：一批输出里排在 TCP 动作后面的平台输出，要等执行器做完前面那段 TCP 动作再交出
 * （例如收尾时先发完投屏的 TEARDOWN，再拆 WiFi 网络）。做法是把剩下的部分经 [SocketRunner.submit] 排到协议线程上，
 * 排队期间新产生的输出接在后面，不插队。
 *
 * ```
 * val bridge = WirelessRunnerBridge(session) { output -> bluetoothAndWifi.execute(output) }
 * val runner = SocketRunner(bridge, events = { event -> ui.show(event) })
 * bridge.attach(runner)
 * runner.start()
 * // 平台交回蓝牙、WiFi 的结果一律经协议线程（数据要先复制一份）：
 * runner.submit { now -> bridge.onData(id, copy, 0, copy.size, now) }
 * runner.submit { now -> bridge.onWifiNetworkReady(id, network, now) }
 * ```
 *
 * [platform] 在协议线程上调用，里面不要阻塞，也不要直接调本类的方法（结果一律经 `runner.submit` 交回）。
 * 所有方法都在协议线程上串行调用，不是线程安全的。
 */
class WirelessRunnerBridge(
    private val session: WirelessReceiverSession,
    /** 会话怎么开始：默认开蓝牙控制监听等手机（[WirelessReceiverSession.start]）；车机主动回连时传它的 startReconnect。 */
    private val begin: (Long) -> List<WirelessOutput> = session::start,
    private val platform: (WirelessOutput) -> Unit,
) : SessionLogic {
    private var submit: ((Long) -> List<SessionOutput>) -> Unit = { throw IllegalStateException("先调用 attach") }

    /** 还没交出的输出，按会话给出的顺序。 */
    private val backlog = ArrayDeque<WirelessOutput>()
    private var drainScheduled = false

    /** 接上执行器，在 `runner.start()` 之前调用。 */
    fun attach(runner: SocketRunner) {
        submit = { task -> runner.submit(task) }
    }

    /** 测试用：自己提供「排到协议线程上稍后执行」的办法。 */
    internal fun attach(submit: ((Long) -> List<SessionOutput>) -> Unit) {
        this.submit = submit
    }

    override val nextTickAtMillis: Long? get() = session.nextTickAtMillis

    /** 会话结束、而且最后一批输出都交出去了，执行器才收工。 */
    override val isEnded: Boolean get() = session.isEnded && backlog.isEmpty() && !drainScheduled

    override fun start(nowMillis: Long) = route(begin(nowMillis))

    override fun onListening(id: Int, port: Int, nowMillis: Long) = route(session.onListening(id, port, nowMillis))

    override fun onAccepted(id: Int, remoteHost: String, nowMillis: Long) =
        route(session.onAccepted(id, remoteHost, nowMillis))

    override fun onConnected(id: Int, nowMillis: Long) = route(session.onConnected(id, nowMillis))

    override fun onData(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
        route(session.onData(id, data, offset, length, nowMillis))

    override fun onDatagram(id: Int, data: ByteArray, offset: Int, length: Int, nowMillis: Long) =
        route(session.onDatagram(id, data, offset, length, nowMillis))

    override fun onClosed(id: Int, nowMillis: Long) = route(session.onClosed(id, nowMillis))

    override fun onIoError(id: Int, message: String, nowMillis: Long) = route(session.onIoError(id, message, nowMillis))

    override fun onTick(nowMillis: Long) = route(session.onTick(nowMillis))

    override fun stop(nowMillis: Long) = route(session.stop(nowMillis))

    /** 平台建好了网络，见 [WirelessReceiverSession.onWifiNetworkReady]。 */
    fun onWifiNetworkReady(id: Int, network: WifiNetwork, nowMillis: Long): List<SessionOutput> =
        route(session.onWifiNetworkReady(id, network, nowMillis))

    /** 发触摸或按键，见 [WirelessReceiverSession.sendInput]；在协议线程上调用，例如 `runner.submit { bridge.sendInput(input) }`。 */
    fun sendInput(input: HiSightInput): List<SessionOutput> = route(session.sendInput(input))

    /** 把车机的音频焦点变化告诉手机，见 [WirelessReceiverSession.sendAudioFocus]；在协议线程上调用。 */
    fun sendAudioFocus(change: CarAudioFocus): List<SessionOutput> = route(session.sendAudioFocus(change))

    private fun route(outputs: List<WirelessOutput>): List<SessionOutput> {
        backlog.addAll(outputs)
        return if (drainScheduled) emptyList() else drain()
    }

    /**
     * 交出开头连续的平台输出，再把紧接着的一段 TCP 输出交给执行器；后面还有就排一个任务，
     * 等执行器做完这段再从这里继续。
     */
    private fun drain(): List<SessionOutput> {
        while (true) {
            val head = backlog.peekFirst() ?: return emptyList()
            if (head is WirelessOutput.Common) break
            backlog.removeFirst()
            platform(head)
        }
        val common = ArrayList<SessionOutput>()
        while (true) {
            val head = backlog.peekFirst() as? WirelessOutput.Common ?: break
            backlog.removeFirst()
            common += head.output
        }
        if (backlog.isNotEmpty()) {
            drainScheduled = true
            submit {
                drainScheduled = false
                drain()
            }
        }
        return common
    }

    override fun toString() = "WirelessRunnerBridge($session, 待交出 ${backlog.size})"
}
