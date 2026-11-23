package io.github.gcbond.openhc.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 开机自动打开：开机广播的判定、打开界面后的结论、记录的存取。 */
class BootStartPolicyTest {

    @Test
    fun onlyTheBootCompletedBroadcastCounts() {
        assertTrue(BootStartPolicy.isBootBroadcast("android.intent.action.BOOT_COMPLETED"))
        for (other in listOf(
            null,
            "",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
            BootStartPolicy.ACTION_BOOT_LAUNCH,
        )) {
            assertFalse(BootStartPolicy.isBootBroadcast(other), "$other")
        }
    }

    @Test
    fun theUiStartsWaitingOnlyForAFreshBootLaunch() {
        assertTrue(BootStartPolicy.isBootLaunch(BootStartPolicy.ACTION_BOOT_LAUNCH, fromHistory = false))
        assertFalse(
            BootStartPolicy.isBootLaunch(BootStartPolicy.ACTION_BOOT_LAUNCH, fromHistory = true),
            "从最近任务重开时系统把开机那次的 Intent 再给一遍，不能再开始一次连接",
        )
        assertFalse(BootStartPolicy.isBootLaunch("android.intent.action.MAIN", fromHistory = false), "点图标打开")
        assertFalse(BootStartPolicy.isBootLaunch("android.hardware.usb.action.USB_DEVICE_ATTACHED", fromHistory = false))
        assertFalse(BootStartPolicy.isBootLaunch(null, fromHistory = false))
    }

    @Test
    fun bootWaitsForTheCableUnlessPluggingStartsWiredByItself() {
        fun wait(plugged: Boolean, usbAuto: Boolean, wireless: Boolean) =
            BootStartPolicy.waitForCable(phonePlugged = plugged, usbAutoConnect = usbAuto, wirelessWanted = wireless)
        assertTrue(wait(plugged = true, usbAuto = true, wireless = true), "插着手机：等插线")
        assertTrue(wait(plugged = false, usbAuto = true, wireless = false), "不做无线：等插线")
        assertFalse(wait(plugged = false, usbAuto = true, wireless = true), "插线时系统会拉起本应用：不用等")
        // 没开「插线时不再弹窗」就等插线；手机蓝牙连上时无线会请有线让出，所以和无线不冲突。
        assertTrue(wait(plugged = false, usbAuto = false, wireless = true))
    }

    @Test
    fun wirelessIsWantedOnlyWhenAutoConnectIsOnAndNotWiredOnly() {
        assertTrue(BootStartPolicy.wirelessWanted(wiredOnly = false, connectOnBluetooth = true))
        assertFalse(BootStartPolicy.wirelessWanted(wiredOnly = false, connectOnBluetooth = false))
        assertFalse(BootStartPolicy.wirelessWanted(wiredOnly = true, connectOnBluetooth = true), "只用数据线连接")
    }

    @Test
    fun overlayPermissionIsNeededFromAndroidTen() {
        for (sdk in listOf(23, 26, 28)) assertFalse(BootStartPolicy.overlayRequired(sdk), "安卓 API $sdk")
        for (sdk in listOf(29, 31, 34, 36)) assertTrue(BootStartPolicy.overlayRequired(sdk), "安卓 API $sdk")
    }

    @Test
    fun reopensOnceWhenTheUiIsNotInFront() {
        assertTrue(BootStartPolicy.shouldRetry(inFront = false, retried = false), "没出来，或者出来后被盖住了")
        assertFalse(BootStartPolicy.shouldRetry(inFront = false, retried = true), "只补开一次")
        assertFalse(BootStartPolicy.shouldRetry(inFront = true, retried = false))
    }

