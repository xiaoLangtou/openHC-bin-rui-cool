package io.github.gcbond.openhc.startup

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep

// 设置「开机静默连接」（默认关）的判定：开机广播进来以后走哪条路、后台等待建立起来没有、什么时候把界面带到前台。
// 本文件是纯逻辑，不引用安卓类，由 JVM 单元测试覆盖；接线见 SilentConnect.kt。
//
// 静默开着时开机不打开界面：有线在后台像点了「开始」一样等插线；无线在后台待命——「自动连接手机」要等用无线连成功过的
// 手机蓝牙连上车机时（系统把本应用叫起来）才拨，拨之前会把界面带到前台（开热点要界面在前台）。等插线期间前台服务的通知一直在。
// 手机连上开始投屏、或者要车主看车机屏幕（首次配对的连接码、关掉手机 USB 调试的提示）时，才把界面带到前台。
// 开机广播是系统允许后台应用起前台服务的场合之一。

/** 收到开机广播以后走哪条路。 */
enum class BootRoute {
    /** 静默关着：照旧打开界面。 */
    OPEN_UI,

    /** 静默开着，但安装包里的车机固定参数不能用：打开界面（首页有提示）。 */
    UI_FOR_IDENTITY,

    /** 静默开着、该等插线，但还没同意 VPN 授权：打开界面（授权框只能从界面弹）。 */
    UI_FOR_VPN,

    /** 静默开着、该等手机无线回连，但缺无线要的运行时权限：打开界面。 */
    UI_FOR_PERMISSIONS,

    /** 在后台等插线。 */
    SILENT_WIRED,

    /** 无线在后台待命（等用无线连成功过的手机蓝牙连上车机）。 */
    SILENT_WIRELESS,
    ;

    /** 这条路要打开界面。 */
    val opensUi: Boolean get() = this != SILENT_WIRED && this != SILENT_WIRELESS
}

/** 静默开机后，后台等待现在怎样了。 */
enum class SilentCheck {
    /** 还没定，接着看。 */
    PENDING,

    /** 在后台等着了：前台服务已经进了前台。 */
    WAITING,

    /**
     * 待命：现在不用做什么，后面会自己开始——用无线连成功过的手机蓝牙连上车机时，系统会把本应用叫起来去拨；
     * 车机蓝牙还没打开的，打开以后手机才会连上来。
     */
    STANDBY,

    /** 这台车机用不了无线（系统没有本地热点）：改在后台等插线，不打开界面。 */
    WIRED_INSTEAD,

    /** 后台开始不了（缺条件、前台服务起不来）：改为打开界面，由首页说明原因。 */
    OPEN_UI,
}

/** 看后台等待时的一份快照。 */
class SilentSnapshot(
    /** 有线要求了前台、服务也真的进了前台。 */
    val wiredWaiting: Boolean,
    /** 有线开始不了，回到了空闲并带着原因。 */
    val wiredBlocked: Boolean,
    /** 无线要求了前台、服务也真的进了前台。 */
    val wirelessWaiting: Boolean,
    /** 「自动连接手机」能不能起作用；还没判断过为 null。 */
    val wirelessHold: AutoHold?,
)

object SilentConnectPolicy {

    /** 握着开机广播等后台等待建立的上限（毫秒）。到点还没建立的按 [check] 的规则收尾。 */
    const val SETTLE_LIMIT_MS = 5_000L

    /** 握着开机广播期间每隔多久看一次。 */
    const val POLL_MS = 250L

    /**
     * 开机走哪条路。[silent] 关着时一律照旧打开界面（与没有这个设置时完全相同）。
     *
     * 先等插线还是先等无线回连，沿用打开界面时的规则（[waitForCable]，见 [BootStartPolicy.waitForCable]）；
     * 系统没有本地热点（[hotspotSupported] 为假，安卓 8.0 以下）时用不了无线，照等插线走。
     * 凡是只有界面才补得上的前提（身份参数、VPN 授权、无线运行时权限）缺了，就打开界面，不在后台什么都不做。
     */
    fun route(
        silent: Boolean,
        waitForCable: Boolean,
        identityLoaded: Boolean,
        vpnGranted: Boolean,
        wirelessPermissionsGranted: Boolean,
        hotspotSupported: Boolean,
    ): BootRoute = when {
        !silent -> BootRoute.OPEN_UI
        !identityLoaded -> BootRoute.UI_FOR_IDENTITY
        waitForCable || !hotspotSupported -> wiredRoute(vpnGranted)
        !wirelessPermissionsGranted -> BootRoute.UI_FOR_PERMISSIONS
        else -> BootRoute.SILENT_WIRELESS
    }

    /** 改走等插线：有 VPN 授权就在后台等，没有就打开界面（授权框只能从界面弹）。 */
    fun wiredRoute(vpnGranted: Boolean): BootRoute = if (vpnGranted) BootRoute.SILENT_WIRED else BootRoute.UI_FOR_VPN

