package io.github.gcbond.openhc.ui

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.wireless.LePairingFailure
import io.github.gcbond.openhc.wireless.LePairingRules
import io.github.gcbond.openhc.wireless.LePairingSession
import io.github.gcbond.openhc.wireless.LePairingStage
import io.github.gcbond.openhc.wireless.LePairingStatus

/**
 * 「连接方式」组里的一行：补全手机蓝牙配对。点它（安卓 12 起先要「附近的设备」权限）弹出对话框，对话框开着期间
 * 开配对入口（[LePairingSession]），点「完成」「取消」、按返回、或设置页被投屏页换掉时收掉入口、改回车机蓝牙名称。
 */
@Composable
internal fun LePairingSetting() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // 给没给都打开对话框：缺权限时里面写着原因。
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { open = true }
    ValueRow(
        title = stringResource(R.string.settings_le_pair),
        summary = stringResource(R.string.settings_le_pair_summary),
        value = stringResource(R.string.settings_le_pair_value),
        description = stringResource(R.string.settings_le_pair_hint),
    ) {
        val missing = LePairingRules.requiredPermissions(Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            open = true
        } else {
            try {
                permissions.launch(missing.toTypedArray())
            } catch (e: ActivityNotFoundException) {
                open = true
            }
        }
    }
    if (open) LePairingDialog(onClose = { open = false })
}

@Composable
private fun LePairingDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val session = remember(context) { LePairingSession(context) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close("对话框关了") }
    }
    val status by session.status.collectAsState()
    // 车机屏幕大，误触多：点框外不关，按返回、点按钮才关。
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false)) {
        val compact = LocalCompact.current
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dimens.CardRadius))
                .background(Palette.Raised)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.le_pair_title), style = UiType.status, color = Palette.Text)
            Text(
                pairingMessage(status),
                style = UiType.rowTitle(compact).copy(lineHeight = 1.5.em, fontWeight = FontWeight.Normal),
                color = if (status.stage == LePairingStage.FAILED) Palette.Warn else Palette.Text,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(stringResource(R.string.le_pair_cancel), onClick = onClose)
                ActionButton(stringResource(R.string.le_pair_done), onClick = onClose, kind = ActionKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun pairingMessage(status: LePairingStatus): String {
    val name = LePairingRules.PAIRING_NAME
    val detail = status.detail ?: stringResource(R.string.le_pair_reason_unknown)
    return when (status.stage) {
        LePairingStage.PREPARING -> stringResource(R.string.le_pair_preparing)
        LePairingStage.ADVERTISING -> stringResource(R.string.le_pair_advertising, name)
        LePairingStage.BONDING -> stringResource(R.string.le_pair_bonding)
        LePairingStage.PAIRED -> stringResource(R.string.le_pair_paired)
        LePairingStage.BOND_FAILED -> stringResource(R.string.le_pair_bond_failed, name)
        LePairingStage.EXPIRED -> stringResource(R.string.le_pair_expired)
        LePairingStage.FAILED -> when (status.failure) {
            LePairingFailure.NO_BLUETOOTH -> stringResource(R.string.le_pair_failed_no_bluetooth)
            LePairingFailure.BLUETOOTH_OFF -> stringResource(R.string.le_pair_failed_bluetooth_off)
            LePairingFailure.PERMISSIONS -> stringResource(R.string.le_pair_failed_permissions)
            LePairingFailure.NAME_UNKNOWN -> stringResource(R.string.le_pair_failed_name_unknown, name)
            LePairingFailure.NAME_NOT_SAVED -> stringResource(R.string.le_pair_failed_name_not_saved)
            LePairingFailure.RENAME_REFUSED -> stringResource(R.string.le_pair_failed_rename)
            LePairingFailure.GATT -> stringResource(R.string.le_pair_failed_gatt, detail)
            LePairingFailure.ADVERTISING_UNSUPPORTED -> stringResource(R.string.le_pair_failed_unsupported)
            LePairingFailure.ADVERTISING_BUSY -> stringResource(R.string.le_pair_failed_busy)
            LePairingFailure.ADVERTISING, null -> stringResource(R.string.le_pair_failed_advertising, detail)
        }
    }
}
