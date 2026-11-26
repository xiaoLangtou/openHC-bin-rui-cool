package io.github.gcbond.openhc.wireless

import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.core.auth.AuthMode
import io.github.gcbond.openhc.core.dmsdp.KeepAliveMode
import io.github.gcbond.openhc.core.session.SessionEvent
import io.github.gcbond.openhc.core.session.SessionLayer
import io.github.gcbond.openhc.core.session.VideoParameters
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.gcbond.openhc.core.session.WirelessStep as SessionStep

/** 无线连接的状态归并：无线事件、会话事件、本机操作 → 界面状态。测试数据都是合成值。 */
class WirelessReducerTest {

    private val video = VideoParameters(1280, 720, 60, touchInput = true, keyInput = false, textInput = false)
    private val qr = "hicar://car/connect?devicename=OpenHC-Test&mac=0A:1B:2C:3D:4E:5F&moduleid=000000&submoduleid=00&pin=135790&ability=6"

    private fun run(vararg signals: WirelessSignal, from: WirelessUiState = WirelessUiState()): WirelessUiState =
        signals.fold(from) { state, signal -> WirelessReducer.reduce(state, signal) }

    private fun wireless(event: WirelessEvent) = WirelessSignal.Wireless(event)
    private fun session(event: SessionEvent) = WirelessSignal.Session(event)

    /** 到「二维码已显示」为止。 */
    private val untilScan = arrayOf(
        WirelessSignal.AttemptStarted(1),
        wireless(WirelessEvent.PairingReady(qr, "135790")),
    )

    /** 到「连接串已发、等手机入网」为止（首次配对）。 */
    private val untilJoin = untilScan + arrayOf(
        wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")),
        wireless(WirelessEvent.BluetoothHandshakeCompleted(encrypted = true)),
        session(SessionEvent.AuthStarted(AuthMode.PIN_PAIRING)),
        session(SessionEvent.Authenticated(AuthMode.PIN_PAIRING)),
        wireless(WirelessEvent.WifiNetworkReady(5745)),
        wireless(WirelessEvent.DataChannelNegotiated(5745)),
        wireless(WirelessEvent.ConnectionInfoSent(41000, "02:aa:bb:cc:dd:ee", 149)),
    )

    @Test
    fun startsIdleAndPreparesOnStart() {
        assertEquals(WirelessPhase.IDLE, WirelessUiState().phase)
        val state = run(WirelessSignal.AttemptStarted(3))
        assertEquals(WirelessPhase.PREPARING, state.phase)
        assertTrue(state.active)
        assertEquals(3, state.attempt)
        assertEquals(Phase.PREPARING, state.projection.phase)
    }

    @Test
    fun pairingReadyShowsTheQrCodeAndPin() {
        val state = run(*untilScan)
        assertEquals(WirelessPhase.WAITING_FOR_SCAN, state.phase)
        assertEquals(qr, state.qrCode)
        assertEquals("135790", state.pinCode)
    }

