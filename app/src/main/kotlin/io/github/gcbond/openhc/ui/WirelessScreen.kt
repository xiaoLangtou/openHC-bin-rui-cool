package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页左栏「无线连接」上的操作。 */
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
 * 首页左栏「无线连接」：顶上一行「无线连接 · 状态」，等扫码时中间是二维码和下面一排连接码，
 * 其余时候是一段居中的说明、要注意的事和操作按钮。
 *
 * [wirelessOff]：设置里开着「只用数据线连接」、也没有正在进行的无线连接，整张卡片写明无线已关闭。
 * [fillHeight]：两栏时卡片撑满高度，二维码按剩下的空间算大小；单栏（整页滚动）时按宽度算。
 */
@Composable
internal fun WirelessCard(
    state: WirelessUiState,
    identityLoaded: Boolean,
    wirelessOff: Boolean,
    actions: WirelessActions,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompact.current
    val qr = state.qrCode.takeIf { state.phase == WirelessPhase.WAITING_FOR_SCAN }
    val problem = !wirelessOff && state.notice != null
    val status = if (wirelessOff) stringResource(R.string.home_wireless_off_title) else wirelessPhaseTitle(state)
    CardBox(modifier) {
        Text(
            stringResource(R.string.home_wireless_header, status),
            style = UiType.cardHeader(compact),
            color = if (problem) Palette.Warn else Palette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = if (compact) 2.dp else 6.dp),
        )
        if (qr != null) {
            ScanContent(state, qr, actions, fillHeight)
        } else {
            StatusContent(state, identityLoaded, wirelessOff, actions, fillHeight)
        }
    }
}

/** 等扫码：一行说明，中间二维码和连接码（按剩下的空间取大小），底下广播状态和「停止」。 */
@Composable
private fun ColumnScope.ScanContent(state: WirelessUiState, qr: String, actions: WirelessActions, fillHeight: Boolean) {
    val compact = LocalCompact.current
    Text(wirelessHint(state), style = UiType.hint, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    BoxWithConstraints(
        (if (fillHeight) Modifier.weight(1f) else Modifier).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val pin = state.pinCode
        val height = if (constraints.hasBoundedHeight) maxHeight.value else Float.POSITIVE_INFINITY
        val size = QrPinLayout.plan(maxWidth.value, height, pin?.length ?: 0, compact)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
            // 码图自带 2 个模块的白边，卡片是深色的：外面再补约 2 个模块的白边，静区凑够 4 个模块，好扫。
            QrCodeImage(
                qr,
                Modifier
                    .size(size.qr.dp)
                    .background(Color.White)
                    .padding((size.qr * QR_EXTRA_QUIET).dp),
            )
            if (pin != null && size.pinBox > 0f) {
                Spacer(Modifier.height(size.pinTop.dp))
                PinBoxes(pin, size.pinBox.dp, size.pinGap.dp, description = stringResource(R.string.home_pin_description, pin))
            }
            Spacer(Modifier.height(QrPinLayout.QR_MARGIN.dp))
        }
    }
    if (state.addressMismatch) {
        Text(stringResource(R.string.wireless_warning_manual_address), style = UiType.hint, color = Palette.Warn)
    }
    state.notice?.let {
        Text(wirelessNoticeText(it), style = UiType.hint, color = Palette.Warn, modifier = Modifier.padding(top = 4.dp))
    }
    // 底部一行：左边无线方式和广播情况（小字），右边「停止」；不挤占二维码的高度。
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(wirelessNetworkText(state), state.beacon?.let { wirelessBeaconText(it) }).joinToString("\n"),
            style = UiType.hint,
            color = Palette.Muted,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        PillButton(
            stringResource(R.string.action_stop),
            actions.onStop,
            primary = false,
            enabled = state.phase != WirelessPhase.STOPPING,
        )
    }
}

