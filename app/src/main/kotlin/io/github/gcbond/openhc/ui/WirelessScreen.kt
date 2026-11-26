package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

// 首页「连接手机」卡片里无线那部分：等扫码时的二维码和说明、连接中的状态、按钮。卡片怎么拼见 [HomeScreen]。

/** 首页上和无线连接有关的操作。 */
internal class WirelessActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onShowProjection: () -> Unit,
    /** 去本应用的设置页（填车机蓝牙地址、关「只用数据线连接」）。 */
    val onOpenSettings: () -> Unit,
    /** 去系统里本应用的设置页（权限被永久拒绝时）。 */
    val onOpenAppSettings: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onResetTrustStore: () -> Unit,
)

/**
 * 等扫码：[wide] 时二维码和连接码在左（按卡片剩下的高度取大小）、说明在右，否则二维码在上（按宽度、剩下的高度取大小）、
 * 说明在下；底下「停止」。[fillHeight] 为假时（整页滚动）不占满高度。
 */
@Composable
internal fun ColumnScope.WirelessScanContent(
    state: WirelessUiState,
    qr: String,
    actions: WirelessActions,
    fillHeight: Boolean,
    wide: Boolean,
) {
    val compact = LocalCompact.current
    if (wide && fillHeight) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val pin = state.pinCode
                QrAndPin(qr, pin, QrPinLayout.plan(maxWidth.value, maxHeight.value, pin?.length ?: 0, compact))
            }
            Spacer(Modifier.width(if (compact) 16.dp else 28.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(wirelessPhaseTitle(state), style = UiType.cardTitle(compact), color = Palette.Text)
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                ScanTexts(state)
                Spacer(Modifier.height(if (compact) 14.dp else 24.dp))
                StopButton(state, actions)
            }
        }
    } else {
        Text(
            wirelessPhaseTitle(state),
            style = UiType.cardTitle(compact),
            color = Palette.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        BoxWithConstraints(
            (if (fillHeight) Modifier.weight(1f) else Modifier)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pin = state.pinCode
            val height = if (constraints.hasBoundedHeight) maxHeight.value else Float.POSITIVE_INFINITY
            QrAndPin(qr, pin, QrPinLayout.plan(maxWidth.value, height, pin?.length ?: 0, compact))
        }
        CompositionLocalProvider(LocalCenteredText provides true) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ScanTexts(state)
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                StopButton(state, actions)
            }
        }
    }
}

@Composable
private fun StopButton(state: WirelessUiState, actions: WirelessActions) {
    PillButton(
        stringResource(R.string.action_stop),
        actions.onStop,
        primary = false,
        enabled = state.phase != WirelessPhase.STOPPING,
    )
}

/** 二维码，下面一排连接码。 */
@Composable
private fun QrAndPin(qr: String, pin: String?, size: QrPinSize) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
        // 码图自带 2 个模块的白边：外面再补约 2 个模块的白边，静区凑够 4 个模块，好扫。浅色时白卡片上再描一圈细线。
        QrCodeImage(
            qr,
            Modifier
                .size(size.qr.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, Palette.Divider, RoundedCornerShape(12.dp))
                .padding((size.qr * QR_EXTRA_QUIET).dp),
        )
        if (pin != null && size.pinBox > 0f) {
            Spacer(Modifier.height(size.pinTop.dp))
            PinBoxes(pin, size.pinBox.dp, size.pinGap.dp, description = stringResource(R.string.home_pin_description, pin))
        }
        Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
    }
}

/** 等扫码时的文字：怎么扫、也可以插线，要注意的事，无线方式和广播情况。 */
@Composable
private fun ScanTexts(state: WirelessUiState) {
    val compact = LocalCompact.current
    BodyText(wirelessHint(state))
    Line(stringResource(R.string.home_connect_or_cable), Palette.Muted)
    if (state.addressMismatch) Line(stringResource(R.string.wireless_warning_manual_address), Palette.Warn)
    state.notice?.let { Line(wirelessNoticeText(it), Palette.Warn) }
    Line(wirelessNetworkText(state), Palette.Muted)
    state.beacon?.let { wirelessBeaconText(it) }?.let { Line(it, Palette.Muted) }
}

/** 二维码外面补的白边占边长的比例（约 2 个模块）。 */
private const val QR_EXTRA_QUIET = 0.04f

