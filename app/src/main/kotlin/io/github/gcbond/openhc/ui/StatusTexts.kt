package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.EndCause
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.hisight.HiSightEndReason
import io.github.gcbond.openhc.core.session.EndReason
import io.github.gcbond.openhc.platform.usb.WiredEndReason

// 连接状态 → 界面文字。

@Composable
internal fun phaseTitle(phase: Phase): String = stringResource(
    when (phase) {
        Phase.IDLE -> R.string.phase_idle
        Phase.PREPARING -> R.string.phase_preparing
        Phase.WAITING_FOR_PHONE -> R.string.phase_waiting_for_phone
        Phase.USB_DEBUGGING_ON -> R.string.phase_usb_debugging
        Phase.WAITING_USB_PERMISSION -> R.string.phase_usb_permission
        Phase.CONFIRM_ON_PHONE -> R.string.phase_confirm_on_phone
        Phase.CONNECTING -> R.string.phase_connecting
        Phase.PAIRING -> R.string.phase_pairing
        Phase.PROJECTING -> R.string.phase_projecting
        Phase.STOPPING -> R.string.phase_stopping
        Phase.REPLUG_REQUIRED -> R.string.phase_replug
    },
)

@Composable
internal fun phaseHint(state: ConnectionUiState): String {
    state.step?.let { return stringResource(stepHint(it)) }
    return when (state.phase) {
        Phase.IDLE -> stringResource(R.string.hint_idle)
        Phase.PREPARING -> stringResource(R.string.hint_preparing)
        Phase.WAITING_FOR_PHONE -> stringResource(R.string.hint_waiting_for_phone)
        Phase.USB_DEBUGGING_ON -> stringResource(R.string.hint_usb_debugging)
        Phase.WAITING_USB_PERMISSION -> stringResource(R.string.hint_usb_permission_phone)
        Phase.CONFIRM_ON_PHONE -> stringResource(R.string.hint_phone_switch)
        Phase.CONNECTING -> stringResource(R.string.hint_connecting)
        Phase.PAIRING ->
            stringResource(if (state.pinCode != null) R.string.hint_pairing else R.string.hint_pairing_wait)
        Phase.PROJECTING -> {
            val video = state.video
            when {
                video == null -> stringResource(R.string.hint_negotiating)
                !state.firstFrame -> stringResource(R.string.hint_projecting_waiting_frame, video.width, video.height, video.fps)
                !video.touchInput -> stringResource(R.string.hint_projecting_no_touch, video.width, video.height, video.fps)
                else -> stringResource(R.string.hint_projecting, video.width, video.height, video.fps)
            }
        }
        Phase.STOPPING -> stringResource(R.string.hint_stopping)
        Phase.REPLUG_REQUIRED -> stringResource(R.string.hint_replug_unplug)
    }
}

private fun stepHint(step: Step): Int = when (step) {
    Step.USB_PERMISSION_PHONE -> R.string.hint_usb_permission_phone
    Step.USB_PERMISSION_NETWORK -> R.string.hint_usb_permission_network
    Step.SENDING_CAR_INFO -> R.string.hint_sending_car_info
    Step.PHONE_SWITCH -> R.string.hint_phone_switch
    Step.PHONE_SWITCH_SLOW -> R.string.hint_phone_switch_slow
    Step.OPENING_NETWORK -> R.string.hint_opening_network
    Step.WAITING_PHONE_LINK -> R.string.hint_waiting_phone_link
    Step.HANDSHAKE -> R.string.hint_handshake
    Step.WAITING_AUTH -> R.string.hint_waiting_auth
    Step.AUTHENTICATING -> R.string.hint_authenticating
    Step.DEVICE_SESSION -> R.string.hint_device_session
    Step.WAITING_PROJECTION -> R.string.hint_waiting_projection
    Step.NEGOTIATING -> R.string.hint_negotiating
    Step.REPLUG_UNPLUG -> R.string.hint_replug_unplug
    Step.REPLUG_WAIT -> R.string.hint_replug_wait
}

