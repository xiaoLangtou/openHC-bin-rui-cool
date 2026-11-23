package io.github.gcbond.openhc.startup

/**
 * 开机自动打开 OpenHC 的判定。纯逻辑，由 JVM 单元测试覆盖；接线见 [BootStartReceiver]、[BootStart]。
 *
 * 做法：收到开机广播就打开界面（等于车主点了图标），界面再当作点了「开始」，进入等插线。这条路不在后台起服务，
 * 前台服务由界面打开以后发起。
 *
 * 设置「开机静默连接」开着时不打开界面，改在后台起前台服务等待（开机广播是系统允许后台应用起前台服务的场合之一），
 * 判定见 [SilentConnectPolicy]；静默关着时与上面完全相同。
 */
object BootStartPolicy {
    /**
     * 系统开机广播。有的车机熄火时并不真正关机，只是把应用进程都杀掉，点火时再把这条广播发一遍，所以冷启动和
     * 熄火后再点火走同一个入口。
     */
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    /** 收到开机广播后打开界面用的动作，界面据此开始有线连接（等插线）。 */
    const val ACTION_BOOT_LAUNCH = "io.github.gcbond.openhc.action.BOOT_LAUNCH"

    /**
     * 开机静默连接时，手机连上了（或要车主看车机屏幕）而界面不在前台，用这个动作把界面带到前台。
     * 界面收到它不开始任何连接，只是露出来：投屏页、连接码由界面按连接状态自己切过去。
     */
    const val ACTION_SHOW_PROJECTION = "io.github.gcbond.openhc.action.SHOW_PROJECTION"

    /** 安卓 10（API 29）起后台应用不能打开界面，车主能给的豁免只有悬浮窗权限。 */
    const val OVERLAY_REQUIRED_FROM_SDK = 29

    /** 收到开机广播时系统已经运行超过这么久，多半是熄火后再点火、系统重发的。 */
    const val FRESH_BOOT_LIMIT_MS = 5 * 60_000L

    /**
     * 打开界面后过这么久看它在不在前台：不在（没出来，或者出来后被车机自己的桌面等界面盖住）就再开一次，
     * 再过这么久记下结果。
     */
    const val CHECK_DELAY_MS = 4_000L

    fun isBootBroadcast(action: String?): Boolean = action == ACTION_BOOT_COMPLETED

    /**
     * 界面收到的是开机打开。[fromHistory]：车主从「最近任务」重开时，系统会把最初打开这个任务的 Intent 再给一遍，
     * 那不是开机，不能再开始一次连接。
     */
    fun isBootLaunch(action: String?, fromHistory: Boolean): Boolean = action == ACTION_BOOT_LAUNCH && !fromHistory

    /**
     * 开机打开界面以后，要不要像点了「开始」一样进入等插线。
     *
     * 无线平时不占着什么（「自动连接手机」只在手机蓝牙连上、或界面回到前台时拨一次），有线在等插线时，
     * 用无线连成功过的手机蓝牙一连上，无线就请有线停下、改走无线；所以等不等插线只看有线自己：
     * - 车机上已经插着手机：进入等插线（插着的手机不会再有一次「设备接入」把有线带起来）；
     * - 不做无线（[wirelessWanted] 为假）：进入等插线；
     * - 开着「插线时不再弹窗」：不进入，插线时系统会拉起本应用，照样开始有线；
     * - 没开：插线不会自己开始有线，进入等插线。
     */
    fun waitForCable(phonePlugged: Boolean, usbAutoConnect: Boolean, wirelessWanted: Boolean): Boolean =
        phonePlugged || !wirelessWanted || !usbAutoConnect

    /** 开机后无线要不要自动连手机，只看设置：没开「只用数据线连接」，并且开着「自动连接手机」。 */
    fun wirelessWanted(wiredOnly: Boolean, connectOnBluetooth: Boolean): Boolean = !wiredOnly && connectOnBluetooth

    fun overlayRequired(sdkInt: Int): Boolean = sdkInt >= OVERLAY_REQUIRED_FROM_SDK

    fun isFreshBoot(sinceBootMillis: Long): Boolean = sinceBootMillis < FRESH_BOOT_LIMIT_MS

    /** 检查时界面不在前台就再开一次，只补一次。 */
    fun shouldRetry(inFront: Boolean, retried: Boolean): Boolean = !inFront && !retried

    /** 检查完的结论。[inFront]：此刻界面在前台；[everShown]：界面到过前台。 */
    fun outcome(inFront: Boolean, everShown: Boolean, sdkInt: Int, overlayGranted: Boolean): BootOutcome = when {
        inFront -> BootOutcome.OPENED
        everShown -> BootOutcome.COVERED
        overlayRequired(sdkInt) && !overlayGranted -> BootOutcome.BLOCKED_NO_OVERLAY
        else -> BootOutcome.NOT_SHOWN
    }

    /**
     * 本次安装或更新以后收到过开机广播。有的车机系统默认禁止应用开机自启动、每次更新应用还会重新禁止，
     * 应用自己查不到这个设置，只能按「更新以后有没有收到过开机广播」推断。
     *
     * 按记录里收到广播时的安装时间是否等于现在的安装时间来判断，不拿收到广播时的墙上时间去比：
     * 不少车机开机时还没校准时钟，那一刻的时间可能早于安装时间。
     */
    fun bootSeenSinceInstall(last: BootRecord?, installStamp: Long): Boolean =
        last != null && last.installStamp == installStamp
}

/** 开机打开界面的结果。 */
enum class BootOutcome {
    /** 收到了开机广播、请求了打开界面，还没有结论（之后进程没了就停在这里）。 */
    PENDING,

    /** 界面到了前台。 */
    OPENED,

    /** 界面打开过，但没留在前台（被车机自己的桌面等界面盖住了）。 */
    COVERED,

    /** 界面没出来：安卓 10 起没有悬浮窗权限，系统不让后台应用打开界面。 */
    BLOCKED_NO_OVERLAY,

    /** 界面一直没出来，原因不明（多半被系统拦了）。 */
    NOT_SHOWN,

    /** 打开界面的调用直接出错。 */
    FAILED,

    /** 开机静默连接：在后台等插线（没打开界面）。 */
    SILENT_WIRED,

    /** 开机静默连接：在后台等已配对的手机无线回连（没打开界面）。 */
    SILENT_WIRELESS,

    /** 开机静默连接：在后台待命，蓝牙打开、或手机蓝牙连上以后再开始等（没打开界面）。 */
    SILENT_STANDBY,
}

/**
 * 最近一次收到开机广播：墙上时间（开机时时钟可能还没校准，只用来显示）、当时系统已运行多久（毫秒）、结果，
 * 以及当时本应用的安装 / 更新时间（[BootStartPolicy.bootSeenSinceInstall] 用）。存进设置给设置页看。
 */
data class BootRecord(val atMillis: Long, val sinceBootMillis: Long, val outcome: BootOutcome, val installStamp: Long) {

    fun encode(): String = "$atMillis,$sinceBootMillis,${outcome.name},$installStamp"

    companion object {
        /** 解析 [encode] 的结果；格式不对返回 null。 */
        fun decode(text: String?): BootRecord? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 4) return null
            val at = parts[0].toLongOrNull() ?: return null
            val since = parts[1].toLongOrNull() ?: return null
            val stamp = parts[3].toLongOrNull() ?: return null
            if (at < 0 || since < 0 || stamp < 0) return null
            val outcome = BootOutcome.entries.firstOrNull { it.name == parts[2] } ?: return null
            return BootRecord(at, since, outcome, stamp)
        }
    }
}
