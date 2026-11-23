package io.github.gcbond.openhc.startup

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 开机静默连接：开机分流、后台等待建立没有、什么时候把界面带到前台。 */
class SilentConnectPolicyTest {

    private val booleans = listOf(false, true)

    @Test
    fun silentOffKeepsTheOldBootPathForEveryInput() {
        for (cable in booleans) for (identity in booleans) for (vpn in booleans) for (permissions in booleans) for (hotspot in booleans) {
            assertEquals(
                BootRoute.OPEN_UI,
                SilentConnectPolicy.route(
                    silent = false, waitForCable = cable, identityLoaded = identity, vpnGranted = vpn,
                    wirelessPermissionsGranted = permissions, hotspotSupported = hotspot,
                ),
                "等插线=$cable 身份参数=$identity VPN=$vpn 权限=$permissions 本地热点=$hotspot",
            )
        }
    }

    @Test
    fun withoutLocalHotspotSilentWaitsForTheCable() {
        // 安卓 8.0 以下没有本地热点、用不了无线：不管无线设置怎样，都在后台等插线。
        assertEquals(BootRoute.SILENT_WIRED, route(waitForCable = false, hotspotSupported = false))
        assertEquals(BootRoute.SILENT_WIRED, route(waitForCable = false, hotspotSupported = false, wirelessPermissionsGranted = false))
        assertEquals(BootRoute.UI_FOR_VPN, route(waitForCable = false, hotspotSupported = false, vpnGranted = false), "照有线：缺 VPN 授权才打开界面")
        assertEquals(BootRoute.SILENT_WIRED, SilentConnectPolicy.wiredRoute(vpnGranted = true))
        assertEquals(BootRoute.UI_FOR_VPN, SilentConnectPolicy.wiredRoute(vpnGranted = false))
    }

    @Test
    fun hotspotUnsupportedHoldSwitchesToTheCableWithoutUi() {
        val check = SilentConnectPolicy.check(BootRoute.SILENT_WIRELESS, snapshot(hold = AutoHold.HOTSPOT_UNSUPPORTED), deadlineReached = false)
        assertEquals(SilentCheck.WIRED_INSTEAD, check)
        assertEquals(
            SilentCheck.WIRED_INSTEAD,
            SilentConnectPolicy.check(BootRoute.SILENT_WIRELESS, snapshot(hold = AutoHold.HOTSPOT_UNSUPPORTED), deadlineReached = true),
        )
        assertNull(SilentConnectPolicy.outcome(BootRoute.SILENT_WIRELESS, check), "改走等插线以后按有线记结果")
    }

    @Test
    fun silentWaitsInTheBackgroundWhenNothingIsMissing() {
        assertEquals(BootRoute.SILENT_WIRED, route(waitForCable = true))
        assertEquals(BootRoute.SILENT_WIRELESS, route(waitForCable = false))
        assertFalse(BootRoute.SILENT_WIRED.opensUi)
        assertFalse(BootRoute.SILENT_WIRELESS.opensUi)
    }

    @Test
    fun whatOnlyTheUiCanFixOpensTheUi() {
        assertEquals(BootRoute.UI_FOR_IDENTITY, route(waitForCable = true, identityLoaded = false))
        assertEquals(BootRoute.UI_FOR_IDENTITY, route(waitForCable = false, identityLoaded = false))
        assertEquals(BootRoute.UI_FOR_VPN, route(waitForCable = true, vpnGranted = false), "VPN 授权框只能从界面弹")
        assertEquals(BootRoute.UI_FOR_PERMISSIONS, route(waitForCable = false, wirelessPermissionsGranted = false))
        for (r in listOf(BootRoute.OPEN_UI, BootRoute.UI_FOR_IDENTITY, BootRoute.UI_FOR_VPN, BootRoute.UI_FOR_PERMISSIONS)) {
            assertTrue(r.opensUi, "$r")
        }
    }

    @Test
    fun eachSideOnlyNeedsItsOwnPrerequisites() {
        assertEquals(BootRoute.SILENT_WIRELESS, route(waitForCable = false, vpnGranted = false), "等无线回连用不到 VPN")
        assertEquals(BootRoute.SILENT_WIRED, route(waitForCable = true, wirelessPermissionsGranted = false), "等插线用不到无线权限")
    }