@Composable
internal fun noticeText(notice: Notice): String = when (notice) {
    Notice.IdentityMissing -> stringResource(R.string.notice_identity_missing)
    is Notice.IdentityInvalid -> stringResource(R.string.notice_identity_invalid, notice.reason)
    Notice.VpnConsentRequired -> stringResource(R.string.notice_vpn_required)
    is Notice.VpnUnavailable -> stringResource(R.string.notice_vpn_unavailable, notice.reason)
    is Notice.TrustStoreCorrupt -> stringResource(R.string.notice_trust_corrupt, notice.reason)
    is Notice.TrustStoreUnavailable -> stringResource(R.string.notice_trust_unavailable, notice.reason)
    is Notice.StartFailed -> stringResource(R.string.notice_start_failed, notice.reason)
    is Notice.Ended -> stringResource(R.string.notice_ended, endCauseText(notice.cause))
}

@Composable
private fun endCauseText(cause: EndCause): String = when (cause) {
    EndCause.UserStop -> stringResource(R.string.end_user_stop)
    is EndCause.Wired -> {
        val text = stringResource(wiredEndText(cause.reason))
        cause.detail?.let { stringResource(R.string.end_with_detail, text, it) } ?: text
    }
    is EndCause.Session -> sessionEndText(cause.reason)
    is EndCause.Failure -> stringResource(R.string.end_failure, cause.message)
}

private fun wiredEndText(reason: WiredEndReason): Int = when (reason) {
    WiredEndReason.STOPPED -> R.string.end_user_stop
    WiredEndReason.BUSY -> R.string.end_wired_busy
    WiredEndReason.USB_HOST_UNSUPPORTED -> R.string.end_wired_usb_host_unsupported
    WiredEndReason.VPN_CONSENT_REQUIRED -> R.string.end_wired_vpn_consent
    WiredEndReason.VPN_UNAVAILABLE -> R.string.end_wired_vpn_unavailable
    WiredEndReason.VPN_FAILED -> R.string.end_wired_vpn_failed
    WiredEndReason.PHONE_NOT_FOUND -> R.string.end_wired_phone_not_found
    WiredEndReason.USB_PERMISSION_DENIED -> R.string.end_wired_usb_denied
    WiredEndReason.USB_PERMISSION_TIMEOUT -> R.string.end_wired_usb_timeout
    WiredEndReason.CAR_INFO_FAILED -> R.string.end_wired_car_info_failed
    WiredEndReason.PHONE_DID_NOT_SWITCH -> R.string.end_wired_phone_did_not_switch
    WiredEndReason.USB_NETWORK_UNUSABLE -> R.string.end_wired_usb_network_unusable
    WiredEndReason.PHONE_UNPLUGGED -> R.string.end_wired_phone_unplugged
    WiredEndReason.LINK_LOST -> R.string.end_wired_link_lost
    WiredEndReason.VPN_REVOKED -> R.string.end_wired_vpn_revoked
    WiredEndReason.TUNNEL_CLOSED -> R.string.end_wired_tunnel_closed
    WiredEndReason.ERROR -> R.string.end_wired_error
}

/** 常见原因给一句好懂的话；其余的用会话层自带的说明（已经是中文，不含密钥和设备标识）。 */
@Composable
private fun sessionEndText(reason: EndReason): String = when (reason) {
    EndReason.LocalStop -> stringResource(R.string.end_user_stop)
    EndReason.ControlHandshakeTimeout -> stringResource(R.string.end_session_handshake_timeout)
    is EndReason.AuthFailed -> when (reason.failure) {
        is AuthFailure.ConnectionClosed -> stringResource(R.string.end_session_auth_closed)
        AuthFailure.NotTrusted -> stringResource(R.string.end_session_not_trusted)
        else -> stringResource(R.string.end_session_auth_failed, reason.failure.toString())
    }
    is EndReason.KeepAliveTimeout -> stringResource(R.string.end_session_keepalive)
    is EndReason.CarDesktopRequested -> stringResource(R.string.end_car_desktop)
    is EndReason.ProjectionEnded -> when (reason.reason) {
        HiSightEndReason.LOCAL_SHUTDOWN -> stringResource(R.string.end_user_stop)
        HiSightEndReason.PEER_TEARDOWN -> stringResource(R.string.end_projection_teardown)
        HiSightEndReason.CONTROL_CLOSED -> stringResource(R.string.end_projection_control_closed)
        HiSightEndReason.VIDEO_CLOSED -> stringResource(R.string.end_projection_video_closed)
        HiSightEndReason.REMOTE_CONTROL_CLOSED -> stringResource(R.string.end_projection_remote_closed)
    }
    is EndReason.ProtocolError, is EndReason.PeerClosed, is EndReason.NetworkError,
    is EndReason.VideoSinkFailed, is EndReason.InternalError,
    -> reason.toString()
}
