package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessEvent
import io.github.gcbond.openhc.wireless.ReconnectTriggers.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 「蓝牙连上自动连接」能不能起作用、什么时候拨谁，以及无线挡不挡有线。 */
class ReconnectPolicyTest {

    private val phone = "02:11:22:33:44:55"
    private val other = "02:66:77:88:99:AA"
    private val known = setOf(phone)

    private fun hold(
        enabled: Boolean = true,
        wiredOnly: Boolean = false,
        wiredActive: Boolean = false,
        hotspotSupported: Boolean = true,
        identityLoaded: Boolean = true,
        bluetoothAddressKnown: Boolean = true,
        bluetoothOn: Boolean = true,
        permissionsGranted: Boolean = true,
        knownPhones: Int = 1,
        overlayGranted: Boolean = true,
    ) = ReconnectPolicy.hold(
        ReconnectPolicy.Conditions(
            enabled = enabled,
            wiredOnly = wiredOnly,
            wiredActive = wiredActive,
            hotspotSupported = hotspotSupported,
            identityLoaded = identityLoaded,
            bluetoothAddressKnown = bluetoothAddressKnown,
            bluetoothOn = bluetoothOn,
            permissionsGranted = permissionsGranted,
            knownPhones = knownPhones,
            overlayGranted = overlayGranted,
        ),
    )

    @Test
    fun holdReasonsInPriorityOrder() {
        assertEquals(AutoHold.READY, hold())
        assertEquals(AutoHold.WIRED_ONLY, hold(wiredOnly = true, enabled = false))
        assertEquals(AutoHold.DISABLED, hold(enabled = false, wiredActive = true))
        assertEquals(AutoHold.WIRED_ACTIVE, hold(wiredActive = true, knownPhones = 0))
        assertEquals(AutoHold.HOTSPOT_UNSUPPORTED, hold(hotspotSupported = false, identityLoaded = false))
        assertEquals(AutoHold.IDENTITY_MISSING, hold(identityLoaded = false, bluetoothAddressKnown = false))
        assertEquals(AutoHold.BLUETOOTH_ADDRESS_MISSING, hold(bluetoothAddressKnown = false, bluetoothOn = false))
        assertEquals(AutoHold.BLUETOOTH_OFF, hold(bluetoothOn = false, permissionsGranted = false))
        assertEquals(AutoHold.PERMISSIONS_MISSING, hold(permissionsGranted = false, knownPhones = 0))
        assertEquals(AutoHold.NO_KNOWN_PHONE, hold(knownPhones = 0, overlayGranted = false))
        assertEquals(AutoHold.OVERLAY_MISSING, hold(overlayGranted = false))
    }

    /** 问一次，要拨就当作真的拨了（连接建出来了）。 */
    private fun ReconnectTriggers.dialNow(decision: Decision, now: Long): Decision {
        if (decision is Decision.Dial) commitDial(decision.address, now)
        return decision
    }

