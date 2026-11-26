package io.github.gcbond.openhc.ui

import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState

/**
 * 「HiCar 启动动画」该不该显示：纯决策，不引用安卓类，由 JVM 单元测试覆盖。
 *
 * 两个登场场景（对应 Issue #113 的演示）：
 * 1. 首页等连接：只遮「车机主动回连」（`reconnecting`）的准备/握手段——那是自动回连的黑盒等待，
 *    用户什么也做不了；手动扫码（`WAITING_FOR_SCAN`）、要确认、要输连接码这些需要人操作的阶段一律不遮。
 * 2. 投屏页等首帧：无线、有线通用；首帧到了自己淡出，解码器出问题时不遮（要让人看到报错）。
 *
 * 用户双击/按返回键收起动画（逃生出口）后，用「dismiss 时的 attempt」记住：同一次连接不再弹，
 * 下一次连接（attempt 变了）恢复显示。
 */
object LaunchSplashPolicy {

    /**
     * [enabled] 设置开关（关了一律不显示）；[inSettings] 当前在设置页（设置页永远不遮）；
     * [inProjection] 当前在投屏页（true = 等首帧场景，false = 首页等回连场景）；
     * [projectionWireless] 投屏页显示的是无线还是有线的画面；
     * [dismissedWirelessAttempt] / [dismissedWiredAttempt] 用户收起动画时的连接次数（同次不再弹）。
     */
    fun shouldShow(
        enabled: Boolean,
        inSettings: Boolean,
        inProjection: Boolean,
        projectionWireless: Boolean,
        wired: ConnectionUiState,
        wireless: WirelessUiState,
        dismissedWirelessAttempt: Int,
        dismissedWiredAttempt: Int,
    ): Boolean {
        if (!enabled || inSettings) return false
        if (inProjection) {
            // 投屏页等首帧：取当前显示的那种连接的状态。首帧到达或解码器报错都不遮（报错要让人看见）；
            // 用户手动收起过这一次（attempt 相同）也不再弹。
            val proj = if (projectionWireless) wireless.projection else wired
            val dismissed = if (projectionWireless) dismissedWirelessAttempt else dismissedWiredAttempt
            return !proj.firstFrame && proj.decoderProblem == null && proj.attempt != dismissed
        }
        // 首页等连接：只遮自动回连的自动等待段。手动扫码要给用户看二维码，手机上要确认、要输连接码的
        // 时候必须露出界面，所以只认「正在连接（reconnecting）且还没走到要人操作的那步」。
        return wireless.reconnecting &&
            (wireless.phase == WirelessPhase.PREPARING || wireless.phase == WirelessPhase.CONNECTING) &&
            wireless.step != WirelessStep.CONFIRM_ON_PHONE &&
            wireless.pinCode == null &&
            wireless.attempt != dismissedWirelessAttempt
    }
}
