package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.EndCause
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessStep as SessionStep
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.BeaconStatus
import io.github.gcbond.openhc.wireless.WirelessEndCause
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPermissions
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState

// 无线连接状态 → 界面文字。

@Composable
internal fun wirelessPhaseTitle(state: WirelessUiState): String = stringResource(
    when (state.phase) {
        WirelessPhase.IDLE -> R.string.wireless_phase_idle
        // 自动进入的等待：标题说清是在等配对过的手机（二维码也照样显示，新手机能扫）。
        WirelessPhase.PREPARING -> if (state.autoReconnect) R.string.wireless_phase_auto else R.string.wireless_phase_preparing
        WirelessPhase.WAITING_FOR_SCAN -> if (state.autoReconnect) R.string.wireless_phase_auto else R.string.wireless_phase_scan
        WirelessPhase.CONNECTING -> R.string.wireless_phase_connecting
        WirelessPhase.PROJECTING -> R.string.wireless_phase_projecting
        WirelessPhase.STOPPING -> R.string.wireless_phase_stopping
    },
)

@Composable
internal fun wirelessHint(state: WirelessUiState): String {
    state.step?.let { return stringResource(stepHint(it, state.direct)) }
    return when (state.phase) {
        WirelessPhase.IDLE -> stringResource(R.string.wireless_hint_idle)
        WirelessPhase.PREPARING -> stringResource(R.string.wireless_hint_preparing)
        WirelessPhase.WAITING_FOR_SCAN ->
            stringResource(if (state.autoReconnect) R.string.wireless_hint_auto else R.string.wireless_hint_scan)
        WirelessPhase.CONNECTING -> stringResource(R.string.hint_connecting)
        // 投屏段与有线相同，沿用有线的说明（分辨率、帧率、等第一帧、手机没开放触摸）。
        WirelessPhase.PROJECTING -> {
            val p = state.projection
            val video = p.video
            when {
                video == null -> stringResource(R.string.hint_negotiating)
                !p.firstFrame -> stringResource(R.string.hint_projecting_waiting_frame, video.width, video.height, video.fps)
                !video.touchInput -> stringResource(R.string.hint_projecting_no_touch, video.width, video.height, video.fps)
                else -> stringResource(R.string.hint_projecting, video.width, video.height, video.fps)
            }
        }
        WirelessPhase.STOPPING -> stringResource(R.string.wireless_hint_stopping)
    }
}

/** [direct]：这次手机加入的是 WiFi 直连组，WiFi 那几步换成直连的说法。 */
private fun stepHint(step: WirelessStep, direct: Boolean): Int = when (step) {
    WirelessStep.BLUETOOTH_HANDSHAKE -> R.string.wireless_hint_bt_handshake
    WirelessStep.WAITING_AUTH -> R.string.wireless_hint_waiting_auth
    WirelessStep.CONFIRM_ON_PHONE -> R.string.wireless_hint_confirm_on_phone
    WirelessStep.AUTH_PAIRING -> R.string.wireless_hint_auth_pairing
    WirelessStep.AUTH_TRUSTED -> R.string.wireless_hint_auth_trusted
    WirelessStep.NEGOTIATING_WIFI -> if (direct) R.string.wireless_hint_negotiating_direct else R.string.wireless_hint_negotiating_hotspot
    WirelessStep.SENDING_NETWORK -> if (direct) R.string.wireless_hint_sending_direct else R.string.wireless_hint_sending_hotspot
    WirelessStep.WAITING_PHONE_JOIN -> if (direct) R.string.wireless_hint_waiting_join_direct else R.string.wireless_hint_waiting_join_hotspot
    WirelessStep.WIFI_HANDSHAKE -> R.string.wireless_hint_wifi_handshake
    WirelessStep.DEVICE_SESSION -> R.string.wireless_hint_device_session
    WirelessStep.WAITING_PROJECTION -> R.string.wireless_hint_waiting_projection
    WirelessStep.NEGOTIATING_PROJECTION -> R.string.wireless_hint_negotiating_projection
}

@Composable
internal fun wirelessNoticeText(notice: WirelessNotice): String = when (notice) {
    WirelessNotice.IdentityMissing -> stringResource(R.string.notice_identity_missing)
    is WirelessNotice.IdentityInvalid -> stringResource(R.string.notice_identity_invalid, notice.reason)
    WirelessNotice.BluetoothAddressMissing -> stringResource(R.string.wireless_notice_bt_address)
    WirelessNotice.BluetoothUnsupported -> stringResource(R.string.wireless_notice_bt_unsupported)
    WirelessNotice.BluetoothOff -> stringResource(R.string.wireless_notice_bt_off)
    WirelessNotice.HotspotUnsupported -> stringResource(R.string.wireless_notice_hotspot_unsupported)
    is WirelessNotice.PermissionsDenied -> when {
        notice.approximateOnly -> stringResource(R.string.wireless_notice_precise_location)
        notice.permanently -> stringResource(R.string.wireless_notice_permissions_permanent, permissionNames(notice.missing))
        else -> stringResource(R.string.wireless_notice_permissions, permissionNames(notice.missing))
    }
    WirelessNotice.WiredActive -> stringResource(R.string.wireless_notice_wired_active)
    WirelessNotice.WiredOnly -> stringResource(R.string.wireless_notice_wired_only)
    is WirelessNotice.TrustStoreCorrupt -> stringResource(R.string.notice_trust_corrupt, notice.reason)
    is WirelessNotice.TrustStoreUnavailable -> stringResource(R.string.notice_trust_unavailable, notice.reason)
    is WirelessNotice.StartFailed -> stringResource(R.string.notice_start_failed, notice.reason)
    is WirelessNotice.Ended -> endedText(notice.cause, notice.direct)
}