    /** 主动回连（三路并发）：控制监听开好（PairingReady）不出二维码，一直显示正在连接。 */
    @Test
    fun reconnectingKeepsConnectingAndHidesTheQrCode() {
        var state = run(WirelessSignal.AttemptStarted(1, reconnecting = true))
        assertEquals(WirelessPhase.PREPARING, state.phase)
        assertTrue(state.reconnecting)
        state = run(wireless(WirelessEvent.PairingReady(qr, "135790")), from = state)
        assertEquals(WirelessPhase.CONNECTING, state.phase)
        assertEquals(WirelessStep.BLUETOOTH_HANDSHAKE, state.step)
        assertNull(state.qrCode)
        assertNull(state.pinCode)
        assertTrue(state.active)
        // 手机连上以后照常往下走，不因为「没经过扫码页」而卡住。
        state = run(wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")), from = state)
        assertEquals(WirelessPhase.CONNECTING, state.phase)
    }

    @Test
    fun bluetoothStepsUntilThePhoneJoins() {
        var state = run(*untilScan, wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")))
        assertEquals(WirelessPhase.CONNECTING, state.phase)
        assertEquals(WirelessStep.BLUETOOTH_HANDSHAKE, state.step)
        assertNull(state.qrCode, "手机连上后二维码就没用了")
        assertEquals("135790", state.pinCode, "连接码留着，手机万一要求手输")

        state = run(wireless(WirelessEvent.BluetoothHandshakeCompleted(true)), from = state)
        assertEquals(WirelessStep.WAITING_AUTH, state.step)
        state = run(session(SessionEvent.AuthStarted(AuthMode.PIN_PAIRING)), from = state)
        assertEquals(WirelessStep.AUTH_PAIRING, state.step)
        // 蓝牙段的认证不进投屏段：有线归并会把它当成「请在手机上输入连接码」。
        assertEquals(Phase.PREPARING, state.projection.phase)

        state = run(session(SessionEvent.Authenticated(AuthMode.PIN_PAIRING)), from = state)
        assertEquals(WirelessStep.NEGOTIATING_WIFI, state.step)
        assertNull(state.pinCode)
        // 热点开好不改这一步；协商完（热点早已开好）直接发网络信息。
        state = run(wireless(WirelessEvent.WifiNetworkReady(5745)), from = state)
        assertEquals(WirelessStep.NEGOTIATING_WIFI, state.step)
        state = run(wireless(WirelessEvent.DataChannelNegotiated(5745)), from = state)
        assertEquals(WirelessStep.SENDING_NETWORK, state.step)
        state = run(wireless(WirelessEvent.ConnectionInfoSent(41000, "02:aa:bb:cc:dd:ee", 149)), from = state)
        assertEquals(WirelessStep.WAITING_PHONE_JOIN, state.step)
        assertEquals(WirelessPhase.CONNECTING, state.phase)
    }

    @Test
    fun trustedReconnectHasItsOwnStep() {
        val state = run(
            *untilScan,
            wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")),
            session(SessionEvent.AuthStarted(AuthMode.TRUSTED_RECONNECT)),
        )
        assertEquals(WirelessStep.AUTH_TRUSTED, state.step)
    }

    @Test
    fun wifiPhaseFeedsTheProjectionStateLikeWired() {
        var state = run(*untilJoin, session(SessionEvent.PhoneConnected("192.168.49.2")))
        assertEquals(WirelessStep.WIFI_HANDSHAKE, state.step)
        assertEquals(Phase.CONNECTING, state.projection.phase)
        state = run(session(SessionEvent.ControlHandshakeCompleted(true)), from = state)
        assertEquals(WirelessStep.DEVICE_SESSION, state.step, "认证已在蓝牙上做完，握手后直接是设备会话")
        state = run(session(SessionEvent.DeviceSessionReady(KeepAliveMode.UDP)), from = state)
        assertEquals(WirelessStep.WAITING_PROJECTION, state.step)
        state = run(session(SessionEvent.ProjectionRequested(40500)), from = state)
        assertEquals(WirelessStep.NEGOTIATING_PROJECTION, state.step)

        state = run(session(SessionEvent.VideoNegotiated(video)), from = state)
        assertEquals(WirelessPhase.PROJECTING, state.phase)
        assertNull(state.step)
        assertEquals(Phase.PROJECTING, state.projection.phase)
        assertEquals(1280, state.projection.video?.width)
        assertFalse(state.inputReady)

        state = run(session(SessionEvent.PlayAccepted), session(SessionEvent.VideoStarted(video)), from = state)
        assertTrue(state.inputReady)
        assertTrue(state.projection.firstFrame)
        state = run(WirelessSignal.VideoSize(1280, 704), WirelessSignal.DecoderProblem("合成问题"), from = state)
        assertEquals(704, state.projection.videoHeight)
        assertEquals("合成问题", state.projection.decoderProblem)
    }

    @Test
    fun diagnosticsDoNotChangeTheStep() {
        val before = run(*untilJoin)
        val after = run(session(SessionEvent.Diagnostic(SessionLayer.SESSION, "合成诊断")), from = before)
        assertEquals(before.phase, after.phase)
        assertEquals(before.step, after.step)
    }

    @Test
    fun endedThenFinishedShowsTheReason() {
        val failed = WirelessEndReason.Failed(SessionStep.PHONE_JOIN, "合成：超时")
        var state = run(*untilJoin, wireless(WirelessEvent.Ended(failed)))
        assertEquals(WirelessPhase.STOPPING, state.phase)
        assertNull(state.pinCode)
        assertTrue(state.active)

        // 收尾中迟到的事件不改阶段。
        state = run(session(SessionEvent.PhoneConnected("192.168.49.2")), from = state)
        assertEquals(WirelessPhase.STOPPING, state.phase)

        state = run(WirelessSignal.AttemptFinished(WirelessEndCause.Session(failed)), from = state)
        assertEquals(WirelessPhase.IDLE, state.phase)
        assertFalse(state.active)
        assertEquals(1, state.attempt)
        val notice = assertIs<WirelessNotice.Ended>(state.notice)
        assertSame(failed, assertIs<WirelessEndCause.Session>(notice.cause).reason)
        assertEquals(Phase.IDLE, state.projection.phase)
    }

    @Test
    fun userStopClearsTheQrCodeAndLeavesNoNotice() {
        var state = run(*untilScan, WirelessSignal.StopRequested)
        assertEquals(WirelessPhase.STOPPING, state.phase)
        assertNull(state.qrCode)
        state = run(WirelessSignal.AttemptFinished(WirelessEndCause.UserStop), from = state)
        assertEquals(WirelessPhase.IDLE, state.phase)
        assertNull(state.notice)

        val idle = WirelessUiState(notice = WirelessNotice.BluetoothOff)
        assertSame(idle, WirelessReducer.reduce(idle, WirelessSignal.StopRequested), "空闲时停止什么都不变")
    }

    @Test
    fun manualAddressWarningLastsForTheAttempt() {
        var state = run(WirelessSignal.AttemptStarted(1, addressMismatch = true), wireless(WirelessEvent.PairingReady(qr, "135790")))
        assertTrue(state.addressMismatch)
        state = run(WirelessSignal.AttemptFinished(WirelessEndCause.UserStop), from = state)
        assertFalse(state.addressMismatch)
        assertFalse(run(WirelessSignal.AttemptStarted(2)).addressMismatch)
    }

    @Test
    fun blockedGoesBackToIdleWithTheProblem() {
        val state = run(WirelessSignal.Blocked(WirelessNotice.BluetoothAddressMissing), from = WirelessUiState(attempt = 2))
        assertEquals(WirelessPhase.IDLE, state.phase)
        assertEquals(WirelessNotice.BluetoothAddressMissing, state.notice)
        assertEquals(2, state.attempt)
        assertFalse(state.active)
    }

    @Test
    fun lateEventsWhileIdleAreIgnored() {
        val idle = WirelessUiState(attempt = 1)
        assertSame(idle, WirelessReducer.reduce(idle, wireless(WirelessEvent.PairingReady(qr, "135790"))))
        assertSame(idle, WirelessReducer.reduce(idle, session(SessionEvent.VideoNegotiated(video))))
        assertSame(idle, WirelessReducer.reduce(idle, WirelessSignal.VideoSize(1, 1)))
    }

    @Test
    fun autoReconnectAttemptKeepsTheLastEndReasonAndTracksTheBeacon() {
        val failed = WirelessEndReason.Failed(SessionStep.PHONE_JOIN, "合成：超时")
        val ended = run(*untilJoin, wireless(WirelessEvent.Ended(failed)), WirelessSignal.AttemptFinished(WirelessEndCause.Session(failed)))

        // 自动回到等待：留着上一次的结束原因。
        var state = run(WirelessSignal.AttemptStarted(2, autoReconnect = true), from = ended)
        assertTrue(state.autoReconnect)
        assertIs<WirelessNotice.Ended>(state.notice)
        assertTrue(state.waitingForReconnect)
        // 用户开始的不留。
        assertNull(run(WirelessSignal.AttemptStarted(2), from = ended).notice)

        state = run(
            wireless(WirelessEvent.PairingReady(qr, "135790")),
            WirelessSignal.Beacon(BeaconStatus.Starting),
            WirelessSignal.Beacon(BeaconStatus.Advertising(1)),
            from = state,
        )
        assertEquals(WirelessPhase.WAITING_FOR_SCAN, state.phase)
        assertEquals(qr, state.qrCode, "等回连时也显示二维码，新手机照样能扫")
        assertEquals(BeaconStatus.Advertising(1), state.beacon)

        state = run(wireless(WirelessEvent.PhoneBluetoothConnected("02:66:77:88:99:AA")), from = state)
        assertTrue(state.phoneConnected)
        assertFalse(state.waitingForReconnect, "手机连上之后就不是在等了")
        state = run(WirelessSignal.Beacon(BeaconStatus.Stopped), from = state)
        assertEquals(BeaconStatus.Stopped, state.beacon)

        state = run(WirelessSignal.AttemptFinished(WirelessEndCause.UserStop), from = state)
        assertFalse(state.autoReconnect)
        assertNull(state.beacon)
        assertFalse(state.phoneConnected)
        // 空闲时迟到的广播状态不理。
        assertSame(state, WirelessReducer.reduce(state, WirelessSignal.Beacon(BeaconStatus.Advertising(3))))
    }

    @Test
    fun autoHoldSurvivesTheIdleTransitionsButNotANewAttempt() {
        var state = run(WirelessSignal.AutoHoldChanged(AutoHold.NO_PAIRED_PHONE))
        assertEquals(AutoHold.NO_PAIRED_PHONE, state.autoHold)
        state = run(WirelessSignal.Blocked(WirelessNotice.BluetoothOff), from = state)
        assertEquals(AutoHold.NO_PAIRED_PHONE, state.autoHold, "开始不了不清掉自动回连的说明")
        state = run(WirelessSignal.AttemptStarted(1), from = state)
        assertNull(state.autoHold)
        state = run(WirelessSignal.AutoHoldChanged(AutoHold.PAUSED_BY_USER), WirelessSignal.AttemptFinished(WirelessEndCause.UserStop), from = state)
        assertEquals(AutoHold.PAUSED_BY_USER, state.autoHold)
        assertNull(run(WirelessSignal.AutoHoldChanged(null), from = state).autoHold)
    }

    @Test
    fun theDirectModeOfAnAttemptCarriesIntoItsEndedNotice() {
        val failed = WirelessEndCause.Session(WirelessEndReason.Failed(SessionStep.WIFI_NETWORK, "车机 WiFi 关着"))
        val during = run(WirelessSignal.AttemptStarted(1, direct = true))
        assertTrue(during.direct)
        val ended = run(WirelessSignal.AttemptFinished(failed), from = during)
        assertEquals(WirelessNotice.Ended(failed, direct = true), ended.notice)
        assertFalse(ended.direct, "空闲时首页按设置说，不沿用上一次的方式")

        val hotspot = run(WirelessSignal.AttemptStarted(2), WirelessSignal.AttemptFinished(failed))
        assertEquals(WirelessNotice.Ended(failed, direct = false), hotspot.notice)
    }

    @Test
    fun toStringHidesTheQrCodeAndPin() {
        val text = run(*untilScan).toString()
        assertFalse(text.contains("135790"))
        assertFalse(text.contains("hicar://"))
        assertTrue(text.contains("二维码=有"))
    }

    @Test
    fun theFirstEndCauseWins() {
        val tracker = WirelessEndTracker()
        assertEquals(WirelessEndCause.UserStop, tracker.result(), "什么都没记就算用户停止")
        tracker.onSessionEnded(WirelessEndReason.LocalStop)
        assertEquals(WirelessEndCause.UserStop, tracker.result(), "本机停止盖不掉别的原因，自己也不算原因")
        val failed = WirelessEndReason.Failed(SessionStep.WIFI_NETWORK, "合成")
        tracker.onSessionEnded(failed)
        tracker.onFailure("后来的")
        tracker.onUserStop()
        assertSame(failed, assertIs<WirelessEndCause.Session>(tracker.result()).reason)

        val stopped = WirelessEndTracker()
        stopped.onUserStop()
        stopped.onSessionEnded(failed)
        assertEquals(WirelessEndCause.UserStop, stopped.result())
    }
}