    @Test
    fun wiredWaitIsSettledOnceTheServiceIsInForeground() {
        val route = BootRoute.SILENT_WIRED
        assertEquals(SilentCheck.PENDING, SilentConnectPolicy.check(route, snapshot(), deadlineReached = false))
        assertEquals(SilentCheck.WAITING, SilentConnectPolicy.check(route, snapshot(wiredWaiting = true), deadlineReached = false))
        assertEquals(SilentCheck.WAITING, SilentConnectPolicy.check(route, snapshot(wiredWaiting = true), deadlineReached = true))
        assertEquals(SilentCheck.OPEN_UI, SilentConnectPolicy.check(route, snapshot(wiredBlocked = true), deadlineReached = false), "开始不了：首页说明原因")
        assertEquals(SilentCheck.OPEN_UI, SilentConnectPolicy.check(route, snapshot(), deadlineReached = true), "到点服务还没进前台")
        assertEquals(
            SilentCheck.PENDING,
            SilentConnectPolicy.check(route, snapshot(wirelessWaiting = true), deadlineReached = false),
            "等插线这条路只看有线",
        )
    }

    @Test
    fun wirelessStandsByWhenAutoConnectIsReady() {
        val route = BootRoute.SILENT_WIRELESS
        assertEquals(SilentCheck.PENDING, SilentConnectPolicy.check(route, snapshot(), deadlineReached = false), "还没判断过")
        assertEquals(SilentCheck.OPEN_UI, SilentConnectPolicy.check(route, snapshot(), deadlineReached = true))
        // 都准备好了：待命，等手机蓝牙连上时系统把本应用叫起来。
        assertEquals(SilentCheck.STANDBY, SilentConnectPolicy.check(route, snapshot(hold = AutoHold.READY), deadlineReached = false))
        // 车机开机时蓝牙常比应用晚：先接着看，到点就待命。
        assertEquals(SilentCheck.PENDING, SilentConnectPolicy.check(route, snapshot(hold = AutoHold.BLUETOOTH_OFF), deadlineReached = false))
        assertEquals(SilentCheck.STANDBY, SilentConnectPolicy.check(route, snapshot(hold = AutoHold.BLUETOOTH_OFF), deadlineReached = true))
        // 只有界面能补上的：打开界面，由首页说明。
        for (hold in listOf(
            AutoHold.NO_KNOWN_PHONE, AutoHold.OVERLAY_MISSING, AutoHold.DISABLED, AutoHold.BLUETOOTH_ADDRESS_MISSING,
            AutoHold.PERMISSIONS_MISSING, AutoHold.IDENTITY_MISSING, AutoHold.START_FAILED,
        )) {
            assertEquals(SilentCheck.OPEN_UI, SilentConnectPolicy.check(route, snapshot(hold = hold), deadlineReached = false), "$hold")
        }
    }

    @Test
    fun uiRoutesNeverWaitInTheBackground() {
        for (r in listOf(BootRoute.OPEN_UI, BootRoute.UI_FOR_IDENTITY, BootRoute.UI_FOR_VPN, BootRoute.UI_FOR_PERMISSIONS)) {
            assertEquals(SilentCheck.OPEN_UI, SilentConnectPolicy.check(r, snapshot(wiredWaiting = true, wirelessWaiting = true), false), "$r")
        }
    }

    @Test
    fun outcomeNamesWhatIsWaiting() {
        assertEquals(BootOutcome.SILENT_WIRED, SilentConnectPolicy.outcome(BootRoute.SILENT_WIRED, SilentCheck.WAITING))
        assertEquals(BootOutcome.SILENT_WIRELESS, SilentConnectPolicy.outcome(BootRoute.SILENT_WIRELESS, SilentCheck.WAITING))
        assertEquals(BootOutcome.SILENT_STANDBY, SilentConnectPolicy.outcome(BootRoute.SILENT_WIRELESS, SilentCheck.STANDBY))
        assertNull(SilentConnectPolicy.outcome(BootRoute.SILENT_WIRED, SilentCheck.PENDING))
        assertNull(SilentConnectPolicy.outcome(BootRoute.SILENT_WIRED, SilentCheck.OPEN_UI), "打开界面那条路自己记结果")
    }

    // ---------------------------------------------------------------- 带到前台

    @Test
    fun surfacesWhenProjectionStartsInTheBackground() {
        val tracker = SilentSurfaceTracker()
        assertNull(tracker.see(wireless = WirelessPhase.CONNECTING, wirelessAttempt = 1))
        assertEquals(
            SilentSurfaceTracker.Surface(wireless = true, attempt = 1, what = SilentSurfaceTracker.What.PROJECTION),
            tracker.see(wireless = WirelessPhase.PROJECTING, wirelessAttempt = 1),
        )
        assertNull(tracker.see(wireless = WirelessPhase.PROJECTING, wirelessAttempt = 1), "同一次连接只带一次")
        assertEquals(
            SilentSurfaceTracker.Surface(wireless = false, attempt = 3, what = SilentSurfaceTracker.What.PROJECTION),
            tracker.see(wired = Phase.PROJECTING, wiredAttempt = 3),
        )
    }

