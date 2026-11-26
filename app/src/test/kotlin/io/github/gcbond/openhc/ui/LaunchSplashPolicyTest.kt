package io.github.gcbond.openhc.ui

import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HiCar 启动动画显隐策略：开关、设置页、首页自动回连等待、投屏页等首帧、
 * 手动收起（attempt 记忆）各分支。纯 JVM，无安卓依赖。
 */
class LaunchSplashPolicyTest {

    /** 首页场景下正在自动回连的标准状态。 */
    private fun reconnecting(phase: WirelessPhase = WirelessPhase.PREPARING, step: WirelessStep? = null) = WirelessUiState(
        phase = phase,
        step = step,
        attempt = 1,
        active = true,
        reconnecting = true,
    )

    /** 投屏场景：等首帧的标准状态。 */
    private fun waitingFirstFrame(attempt: Int = 1) = ConnectionUiState(
        phase = Phase.PROJECTING,
        attempt = attempt,
        active = true,
    )

    private fun show(
        enabled: Boolean = true,
        inSettings: Boolean = false,
        inProjection: Boolean = false,
        projectionWireless: Boolean = false,
        wired: ConnectionUiState = ConnectionUiState(),
        wireless: WirelessUiState = WirelessUiState(),
        dismissedWirelessAttempt: Int = 0,
        dismissedWiredAttempt: Int = 0,
    ) = LaunchSplashPolicy.shouldShow(
        enabled, inSettings, inProjection, projectionWireless,
        wired, wireless, dismissedWirelessAttempt, dismissedWiredAttempt,
    )

    // ---------------- 开关与全局不遮 ----------------

    @Test
    fun disabledSwitchNeverShows() {
        assertFalse(show(enabled = false, wireless = reconnecting()))
        assertFalse(show(enabled = false, inProjection = true, wired = waitingFirstFrame()))
    }

    @Test
    fun settingsScreenNeverCovered() {
        assertFalse(show(inSettings = true, wireless = reconnecting()))
        assertFalse(show(inSettings = true, inProjection = true, wired = waitingFirstFrame()))
    }

    // ---------------- 首页：无线自动回连等待 ----------------

    @Test
    fun showsDuringWirelessReconnectPreparing() {
        assertTrue(show(wireless = reconnecting(WirelessPhase.PREPARING)))
    }

    @Test
    fun showsDuringWirelessReconnectConnecting() {
        assertTrue(show(wireless = reconnecting(WirelessPhase.CONNECTING, WirelessStep.AUTH_TRUSTED)))
    }

    @Test
    fun manualScanNeverShows() {
        // 手动扫码（WAITING_FOR_SCAN）：用户要看二维码，不遮。
        assertFalse(
            show(
                wireless = WirelessUiState(
                    phase = WirelessPhase.WAITING_FOR_SCAN,
                    attempt = 1,
                    active = true,
                    reconnecting = true,
                ),
            ),
        )
        // 空闲、已投屏、收尾阶段也都不遮。
        assertFalse(show(wireless = WirelessUiState(phase = WirelessPhase.IDLE, reconnecting = true, attempt = 1)))
        assertFalse(show(wireless = WirelessUiState(phase = WirelessPhase.PROJECTING, reconnecting = true, attempt = 1)))
        assertFalse(show(wireless = WirelessUiState(phase = WirelessPhase.STOPPING, reconnecting = true, attempt = 1)))
    }

    @Test
    fun confirmOnPhoneNeverShows() {
        // 手机上要确认：必须露出界面让用户操作。
        assertFalse(show(wireless = reconnecting(WirelessPhase.CONNECTING, WirelessStep.CONFIRM_ON_PHONE)))
    }

    @Test
    fun pinCodeNeverShows() {
        // 要输连接码：必须露出界面。
        assertFalse(
            show(
                wireless = WirelessUiState(
                    phase = WirelessPhase.CONNECTING,
                    attempt = 1,
                    active = true,
                    reconnecting = true,
                    pinCode = "123456",
                ),
            ),
        )
    }

    // ---------------- 投屏页：等首帧 ----------------

    @Test
    fun showsWhileWaitingFirstFrameWireless() {
        val wireless = WirelessUiState(
            phase = WirelessPhase.PROJECTING,
            attempt = 1,
            active = true,
            projection = waitingFirstFrame(attempt = 1),
        )
        assertTrue(show(inProjection = true, projectionWireless = true, wireless = wireless))
    }

    @Test
    fun showsWhileWaitingFirstFrameWired() {
        assertTrue(show(inProjection = true, projectionWireless = false, wired = waitingFirstFrame()))
    }

    @Test
    fun hidesOnceFirstFrameArrives() {
        val wireless = WirelessUiState(
            phase = WirelessPhase.PROJECTING,
            attempt = 1,
            active = true,
            projection = waitingFirstFrame().copy(firstFrame = true),
        )
        assertFalse(show(inProjection = true, projectionWireless = true, wireless = wireless))
        assertFalse(
            show(inProjection = true, projectionWireless = false, wired = waitingFirstFrame().copy(firstFrame = true)),
        )
    }

    @Test
    fun hidesOnDecoderProblem() {
        // 解码器出问题：报错要让人看见，不遮。
        assertFalse(
            show(inProjection = true, projectionWireless = false, wired = waitingFirstFrame().copy(decoderProblem = "没有解码器")),
        )
        val wireless = WirelessUiState(
            phase = WirelessPhase.PROJECTING,
            attempt = 1,
            active = true,
            projection = waitingFirstFrame(attempt = 1).copy(decoderProblem = "没有解码器"),
        )
        assertFalse(show(inProjection = true, projectionWireless = true, wireless = wireless))
    }

    // ---------------- 手动收起的记忆 ----------------

    @Test
    fun dismissedAttemptStaysHiddenUntilNextAttempt() {
        // 同一次连接收起后不再弹。
        assertFalse(show(inProjection = true, wired = waitingFirstFrame(attempt = 3), dismissedWiredAttempt = 3))
        assertFalse(show(wireless = reconnecting(), dismissedWirelessAttempt = 1))
        // 下一次连接（attempt 变了）恢复显示。
        assertTrue(show(inProjection = true, wired = waitingFirstFrame(attempt = 4), dismissedWiredAttempt = 3))
        assertTrue(show(wireless = reconnecting(phase = WirelessPhase.CONNECTING).let { it.copy(attempt = 2) }, dismissedWirelessAttempt = 1))
    }

    @Test
    fun wirelessAndWiredDismissRememberedIndependently() {
        // 有线收起不影响无线（两种连接互不干扰）。
        assertTrue(
            show(
                inProjection = true,
                projectionWireless = true,
                wireless = WirelessUiState(
                    phase = WirelessPhase.PROJECTING,
                    attempt = 2,
                    active = true,
                    projection = waitingFirstFrame(attempt = 2),
                ),
                wired = waitingFirstFrame(attempt = 1),
                dismissedWiredAttempt = 1,
            ),
        )
    }

    // ---------------- 场景切换 ----------------

    @Test
    fun backToHomeWhileProjectingNeverShows() {
        // 已投屏返回首页（首页显示连接状态，不强遮）。
        assertFalse(
            show(
                wireless = WirelessUiState(phase = WirelessPhase.PROJECTING, attempt = 1, active = true),
                wired = ConnectionUiState(phase = Phase.PROJECTING, attempt = 1, active = true),
            ),
        )
    }
}