    @Test
    fun bluetoothConnectDialsOnlyKnownPhones() {
        val t = ReconnectTriggers()
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone.lowercase(), known, 0))
        assertIs<Decision.Skip>(t.onBluetoothConnected(other, known, 100_000), "没用无线连成功过的手机不拨")
        assertIs<Decision.Skip>(ReconnectTriggers().onBluetoothConnected(phone, emptySet(), 0), "老用户升级后还没有记录：不拨")
    }

    @Test
    fun ownDialDoesNotTriggerAnotherDialWithinCooldown() {
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.dialNow(t.onBluetoothConnected(phone, known, 0), 0)
        // 车机拨号让手机蓝牙「连上」，系统又报一次（比亚迪会排队几秒，甚至在拨号失败之后才到）。
        assertIs<Decision.Skip>(t.onBluetoothConnected(phone, known, 8_000))
        assertIs<Decision.Skip>(t.onBluetoothConnected(phone, known, 59_999))
        // 真的重新连上（过了冷却）再拨。
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone, known, 60_000))
    }

    @Test
    fun aDialThatNeverStartedLeavesNoTrace() {
        // 问了、但没拨成（例如界面没出来）：不占冷却、不算这次拨过，补上条件后马上还能拨。
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 0))
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 5_000))
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone, known, 6_000))
    }

    @Test
    fun uiShownDialsOncePerBluetoothConnection() {
        val t = ReconnectTriggers(cooldownMillis = 1_000)
        t.dialNow(t.onUiShown(phone, known, 0), 0)
        // 用户点了停止、切走再回来：还是这次蓝牙连接，不再拨（冷却早过了）。
        t.onSessionEnded(phone, projected = true, stoppedByUser = true)
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 600_000))
        // 手机蓝牙断开又连上：新的一次，蓝牙连上那一下就拨了；之后回到界面不再拨。
        t.onBluetoothDisconnected(phone)
        assertEquals(Decision.Dial(phone), t.dialNow(t.onBluetoothConnected(phone, known, 700_000), 700_000))
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 800_000))
        // 只断开：下次回到界面就拨。
        t.onBluetoothDisconnected(phone)
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 900_000))
    }

    @Test
    fun projectedSessionEndingByItselfRearmsTheUiDial() {
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.dialNow(t.onUiShown(phone, known, 0), 0)
        // 投屏过、手机那边断了（不是用户停的）：冷却一起清掉，回到界面立即重拨（2026-11-23：行驶中断连自动恢复，
        // 原来要等满 60 秒冷却，车主只能干等）。手机蓝牙再连上（700_000 处那步）同样立即拨。
        t.onSessionEnded(phone, projected = true, stoppedByUser = false)
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 30_000))
        // 拨号没投屏就失败：从「这次蓝牙连接里拨过」里释放（2026-11-26：自动重试打满后，车主切回界面就能立刻再拨，
        // 不用等手机的蓝牙真的断开重连）；lastDialAt 照旧保留，40 秒处（30_000 拨的、12~60 秒冷却内）仍挡广播回声。
        t.dialNow(t.onUiShown(phone, known, 30_000), 30_000)
        t.onSessionEnded(phone, projected = false, stoppedByUser = false)
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 200_000), "没投屏的失败也释放这次蓝牙连接的拨号名额")
        t.dialNow(t.onUiShown(phone, known, 200_000), 200_000)
        t.onSessionEnded(phone, projected = false, stoppedByUser = false)
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 240_000), "lastDialAt 还在（200_000 拨的，60 秒冷却内挡回声）")
        assertIs<Decision.Dial>(t.onUiShown(phone, known, 300_000))
    }

    @Test
    fun userStoppingAScannedSessionAlsoHoldsTheUiDial() {
        // 扫码连上的（车机没拨过）被用户停止：切走再回来不拨；手机蓝牙断开或重新连上以后照常。
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.onSessionEnded(phone, projected = true, stoppedByUser = true)
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 600_000))
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone, known, 700_000))
        t.onBluetoothDisconnected(phone)
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 800_000))
    }

    @Test
    fun blindDialAtStartDoesNotBlockTheRealBluetoothConnect() {
        // 开机时盲拨最近那台，手机不在（没有蓝牙事件）；之后手机来了、蓝牙连上：照拨。
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.dialNow(t.onUiShown(phone, known, 0), 0)
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone, known, 300_000))
    }

    @Test
    fun uiShownNeedsARecordedPhone() {
        val t = ReconnectTriggers()
        assertIs<Decision.Skip>(t.onUiShown(null, known, 0))
        assertIs<Decision.Skip>(t.onUiShown(other, known, 0))
    }

    @Test
    fun directDialCountsForCooldownAndConnection() {
        // 调试入口直接拨：照样记进冷却和这次蓝牙连接。
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.commitDial(phone, 0)
        assertIs<Decision.Skip>(t.onBluetoothConnected(phone, known, 5_000))
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 120_000))
    }

    @Test
    fun wirelessBlocksWiredUnlessStillWaitingForThePhone() {
        val qr = "x"
        fun run(vararg signals: WirelessSignal) = signals.fold(WirelessUiState()) { s, sig -> WirelessReducer.reduce(s, sig) }

        assertFalse(ReconnectPolicy.blocksWired(WirelessUiState()), "空闲不挡")
        val waiting = run(WirelessSignal.AttemptStarted(1, autoReconnect = true), WirelessSignal.Wireless(WirelessEvent.PairingReady(qr, "135790")))
        assertTrue(waiting.waitingForReconnect)
        assertFalse(ReconnectPolicy.blocksWired(waiting))
        val connected = WirelessReducer.reduce(waiting, WirelessSignal.Wireless(WirelessEvent.PhoneBluetoothConnected("02:00:00:00:00:01")))
        assertTrue(ReconnectPolicy.blocksWired(connected))
        // 车机主动回连（不是在等手机）：在拨就挡（点开始、开机等插线不能打断它）。
        assertTrue(ReconnectPolicy.blocksWired(run(WirelessSignal.AttemptStarted(2))))
        // 用户点开始的扫码连接照旧挡。
        assertTrue(ReconnectPolicy.blocksWired(run(WirelessSignal.AttemptStarted(3), WirelessSignal.Wireless(WirelessEvent.PairingReady(qr, "135790")))))
    }

    @Test
    fun aPluggedPhoneIsIgnoredOnlyOnceAPhoneIsOnWireless() {
        val qr = "x"
        fun run(vararg signals: WirelessSignal) = signals.fold(WirelessUiState()) { s, sig -> WirelessReducer.reduce(s, sig) }

        assertFalse(ReconnectPolicy.phoneOnWireless(WirelessUiState()))
        // 扫码等待、车机在拨：手机还没连上，插线优先。
        val scanning = run(WirelessSignal.AttemptStarted(3), WirelessSignal.Wireless(WirelessEvent.PairingReady(qr, "135790")))
        assertFalse(ReconnectPolicy.phoneOnWireless(scanning))
        assertFalse(ReconnectPolicy.phoneOnWireless(run(WirelessSignal.AttemptStarted(2))))
        // 手机经蓝牙连上以后：插线不管（多半是在充电）。
        val scanned = WirelessReducer.reduce(scanning, WirelessSignal.Wireless(WirelessEvent.PhoneBluetoothConnected("02:00:00:00:00:02")))
        assertTrue(ReconnectPolicy.phoneOnWireless(scanned))
    }

    @Test
    fun needsPairingOnlyTriggersOnReconnectFailedWithNeedsPairing() {
        assertTrue(
            ReconnectPolicy.needsPairing(
                WirelessEndCause.Session(WirelessEndReason.ReconnectFailed("手机当场拒绝了", needsPairing = true)),
            ),
        )
        assertFalse(
            ReconnectPolicy.needsPairing(
                WirelessEndCause.Session(WirelessEndReason.ReconnectFailed("超时", needsPairing = false)),
            ),
        )
        assertFalse(ReconnectPolicy.needsPairing(WirelessEndCause.Session(WirelessEndReason.LocalStop)))
        assertFalse(ReconnectPolicy.needsPairing(WirelessEndCause.UserStop))
    }

    @Test
    fun forgettingAPhoneClearsItsCooldownAndConnectionDialState() {
        val t = ReconnectTriggers(cooldownMillis = 60_000)
        t.dialNow(t.onUiShown(phone, known, 0), 0)
        assertIs<Decision.Skip>(t.onBluetoothConnected(phone, known, 5_000))
        assertIs<Decision.Skip>(t.onUiShown(phone, known, 5_000))

        t.forget(phone)
        assertEquals(Decision.Dial(phone), t.onBluetoothConnected(phone, known, 5_001))
        assertEquals(Decision.Dial(phone), t.onUiShown(phone, known, 5_001))
    }
}