@Composable
private fun permissionNames(permissions: List<String>): String {
    val names = permissions.map {
        stringResource(
            when (it) {
                WirelessPermissions.BLUETOOTH_CONNECT -> R.string.wireless_perm_bluetooth
                WirelessPermissions.NEARBY_WIFI_DEVICES -> R.string.wireless_perm_nearby_wifi
                else -> R.string.wireless_perm_location
            },
        )
    }
    return names.joinToString(stringResource(R.string.wireless_perm_list_separator))
}

@Composable
private fun endedText(cause: WirelessEndCause, direct: Boolean): String {
    val reason = (cause as? WirelessEndCause.Session)?.reason
    // WiFi 段的结束与有线相同，沿用有线的说法。
    if (reason is WirelessEndReason.Wired) return noticeText(Notice.Ended(EndCause.Session(reason.reason)))
    val text = when (cause) {
        WirelessEndCause.UserStop -> stringResource(R.string.end_user_stop)
        is WirelessEndCause.Failure -> stringResource(R.string.end_failure, cause.message)
        is WirelessEndCause.Session -> wirelessEndText(cause.reason, direct)
    }
    return stringResource(R.string.wireless_notice_ended, text)
}

/** 常见原因给一句好懂的话；会话层自带的说明已经是中文、不含密钥和设备标识，放在括号里。 */
@Composable
private fun wirelessEndText(reason: WirelessEndReason, direct: Boolean): String = when (reason) {
    WirelessEndReason.LocalStop -> stringResource(R.string.end_user_stop)
    is WirelessEndReason.Wired -> reason.toString()
    is WirelessEndReason.AuthFailed -> when (reason.failure) {
        is AuthFailure.ConnectionClosed -> stringResource(R.string.wireless_end_auth_closed)
        AuthFailure.NotTrusted -> stringResource(R.string.wireless_end_not_trusted)
        else -> stringResource(R.string.wireless_end_auth_failed, reason.failure.toString())
    }
    is WirelessEndReason.Failed -> stringResource(
        when (reason.step) {
            SessionStep.BLUETOOTH -> R.string.wireless_end_step_bluetooth
            SessionStep.AUTH -> R.string.wireless_end_step_auth
            SessionStep.NEGOTIATION -> R.string.wireless_end_step_negotiation
            SessionStep.WIFI_NETWORK -> if (direct) R.string.wireless_end_step_direct else R.string.wireless_end_step_hotspot
            SessionStep.PHONE_JOIN -> if (direct) R.string.wireless_end_step_join_direct else R.string.wireless_end_step_join_hotspot
        },
        reason.detail,
    )
    is WirelessEndReason.ReconnectFailed ->
        if (reason.needsPairing) stringResource(R.string.wireless_end_reconnect_pairing) else stringResource(R.string.wireless_end_reconnect, reason.detail)
    is WirelessEndReason.InternalError -> stringResource(R.string.wireless_end_internal, reason.message)
}

/** 这次连接里 BLE 广播的情况；停了不显示。 */
@Composable
internal fun wirelessBeaconText(status: BeaconStatus): String? = when (status) {
    BeaconStatus.Starting -> stringResource(R.string.wireless_beacon_starting)
    is BeaconStatus.Advertising -> stringResource(R.string.wireless_beacon_advertising, status.publishes)
    is BeaconStatus.Retrying -> stringResource(R.string.wireless_beacon_retrying, status.reason, (status.retryInMillis / 1000).toInt())
    is BeaconStatus.Failed -> stringResource(R.string.wireless_beacon_failed, status.reason)
    BeaconStatus.Stopped -> null
}

/** 「自动连接手机」现在能不能起作用（无线页空闲时一行说明）；开关关着不说。 */
@Composable
internal fun wirelessAutoHoldText(hold: AutoHold): String? = when (hold) {
    AutoHold.READY -> stringResource(R.string.wireless_auto_hold_ready)
    AutoHold.NO_KNOWN_PHONE -> stringResource(R.string.wireless_auto_hold_no_known)
    AutoHold.OVERLAY_MISSING -> stringResource(R.string.wireless_auto_hold_overlay)
    AutoHold.UI_NOT_SHOWN -> stringResource(R.string.wireless_auto_hold_ui_not_shown)
    AutoHold.WIRED_ONLY -> stringResource(R.string.wireless_auto_hold_wired_only)
    AutoHold.DISABLED -> null
    AutoHold.PAUSED_BY_USER -> stringResource(R.string.wireless_auto_hold_paused)
    AutoHold.WIRED_ACTIVE -> stringResource(R.string.wireless_auto_hold_wired)
    AutoHold.HOTSPOT_UNSUPPORTED -> stringResource(R.string.wireless_auto_hold_hotspot_unsupported)
    AutoHold.IDENTITY_MISSING -> stringResource(R.string.wireless_auto_hold_identity)
    AutoHold.NO_PAIRED_PHONE -> stringResource(R.string.wireless_auto_hold_no_phone)
    AutoHold.BLUETOOTH_OFF -> stringResource(R.string.wireless_auto_hold_bt_off)
    AutoHold.PERMISSIONS_MISSING -> stringResource(R.string.wireless_auto_hold_permissions)
    AutoHold.ADVERTISING_UNSUPPORTED -> stringResource(R.string.wireless_auto_hold_unsupported)
    AutoHold.BLUETOOTH_ADDRESS_MISSING -> stringResource(R.string.wireless_auto_hold_address)
    AutoHold.FOREGROUND_UNAVAILABLE -> stringResource(R.string.wireless_auto_hold_foreground)
    AutoHold.START_FAILED -> stringResource(R.string.wireless_auto_hold_failed)
}
