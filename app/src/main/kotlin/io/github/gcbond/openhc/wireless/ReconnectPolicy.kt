package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessEndReason

/**
 * 无线页空闲时的一行说明：「蓝牙连上自动连接」现在能不能起作用，不能的话为什么（[ReconnectPolicy.hold]）。
 * 只是说明，不当成错误。
 */
enum class AutoHold {
    /** 一切就绪：手机蓝牙连上车机时会自动连接。 */
    READY,

    /** 设置里开着「只用数据线连接」：完全不做无线。 */
    WIRED_ONLY,

    /** 「蓝牙连上自动连接」关着。 */
    DISABLED,

    /** 用户点了停止：这次蓝牙连接里不再自动连，手机蓝牙重新连上车机时再连。 */
    PAUSED_BY_USER,

    /** 有线连接在进行。 */
    WIRED_ACTIVE,

    /** 车机系统是安卓 8.0 以下，没有本地热点：用不了无线连接。 */
    HOTSPOT_UNSUPPORTED,

    /** 没有身份参数。 */
    IDENTITY_MISSING,

    /** 还没有配对过的手机（有线、无线配对的都算）。只剩扫码等待里用到。 */
    NO_PAIRED_PHONE,

    /** 还没有用无线连成功过的手机：自动连接只拨连成功过的手机，扫码连一次以后才会自动连。 */
    NO_KNOWN_PHONE,

    BLUETOOTH_OFF,

    /** 缺运行时权限。 */
    PERMISSIONS_MISSING,

    /** 车机报告不支持 BLE 广播。只剩扫码等待里用到。 */
    ADVERTISING_UNSUPPORTED,

    /** 读不到车机真实的蓝牙地址。 */
    BLUETOOTH_ADDRESS_MISSING,

    /** 安卓 10 起没有悬浮窗权限时，手机蓝牙连上时打不开界面，热点也开不了（定位权限只在界面在前台时算数）。 */
    OVERLAY_MISSING,

    /** 手机蓝牙连上了，但界面没能自动打开（多半被系统拦了），这次没拨。 */
    UI_NOT_SHOWN,

    /** 应用在后台，系统不让起前台服务；回到界面时再试。 */
    FOREGROUND_UNAVAILABLE,

    /** 其他原因开始不了（见日志）。 */
    START_FAILED,
}

/**
 * 「蓝牙连上自动连接」现在能不能起作用（纯逻辑，JVM 单元测试覆盖；接线见 [WirelessController]）。
 * 按优先级取第一个不满足的条件；都满足返回 [AutoHold.READY]。
 */
internal object ReconnectPolicy {

    /** 判断时的条件快照。 */
    class Conditions(
        /** 「蓝牙连上自动连接」开着。 */
        val enabled: Boolean,
        val wiredOnly: Boolean = false,
        val wiredActive: Boolean = false,
        /** 系统能开本地热点（安卓 8.0 起）。 */
        val hotspotSupported: Boolean = true,
        val identityLoaded: Boolean = true,
        val bluetoothAddressKnown: Boolean = true,
        val bluetoothOn: Boolean = true,
        val permissionsGranted: Boolean = true,
        /** 用无线连成功过的手机有几台。 */
        val knownPhones: Int,
        /** 后台打开界面要的悬浮窗权限有了（安卓 10 以前不需要，算有）。 */
        val overlayGranted: Boolean = true,
    )

    fun hold(c: Conditions): AutoHold = when {
        c.wiredOnly -> AutoHold.WIRED_ONLY
        !c.enabled -> AutoHold.DISABLED
        c.wiredActive -> AutoHold.WIRED_ACTIVE
        !c.hotspotSupported -> AutoHold.HOTSPOT_UNSUPPORTED
        !c.identityLoaded -> AutoHold.IDENTITY_MISSING
        !c.bluetoothAddressKnown -> AutoHold.BLUETOOTH_ADDRESS_MISSING
        !c.bluetoothOn -> AutoHold.BLUETOOTH_OFF
        !c.permissionsGranted -> AutoHold.PERMISSIONS_MISSING
        c.knownPhones <= 0 -> AutoHold.NO_KNOWN_PHONE
        !c.overlayGranted -> AutoHold.OVERLAY_MISSING
        else -> AutoHold.READY
    }

    /**
     * 无线挡不挡有线（点「开始」、开机等插线这类有线开始）：在进行的无线连接要挡；扫码等待里手机还没连上时
     * （[WirelessUiState.waitingForReconnect]），有线来了就让给有线。
     */
    fun blocksWired(state: WirelessUiState): Boolean = state.active && !state.waitingForReconnect

    /**
     * 真插上了手机时：无线这边手机已经经蓝牙连上（正在连、投屏中）就不管插线（多半是在充电）；
     * 还在等手机（扫码、等回连、车机在拨）就让给有线（插线优先）。
     */
    fun phoneOnWireless(state: WirelessUiState): Boolean = state.active && state.phoneConnected

    /** 这次无线连接是不是因为「手机要求重新扫码配对」（例如手机删了这辆车，状态 1 秒拒）结束的。 */
    fun needsPairing(cause: WirelessEndCause): Boolean {
        val reason = (cause as? WirelessEndCause.Session)?.reason
        return reason is WirelessEndReason.ReconnectFailed && reason.needsPairing
    }
}