    @Test
    fun nextConnectionSurfacesAgain() {
        val tracker = SilentSurfaceTracker()
        assertTrue(tracker.see(wireless = WirelessPhase.PROJECTING, wirelessAttempt = 1) != null)
        assertNull(tracker.see(wireless = WirelessPhase.IDLE, wirelessAttempt = 1))
        assertEquals(
            SilentSurfaceTracker.Surface(wireless = true, attempt = 2, what = SilentSurfaceTracker.What.PROJECTION),
            tracker.see(wireless = WirelessPhase.PROJECTING, wirelessAttempt = 2),
            "手机断开后回连上来是新的一次",
        )
    }

    @Test
    fun aVisibleUiIsLeftAloneEvenAfterTheDriverLeaves() {
        val tracker = SilentSurfaceTracker()
        assertNull(tracker.see(wired = Phase.PROJECTING, wiredAttempt = 1, uiVisible = true), "界面在前台时自己切到投屏页")
        assertNull(tracker.see(wired = Phase.PROJECTING, wiredAttempt = 1, uiVisible = false), "车主按了主页键离开：同一次连接不再拉回来")
    }

    @Test
    fun silentOffNeverSurfacesAndIsAskedOnlyWhenNeeded() {
        val tracker = SilentSurfaceTracker()
        var asked = 0
        val off = { asked++; false }
        assertNull(tracker.see(wired = Phase.WAITING_FOR_PHONE, wiredAttempt = 1, silent = off))
        assertEquals(0, asked, "没有要带的事，不读设置")
        assertNull(tracker.see(wired = Phase.PROJECTING, wiredAttempt = 1, uiVisible = true, silent = off))
        assertEquals(0, asked, "界面在前台，不读设置")
        assertNull(tracker.see(wired = Phase.PROJECTING, wiredAttempt = 2, silent = off))
        assertEquals(1, asked)
        assertNull(tracker.see(wired = Phase.PROJECTING, wiredAttempt = 2, silent = { true }), "静默关着时看过的也算看过")
    }

    @Test
    fun screensTheDriverMustSeeAlsoSurface() {
        val tracker = SilentSurfaceTracker()
        assertEquals(SilentSurfaceTracker.What.USB_DEBUGGING, tracker.see(wired = Phase.USB_DEBUGGING_ON, wiredAttempt = 1)?.what)
        assertEquals(SilentSurfaceTracker.What.PIN, tracker.see(wired = Phase.PAIRING, wiredAttempt = 1)?.what, "首次配对的连接码")
        assertNull(tracker.see(wired = Phase.CONFIRM_ON_PHONE, wiredAttempt = 1), "在手机上点「连接」不用看车机")
        assertNull(tracker.see(wired = Phase.WAITING_USB_PERMISSION, wiredAttempt = 1), "授权框是系统自己弹的")
        assertEquals(SilentSurfaceTracker.What.PROJECTION, tracker.see(wired = Phase.PROJECTING, wiredAttempt = 1)?.what)
        assertEquals(
            SilentSurfaceTracker.What.PIN,
            tracker.see(wireless = WirelessPhase.CONNECTING, wirelessStep = WirelessStep.AUTH_PAIRING, wirelessAttempt = 1)?.what,
            "没配对过的手机靠广播找到车机，要输车机上的连接码",
        )
        assertNull(tracker.see(wireless = WirelessPhase.CONNECTING, wirelessStep = WirelessStep.AUTH_TRUSTED, wirelessAttempt = 2))
    }

    private fun route(
        waitForCable: Boolean,
        identityLoaded: Boolean = true,
        vpnGranted: Boolean = true,
        wirelessPermissionsGranted: Boolean = true,
        hotspotSupported: Boolean = true,
    ) = SilentConnectPolicy.route(true, waitForCable, identityLoaded, vpnGranted, wirelessPermissionsGranted, hotspotSupported)

    private fun snapshot(
        wiredWaiting: Boolean = false,
        wiredBlocked: Boolean = false,
        wirelessWaiting: Boolean = false,
        hold: AutoHold? = null,
    ) = SilentSnapshot(wiredWaiting, wiredBlocked, wirelessWaiting, hold)

    private fun SilentSurfaceTracker.see(
        wired: Phase = Phase.IDLE,
        wiredAttempt: Int = 0,
        wireless: WirelessPhase = WirelessPhase.IDLE,
        wirelessStep: WirelessStep? = null,
        wirelessAttempt: Int = 0,
        uiVisible: Boolean = false,
        silent: () -> Boolean = { true },
    ) = observe(uiVisible, wired, wiredAttempt, wireless, wirelessStep, wirelessAttempt, silent)
}
