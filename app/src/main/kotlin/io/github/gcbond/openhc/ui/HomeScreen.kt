package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页上和有线连接有关的操作。 */
internal class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
)

/** 内容宽不到这个值就排单栏（上无线、下有线，整页滚动）。 */
private val TWO_COLUMN_MIN = 560.dp

/** 两栏的宽度比例：左边无线（二维码要地方），右边有线。 */
private const val WIRELESS_WEIGHT = 1.6f
private const val WIRED_WEIGHT = 1f

/**
 * 首页：左上应用名、右上「设置」；左栏无线（二维码、连接码），右栏有线（状态、提示、配对时的连接码）；
 * 左下角版本和当前模式。缺身份参数、或车机 WiFi 要车主自己打开时，顶上一张提示卡片。
 * 任何一边在投屏时，那一栏里有「回到投屏画面」。
 * 窄屏排成一列：无线在上、有线在下，整页滚动。
 */
@Composable
internal fun HomeScreen(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identity: IdentityLoader.Result,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    onShowProjection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { WirelessPreferences(context) }
    val identityLoaded = identity is IdentityLoader.Result.Loaded
    // 设置是在设置页里改的，回到首页时首页重新组合，这里每次读到的都是新值。
    val wirelessOff = prefs.wiredOnly && !wireless.active
    val wiredState = wired.withoutIdentityNotice()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val twoColumn = maxWidth - side * 2 >= TWO_COLUMN_MIN
        CompositionLocalProvider(LocalCompact provides compact) {
            if (twoColumn) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = side),
                ) {
                    HomeTopBar(onOpenSettings)
                    IdentityHomeNotice(identity, Modifier.padding(top = 8.dp))
                    WifiRestoreCard(Modifier.padding(top = 8.dp))
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 10.dp),
                    ) {
                        WirelessCard(
                            wireless,
                            identityLoaded,
                            wirelessOff,
                            wirelessActions,
                            fillHeight = true,
                            modifier = Modifier
                                .weight(WIRELESS_WEIGHT)
                                .fillMaxHeight(),
                        )
                        Spacer(Modifier.width(24.dp))
                        Column(
                            Modifier
                                .weight(WIRED_WEIGHT)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            WiredCard(wiredState, identityLoaded, actions, onShowProjection, Modifier.fillMaxWidth())
                        }
                    }
                    HomeFooter(prefs)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = side),
                ) {
                    HomeTopBar(onOpenSettings)
                    IdentityHomeNotice(identity, Modifier.padding(top = 8.dp))
                    WifiRestoreCard(Modifier.padding(top = 8.dp))
                    WirelessCard(
                        wireless,
                        identityLoaded,
                        wirelessOff,
                        wirelessActions,
                        fillHeight = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    WiredCard(
                        wiredState,
                        identityLoaded,
                        actions,
                        onShowProjection,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    HomeFooter(prefs, Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** 顶栏：左边应用名，右上「设置」（设置页的「返回」在同一个位置）。 */
@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit) {
    val compact = LocalCompact.current
    Row(Modifier.fillMaxWidth()) {
        PageTitle(
            stringResource(R.string.app_name),
            Modifier
                .weight(1f)
                .padding(top = if (compact) 16.dp else 24.dp, end = 16.dp),
        )
        TopBarButton(
            stringResource(R.string.action_settings),
            onOpenSettings,
            Modifier.padding(top = 12.dp, end = 16.dp),
        )
    }
}

/** 右栏「有线连接」：小标题、状态、提示，配对时一排连接码，上次结束的原因，操作按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WiredCard(
    state: ConnectionUiState,
    identityLoaded: Boolean,
    actions: HomeActions,
    onShowProjection: () -> Unit,
    modifier: Modifier,
) {
    val compact = LocalCompact.current
    // 要用户动手处理的状态（关 USB 调试、拔线重插、手机迟迟不响应）用提醒色。
    val warning = state.phase == Phase.USB_DEBUGGING_ON || state.phase == Phase.REPLUG_REQUIRED ||
        state.step == Step.PHONE_SWITCH_SLOW
    CardBox(modifier) {
        Text(stringResource(R.string.home_wired_title), style = UiType.cardHeading, color = Palette.Accent)
        Text(
            phaseTitle(state.phase),
            style = UiType.status,
            color = if (warning) Palette.Warn else Palette.Text,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            phaseHint(state),
            style = UiType.hint,
            color = if (warning) Palette.Warn else Palette.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        val pin = state.pinCode
        if (state.phase == Phase.PAIRING && pin != null) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
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
        state.notice?.let {
            Text(noticeText(it), style = UiType.hint, color = Palette.Warn, modifier = Modifier.padding(top = 6.dp))
        }
        FlowRow(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.phase == Phase.IDLE) {
                when (state.notice) {
                    Notice.VpnConsentRequired ->
                        PillButton(stringResource(R.string.action_grant_vpn), actions.onGrantVpn)
                    is Notice.TrustStoreCorrupt ->
                        PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore)
                    else ->
                        PillButton(stringResource(R.string.action_start), actions.onStart, enabled = identityLoaded)
                }
            } else {
                if (state.phase == Phase.PROJECTING) {
                    PillButton(stringResource(R.string.action_show_projection), onShowProjection)
                }
                if (state.phase == Phase.REPLUG_REQUIRED) {
                    PillButton(stringResource(R.string.action_retry_now), actions.onRetryNow, primary = false)
                }
                PillButton(
                    stringResource(R.string.action_stop),
                    actions.onStop,
                    primary = false,
                    enabled = state.phase != Phase.STOPPING,
                )
            }
        }
    }
}

/**
 * 左下角：版本、当前模式，以及车型编号。
 * 底边这一行的居中位置空着，留给以后的主按钮（如「退出」）。
 */
@Composable
private fun HomeFooter(
    prefs: WirelessPreferences,
    modifier: Modifier = Modifier,
) {
    val lines = mutableListOf(
        stringResource(R.string.home_version, BuildConfig.VERSION_NAME),
        stringResource(R.string.home_mode, modeSummary(prefs)),
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            lines.joinToString("\n"),
            style = UiType.footer(LocalCompact.current),
            color = Palette.Muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 当前模式一句话：「无线 + 有线」或「只用数据线」，后面接开着的自动连接选项。首页页脚和设置页底部共用。 */
@Composable
internal fun modeSummary(prefs: WirelessPreferences): String {
    if (prefs.wiredOnly) return stringResource(R.string.home_mode_wired_only)
    val parts = mutableListOf(stringResource(R.string.home_mode_both))
    if (prefs.connectOnBluetooth) parts += stringResource(R.string.home_mode_connect_on_bt)
    return parts.joinToString(" · ")
}