    /**
     * 后台等待建立起来没有。[deadlineReached]：已经握了 [SETTLE_LIMIT_MS]。
     *
     * - 等插线：服务进了前台算等上了；开始不了、或到点还没进前台，打开界面。
     * - 无线：「自动连接手机」都准备好了就待命；蓝牙还没开的，到点就待命（打开以后手机才会连上来）；
     *   系统没有本地热点（用不了无线）的，改在后台等插线、不打开界面；别的原因（还没有用无线连成功过的手机、
     *   缺悬浮窗权限、读不到车机蓝牙地址……）打开界面，由首页说明。
     */
    fun check(route: BootRoute, s: SilentSnapshot, deadlineReached: Boolean): SilentCheck = when (route) {
        BootRoute.SILENT_WIRED -> when {
            s.wiredWaiting -> SilentCheck.WAITING
            s.wiredBlocked || deadlineReached -> SilentCheck.OPEN_UI
            else -> SilentCheck.PENDING
        }
        BootRoute.SILENT_WIRELESS -> when {
            s.wirelessWaiting -> SilentCheck.WAITING
            s.wirelessHold == null -> if (deadlineReached) SilentCheck.OPEN_UI else SilentCheck.PENDING
            s.wirelessHold == AutoHold.READY -> SilentCheck.STANDBY
            s.wirelessHold == AutoHold.BLUETOOTH_OFF -> if (deadlineReached) SilentCheck.STANDBY else SilentCheck.PENDING
            s.wirelessHold == AutoHold.HOTSPOT_UNSUPPORTED -> SilentCheck.WIRED_INSTEAD
            else -> SilentCheck.OPEN_UI
        }
        else -> SilentCheck.OPEN_UI
    }

    /** 等上了以后记下的开机结果。 */
    fun outcome(route: BootRoute, check: SilentCheck): BootOutcome? = when (check) {
        SilentCheck.WAITING -> if (route == BootRoute.SILENT_WIRED) BootOutcome.SILENT_WIRED else BootOutcome.SILENT_WIRELESS
        SilentCheck.STANDBY -> BootOutcome.SILENT_STANDBY
        SilentCheck.PENDING, SilentCheck.OPEN_UI, SilentCheck.WIRED_INSTEAD -> null
    }
}

/**
 * 静默开着时什么时候把界面带到前台：手机连上开始投屏，或者要车主看车机屏幕（有线首次配对的连接码、
 * 关掉手机 USB 调试的提示；无线里没配对过的手机靠广播找到车机、要输车机上的连接码）。
 *
 * 同一次连接里同一件事只看一次：第一次看到时界面已经在前台（它自己会切页面），之后车主离开了也不再带；
 * 静默关着时看到的也算看过。只在一个线程上调用。
 */
class SilentSurfaceTracker {

    enum class What {
        /** 开始投屏。 */
        PROJECTION,

        /** 车机上显示了连接码，要车主在手机上输。 */
        PIN,

        /** 插着的手机开着 USB 调试，要车主关掉。 */
        USB_DEBUGGING,
    }

    data class Surface(val wireless: Boolean, val attempt: Int, val what: What)

    private val seen = HashSet<Surface>()
    private var lastWiredAttempt = 0
    private var lastWirelessAttempt = 0

    /**
     * 有线、无线的状态变了时调用。返回要带到前台的那件事；不用带返回 null。
     * [silent] 只在真要带的那一刻才问（读系统设置有开销）。
     */
    fun observe(
        uiVisible: Boolean,
        wiredPhase: Phase,
        wiredAttempt: Int,
        wirelessPhase: WirelessPhase,
        wirelessStep: WirelessStep?,
        wirelessAttempt: Int,
        silent: () -> Boolean,
    ): Surface? {
        forgetOld(wiredAttempt, wirelessAttempt)
        val fresh = listOfNotNull(
            wirelessCandidate(wirelessPhase, wirelessStep)?.let { Surface(wireless = true, attempt = wirelessAttempt, what = it) },
            wiredCandidate(wiredPhase)?.let { Surface(wireless = false, attempt = wiredAttempt, what = it) },
        ).filter { seen.add(it) }
        if (fresh.isEmpty() || uiVisible) return null
        return if (silent()) fresh.first() else null
    }

    private fun wiredCandidate(phase: Phase): What? = when (phase) {
        Phase.PROJECTING -> What.PROJECTION
        Phase.PAIRING -> What.PIN
        Phase.USB_DEBUGGING_ON -> What.USB_DEBUGGING
        else -> null
    }

    private fun wirelessCandidate(phase: WirelessPhase, step: WirelessStep?): What? = when {
        phase == WirelessPhase.PROJECTING -> What.PROJECTION
        phase == WirelessPhase.CONNECTING && step == WirelessStep.AUTH_PAIRING -> What.PIN
        else -> null
    }

    /** 换了一次连接就忘掉上一次看过的，集合不会一直长。 */
    private fun forgetOld(wired: Int, wireless: Int) {
        if (wired != lastWiredAttempt) {
            seen.removeAll { !it.wireless }
            lastWiredAttempt = wired
        }
        if (wireless != lastWirelessAttempt) {
            seen.removeAll { it.wireless }
            lastWirelessAttempt = wireless
        }
    }
}