/**
 * 车机主动回连什么时候拨、拨谁（纯逻辑，JVM 单元测试覆盖；接线见 [WirelessController]）。只在一个线程上调用。
 *
 * 分「问」和「记」两步：[onBluetoothConnected]、[onUiShown] 只回答拨不拨；真的开始拨了（连接建出来了）才调 [commitDial] 记下。
 * 没拨成（前置条件不过、有线在进行、界面没出来……）不占名额，条件补上以后还会拨。
 *
 * - 只拨用无线连成功过的手机（按蓝牙地址，调用方传入）。
 * - 手机蓝牙连上车机（[onBluetoothConnected]）：算一次新的蓝牙连接，拨这台手机。
 * - 界面回到前台（[onUiShown]，含开机自动打开）：拨最近连成功的那台；一次蓝牙连接只拨一次——拨过以后要等这台手机的蓝牙
 *   断开（[onBluetoothDisconnected]）或重新连上才再拨。用户点了停止（[onSessionEnded]，扫码连上的也算）同样记作拨过，
 *   所以停止以后切走再回来不会被拨回去。投屏过、不是用户停的结束重新计次：首页说「打开 OpenHC 时会自动连」，回到界面就该连。
 * - 同一台手机两次拨号至少隔 [cooldownMillis]：车机拨号本身会让手机蓝牙「连上」，系统会再报一次连上，
 *   比亚迪的广播还要排队几秒，可能在这次拨号失败之后才到，不挡就会接着再拨。冷却里收到的「连上」不算新的一次蓝牙连接。
 */
internal class ReconnectTriggers(private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS) {

    sealed class Decision {
        /** 拨 [address]（大写的蓝牙地址）。真的开始拨了要调 [commitDial]。 */
        data class Dial(val address: String) : Decision()

        /** 这次不拨，[reason] 写日志用。 */
        data class Skip(val reason: String) : Decision()
    }

    private val lastDialAt = HashMap<String, Long>()
    private val dialedThisConnection = HashSet<String>()

    fun onBluetoothConnected(address: String, knownPhones: Set<String>, nowMillis: Long): Decision {
        val key = key(address)
        if (knownPhones.none { key(it) == key }) return Decision.Skip("不是用无线连成功过的手机")
        coolingDown(key, nowMillis)?.let { return Decision.Skip("$it（多半是车机拨号让它连上的）") }
        dialedThisConnection.remove(key)
        return Decision.Dial(key)
    }

    fun onBluetoothDisconnected(address: String) {
        dialedThisConnection.remove(key(address))
    }

    /** 忘掉这台手机的冷却和拨号状态（手机要求重新扫码配对时调用）。 */
    fun forget(address: String) {
        val key = key(address)
        lastDialAt.remove(key)
        dialedThisConnection.remove(key)
    }

    fun onUiShown(lastPhone: String?, knownPhones: Set<String>, nowMillis: Long): Decision {
        val key = lastPhone?.let(::key) ?: return Decision.Skip("还没有用无线连成功过的手机")
        if (knownPhones.none { key(it) == key }) return Decision.Skip("最近那台手机不在记录里")
        if (key in dialedThisConnection) return Decision.Skip("这次蓝牙连接里已经拨过，手机蓝牙重新连上车机时再拨")
        coolingDown(key, nowMillis)?.let { return Decision.Skip(it) }
        return Decision.Dial(key)
    }

    /** 真的开始拨了（含调试入口直接拨）：记进冷却和这次蓝牙连接。 */
    fun commitDial(address: String, nowMillis: Long) {
        val key = key(address)
        lastDialAt[key] = nowMillis
        dialedThisConnection += key
    }

    /**
     * 一次连接结束（不论扫码还是车机拨的）：用户停的，这次蓝牙连接里回到界面不再拨；投屏过、不是用户停的，重新计次（回到界面会再拨）。
     *
     * 2026-11-23 起：投屏过、非用户停止（= 连上后中途断掉）时连冷却一起清掉。原设计里拨号起 60 秒冷却，
     * 连上投屏 40 秒后断线的话，手机蓝牙再连上/回到界面都会被冷却挡到（「xx 秒前刚拨过」），车主只能干等或手动点连接。
     * 清掉后手机蓝牙再连上（会话结束时手机通常会断开两条蓝牙链路，之后蓝牙回连是常见事件）就立即自动重拨，
     * 行驶中的掉线能自动恢复。风暴风险有限：只有投屏过的结束才清，拨号失败（没投过屏）仍受冷却约束；
     * 重拨成功又断，最多按「断→重拨→断」循环，每轮要过完整认证+入网流程（几十秒），不是紧密循环。
     *
     * 2026-11-26 起：投屏没成就自己断掉的（非用户停止、没投过屏）也从「这次蓝牙连接里拨过」里释放——
     * 自动重试打满后，车主切回界面（[onUiShown]）就能立刻再拨，不用等手机的蓝牙真的断开重连；
     * [lastDialAt] 照旧保留，挡住车机拨号引发的「连上」广播回声。
     */
    fun onSessionEnded(address: String, projected: Boolean, stoppedByUser: Boolean) {
        val key = key(address)
        when {
            stoppedByUser -> dialedThisConnection += key
            projected -> {
                dialedThisConnection.remove(key)
                lastDialAt.remove(key)
            }
            // 非用户主动停止的连接失败（还没投屏就断了）：允许切回界面（onUiShown）时立即再拨，
            // 只留 lastDialAt 防广播回声。
            else -> dialedThisConnection.remove(key)
        }
    }

    private fun coolingDown(key: String, nowMillis: Long): String? {
        val last = lastDialAt[key] ?: return null
        return if (nowMillis - last < cooldownMillis) "${(nowMillis - last) / 1000} 秒前刚拨过这台手机" else null
    }

    private fun key(address: String) = address.trim().uppercase()

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
    }
}