    @Test
    fun outcomeExplainsWhyTheUiIsNotInFront() {
        assertEquals(BootOutcome.OPENED, BootStartPolicy.outcome(inFront = true, everShown = true, sdkInt = 29, overlayGranted = true))
        assertEquals(
            BootOutcome.OPENED,
            BootStartPolicy.outcome(inFront = true, everShown = true, sdkInt = 29, overlayGranted = false),
            "有的系统不拦后台打开界面：出来了就算打开",
        )
        assertEquals(BootOutcome.COVERED, BootStartPolicy.outcome(inFront = false, everShown = true, sdkInt = 29, overlayGranted = true))
        assertEquals(BootOutcome.BLOCKED_NO_OVERLAY, BootStartPolicy.outcome(inFront = false, everShown = false, sdkInt = 29, overlayGranted = false))
        assertEquals(BootOutcome.NOT_SHOWN, BootStartPolicy.outcome(inFront = false, everShown = false, sdkInt = 29, overlayGranted = true))
        assertEquals(
            BootOutcome.NOT_SHOWN,
            BootStartPolicy.outcome(inFront = false, everShown = false, sdkInt = 28, overlayGranted = false),
            "安卓 10 以前不需要悬浮窗权限，没出来另有原因",
        )
    }

    @Test
    fun resentBroadcastIsToldApartByUptime() {
        assertTrue(BootStartPolicy.isFreshBoot(40_000L), "冷启动后几十秒送到")
        assertTrue(BootStartPolicy.isFreshBoot(BootStartPolicy.FRESH_BOOT_LIMIT_MS - 1))
        assertFalse(BootStartPolicy.isFreshBoot(BootStartPolicy.FRESH_BOOT_LIMIT_MS))
        assertFalse(BootStartPolicy.isFreshBoot(3 * 3_600_000L), "熄火后再点火：系统一直在跑，开机广播是重发的")
    }

    @Test
    fun recordRoundTrips() {
        for (outcome in BootOutcome.entries) {
            val record = BootRecord(atMillis = 1_700_000_000_000L, sinceBootMillis = 12_345L, outcome = outcome, installStamp = 1_600_000_000_000L)
            assertEquals(record, BootRecord.decode(record.encode()))
        }
        val zeros = BootRecord(atMillis = 0L, sinceBootMillis = 0L, outcome = BootOutcome.PENDING, installStamp = 0L)
        assertEquals(zeros, BootRecord.decode(zeros.encode()), "读不到安装时间时记 0")
    }

    @Test
    fun malformedRecordsAreIgnored() {
        for (bad in listOf(
            null,
            "",
            "1,2",
            "1,2,OPENED",
            "1,2,OPENED,4,5",
            "a,2,OPENED,4",
            "1,b,OPENED,4",
            "1,2,OPENED,c",
            "1,2,opened,4",
            "1,2,UNKNOWN,4",
            "-1,2,OPENED,4",
            "1,-2,OPENED,4",
            "1,2,OPENED,-4",
            "1,2,,4",
        )) {
            assertNull(BootRecord.decode(bad), "$bad")
        }
    }

    @Test
    fun bootSeenSinceInstallComparesInstallStampsNotClocks() {
        val installedAt = 1_700_000_000_000L
        assertFalse(BootStartPolicy.bootSeenSinceInstall(null, installedAt), "从没收到过")
        assertFalse(
            BootStartPolicy.bootSeenSinceInstall(BootRecord(installedAt + 60_000L, 30_000L, BootOutcome.OPENED, installedAt - 86_400_000L), installedAt),
            "更新之前收到的不算：有的系统更新应用后会重新禁止自启动",
        )
        assertTrue(BootStartPolicy.bootSeenSinceInstall(BootRecord(installedAt + 60_000L, 30_000L, BootOutcome.PENDING, installedAt), installedAt))
        assertTrue(
            BootStartPolicy.bootSeenSinceInstall(BootRecord(946_684_800_000L, 30_000L, BootOutcome.OPENED, installedAt), installedAt),
            "开机时时钟还没校准（时间早于安装时间）也算收到过",
        )
    }
}