/** 无线连接中（没有二维码时）的正文：现在在做什么，连接码（手机连上蓝牙、还没认证完时还留着），要注意的事。 */
@Composable
internal fun WirelessStatusBody(state: WirelessUiState) {
    val compact = LocalCompact.current
    BodyText(wirelessHint(state))
    Line(wirelessNetworkText(state), Palette.Muted)
    state.pinCode?.let { pin ->
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            val (box, gap) = QrPinLayout.pinRow(
                maxWidth.value,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            PinBoxes(pin, box.dp, gap.dp, Modifier.fillMaxWidth(), description = stringResource(R.string.home_pin_description, pin))
        }
    }
    if (state.addressMismatch) Line(stringResource(R.string.wireless_warning_manual_address), Palette.Warn)
    state.notice?.let { Line(wirelessNoticeText(it), Palette.Warn) }
    state.beacon?.let { wirelessBeaconText(it) }?.let { Line(it, Palette.Muted) }
}

/** 空闲时自动连接没在等的原因里只是说明的那些（要车主处理的在首页上方的提示条里，见 [needsAction]）。 */
@Composable
internal fun WirelessIdleLines(state: WirelessUiState) {
    state.autoHold?.takeUnless { it.needsAction() }?.let { wirelessAutoHoldText(it) }?.let { Line(it, Palette.Muted) }
}

/** 自动连接没在等的原因里，要车主动手处理的（权限、蓝牙、地址、系统不支持、没打开成）。 */
internal fun AutoHold.needsAction(): Boolean = when (this) {
    AutoHold.OVERLAY_MISSING, AutoHold.UI_NOT_SHOWN, AutoHold.HOTSPOT_UNSUPPORTED, AutoHold.IDENTITY_MISSING,
    AutoHold.BLUETOOTH_OFF, AutoHold.PERMISSIONS_MISSING, AutoHold.ADVERTISING_UNSUPPORTED,
    AutoHold.BLUETOOTH_ADDRESS_MISSING, AutoHold.START_FAILED,
    -> true
    else -> false
}

/** 首页卡片里文字是否居中（状态页居中，宽屏等扫码时右栏靠左）。 */
internal val LocalCenteredText = compositionLocalOf { false }

/** 卡片里的一两句正文。 */
@Composable
internal fun BodyText(text: String, color: Color = Palette.Text2) {
    val center = LocalCenteredText.current
    Text(
        text,
        style = UiType.cardText(LocalCompact.current),
        color = color,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
    )
}

/** 卡片里的一行小字（说明、提醒）。 */
@Composable
internal fun Line(text: String, color: Color) {
    val center = LocalCenteredText.current
    Text(
        text,
        style = UiType.hint,
        color = color,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.padding(top = 8.dp).then(if (center) Modifier.fillMaxWidth() else Modifier),
    )
}

/** 无线连接中的按钮：投屏时「回到投屏画面」，加上「停止」。 */
@Composable
internal fun WirelessActiveButtons(state: WirelessUiState, actions: WirelessActions) {
    if (state.phase == WirelessPhase.PROJECTING) {
        PillButton(stringResource(R.string.action_show_projection), actions.onShowProjection)
    }
    PillButton(
        stringResource(R.string.action_stop),
        actions.onStop,
        primary = false,
        enabled = state.phase != WirelessPhase.STOPPING,
    )
}

/** 空闲时的按钮：「开始连接」；缺权限、蓝牙没开等时换成去处理的按钮。 */
@Composable
internal fun WirelessIdleButtons(state: WirelessUiState, identityLoaded: Boolean, actions: WirelessActions) {
    val start = stringResource(R.string.home_connect_start)
    when (val notice = state.notice) {
        is WirelessNotice.PermissionsDenied -> if (notice.permanently) {
            PillButton(stringResource(R.string.wireless_action_app_settings), actions.onOpenAppSettings)
            PillButton(start, actions.onStart, primary = false)
        } else {
            PillButton(stringResource(R.string.wireless_action_grant), actions.onStart)
        }
        WirelessNotice.BluetoothOff -> {
            PillButton(stringResource(R.string.wireless_action_enable_bt), actions.onEnableBluetooth)
            PillButton(start, actions.onStart, primary = false)
        }
        WirelessNotice.BluetoothAddressMissing ->
            PillButton(stringResource(R.string.wireless_action_fill_bt), actions.onOpenSettings)
        is WirelessNotice.TrustStoreCorrupt ->
            PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore)
        else -> PillButton(start, actions.onStart, enabled = identityLoaded, wide = true)
    }
}