/** 二维码外面补的白边占边长的比例（约 2 个模块）。 */
private const val QR_EXTRA_QUIET = 0.04f

/** 没有二维码时：居中一段说明，下面是连接码（手机连上蓝牙、还没认证完时还留着）、要注意的事和操作按钮。 */
@Composable
private fun ColumnScope.StatusContent(
    state: WirelessUiState,
    identityLoaded: Boolean,
    wirelessOff: Boolean,
    actions: WirelessActions,
    fillHeight: Boolean,
) {
    if (fillHeight) {
        // 内容少时在卡片里上下居中，多了（矮屏）就能滚动。
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusBody(state, identityLoaded, wirelessOff, actions)
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusBody(state, identityLoaded, wirelessOff, actions)
        }
    }
}

@Composable
private fun StatusBody(state: WirelessUiState, identityLoaded: Boolean, wirelessOff: Boolean, actions: WirelessActions) {
    val compact = LocalCompact.current
    val message = if (wirelessOff) stringResource(R.string.home_wireless_off_note) else wirelessHint(state)
    Text(message, style = UiType.message(compact), color = Palette.Text, textAlign = TextAlign.Center)
    if (state.phase == WirelessPhase.IDLE && !wirelessOff) {
        Text(
            stringResource(R.string.wireless_tagline),
            style = UiType.hint,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (!wirelessOff) CenteredLine(wirelessNetworkText(state), Palette.Muted)
    state.pinCode?.let { pin ->
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            val (box, gap) = QrPinLayout.pinRow(
                maxWidth.value,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            PinBoxes(
                pin,
                box.dp,
                gap.dp,
                Modifier.fillMaxWidth(),
                description = stringResource(R.string.home_pin_description, pin),
            )
        }
    }
    if (state.addressMismatch && state.phase != WirelessPhase.IDLE) {
        CenteredLine(stringResource(R.string.wireless_warning_manual_address), Palette.Warn)
    }
    // 「只用数据线连接」开着时，卡片本身已经写明了，不再重复这条提示。
    state.notice?.takeUnless { wirelessOff && it == WirelessNotice.WiredOnly }?.let {
        CenteredLine(wirelessNoticeText(it), Palette.Warn)
    }
    // 自动回连没在等的原因：只是说明，不当成错误。
    val hold = state.autoHold
    if (state.phase == WirelessPhase.IDLE && hold != null && !wirelessOff) {
        wirelessAutoHoldText(hold)?.let { CenteredLine(it, Palette.Muted) }
    }
    state.beacon?.let { wirelessBeaconText(it) }?.let { CenteredLine(it, Palette.Muted) }
    WirelessButtons(state, identityLoaded, wirelessOff, actions, Modifier.padding(top = 16.dp))
}

@Composable
private fun CenteredLine(text: String, color: Color) {
    Text(text, style = UiType.hint, color = color, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WirelessButtons(
    state: WirelessUiState,
    identityLoaded: Boolean,
    wirelessOff: Boolean,
    actions: WirelessActions,
    modifier: Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.phase != WirelessPhase.IDLE) {
            if (state.phase == WirelessPhase.PROJECTING) {
                PillButton(stringResource(R.string.action_show_projection), actions.onShowProjection)
            }
            PillButton(
                stringResource(R.string.action_stop),
                actions.onStop,
                primary = false,
                enabled = state.phase != WirelessPhase.STOPPING,
            )
            return@FlowRow
        }
        if (wirelessOff) {
            PillButton(stringResource(R.string.wireless_action_open_settings), actions.onOpenSettings)
            return@FlowRow
        }
        val start = stringResource(R.string.wireless_action_start)
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
            WirelessNotice.WiredOnly ->
                PillButton(stringResource(R.string.wireless_action_open_settings), actions.onOpenSettings)
            is WirelessNotice.TrustStoreCorrupt ->
                PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore)
            else -> PillButton(start, actions.onStart, enabled = identityLoaded)
        }
    }
}
