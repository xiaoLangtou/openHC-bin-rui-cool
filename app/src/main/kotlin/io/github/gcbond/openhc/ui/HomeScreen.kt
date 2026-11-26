package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.connection.Step
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessUiState

/** 首页上和有线连接有关的操作。 */
internal class HomeActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryNow: () -> Unit,
    val onGrantVpn: () -> Unit,
    val onResetTrustStore: () -> Unit,
    /** 车机上插着华为 / 荣耀手机。 */
    val phonePlugged: () -> Boolean,
)

/** 内容宽到这个值，等扫码时二维码和说明左右排、空闲时两种连法并排。 */
private val WIDE_CARD_MIN = 640.dp

/**
 * 首页：顶栏左边应用名、正中深浅色切换、右边「设置」；下面一张「连接手机」卡片，页脚是当前模式和版本。
 * 缺身份参数、或车机 WiFi 要车主自己打开时，卡片上面一条提示。
 *
 * 卡片不分有线、无线：空闲时写两种连法（点「开始连接」扫码、插数据线），按钮是「开始连接」（走无线扫码）；
 * 插线由 MainActivity 直接开始有线（插线优先）。开着「只用数据线连接」时不扫码、没有按钮，插上就连。
 * 有线在进行时卡片显示有线的状态，无线在进行时显示无线的（等扫码时是二维码）。窄屏整页滚动。
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
    val wiredOnly = prefs.wiredOnly
    val wiredState = wired.withoutIdentityNotice()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val wide = maxWidth - side * 2 >= WIDE_CARD_MIN
        // 够高才让卡片撑满一屏（二维码按剩下的空间取大小）；竖屏小屏整页滚动。
        val fill = wide || maxHeight >= maxWidth
        val gap = if (compact) 12.dp else 20.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (fill) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = side),
            ) {
                HomeTopBar(onOpenSettings, Modifier.padding(top = if (compact) 10.dp else 18.dp))
                IdentityHomeNotice(identity, Modifier.padding(top = gap))
                WifiRestoreCard(Modifier.padding(top = gap))
                HomeBanners(wiredState, wireless, wiredOnly, wirelessActions, Modifier.padding(top = gap))
                ConnectCard(
                    wiredState,
                    wireless,
                    identityLoaded,
                    wiredOnly,
                    actions,
                    wirelessActions,
                    onShowProjection,
                    fillHeight = fill,
                    wide = wide,
                    modifier = (if (fill) Modifier.weight(1f) else Modifier)
                        .fillMaxWidth()
                        .padding(top = gap, bottom = if (compact) 8.dp else 12.dp),
                )
                HomeFooter(prefs)
            }
        }
    }
}

/** 顶栏：左边「设置」（靠近驾驶位，和设置页的「返回」同一位置），正中深浅色切换，右边应用标记和名称。 */
@Composable
private fun HomeTopBar(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    TopBar(
        start = { TopBarButton(stringResource(R.string.action_settings), onOpenSettings, glyph = Glyphs.Gear) },
        end = {
            AppMark()
            PageTitle(stringResource(R.string.app_name))
        },
        modifier = modifier,
    )
}

/**
 * 首页的卡片：有线在进行就显示有线的状态，无线在进行就显示无线的（等扫码时是二维码），都没有就是两种连法和「开始连接」。
 * 除了等扫码，都是居中排：大图标、状态、一两句说明、按钮；内容少时在卡片里上下居中，多了能滚动。
 */
@Composable
private fun ConnectCard(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identityLoaded: Boolean,
    wiredOnly: Boolean,
    actions: HomeActions,
    wirelessActions: WirelessActions,
    onShowProjection: () -> Unit,
    fillHeight: Boolean,
    wide: Boolean,
    modifier: Modifier,
) {
    CardBox(modifier) {
        when {
            wired.phase != Phase.IDLE -> {
                // 要用户动手处理的状态（关 USB 调试、拔线重插、手机迟迟不响应）用提醒色。
                val warning = wired.phase == Phase.USB_DEBUGGING_ON || wired.phase == Phase.REPLUG_REQUIRED ||
                    wired.step == Step.PHONE_SWITCH_SLOW
                Hero(
                    fillHeight,
                    top = { StatusHead(Glyphs.Plug, phaseTitle(wired.phase), if (warning) Palette.Warn else Palette.Text) },
                    body = { WiredBody(wired, warning) },
                    buttons = { WiredButtons(wired, actions, onShowProjection) },
                )
            }
            wireless.phase != WirelessPhase.IDLE -> {
                val qr = wireless.qrCode.takeIf { wireless.phase == WirelessPhase.WAITING_FOR_SCAN }
                if (qr != null) {
                    WirelessScanContent(wireless, qr, wirelessActions, fillHeight, wide)
                } else {
                    Hero(
                        fillHeight,
                        top = { StatusHead(Glyphs.Wifi, wirelessPhaseTitle(wireless), Palette.Text) },
                        body = { WirelessStatusBody(wireless) },
                        buttons = { WirelessActiveButtons(wireless, wirelessActions) },
                    )
                }
            }
            else -> Hero(
                fillHeight,
                top = { Ways(wiredOnly, wide) },
                body = { IdleLines(wireless, wiredOnly) },
                buttons = { IdleButtons(wired, wireless, identityLoaded, wiredOnly, actions, wirelessActions) },
                buttonsFirst = true,
            )
        }
    }
}

/**
 * 居中的一屏：[top]（大图标和状态，或两种连法），[body]（说明），[buttons]。[buttonsFirst] 时按钮紧跟在 [top] 下面、
 * 说明放最后（空闲时「开始连接」要显眼）。[fillHeight] 时在卡片里上下居中，内容多了能滚动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Hero(
    fillHeight: Boolean,
    top: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable () -> Unit,
    buttonsFirst: Boolean = false,
) {
    val compact = LocalCompact.current
    val gap = if (compact) 14.dp else 24.dp
    val buttonRow: @Composable () -> Unit = {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { buttons() }
    }
    val content: @Composable ColumnScope.() -> Unit = {
        top()
        Spacer(Modifier.height(gap))
        CompositionLocalProvider(LocalCenteredText provides true) {
            if (buttonsFirst) {
                buttonRow()
                Spacer(Modifier.height(gap / 2))
                body()
            } else {
                body()
                Spacer(Modifier.height(gap))
                buttonRow()
            }
        }
    }
    if (fillHeight) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** 进行中的状态：大图标，下面状态（「等待插线」「正在连接」「投屏中」……）。 */
@Composable
private fun StatusHead(glyph: ImageVector, title: String, titleColor: Color) {
    val compact = LocalCompact.current
    IconTile(glyph, large = true)
    Text(
        title,
        style = UiType.cardTitle(compact),
        color = titleColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = if (compact) 10.dp else 16.dp),
    )
}

/** 空闲：两种连法并排（窄屏上下排；开着「只用数据线连接」时只有插线）。 */
@Composable
private fun Ways(wiredOnly: Boolean, wide: Boolean) {
    val scan: @Composable (Modifier) -> Unit = { m ->
        WayTile(Glyphs.Wifi, stringResource(R.string.home_way_scan_title), stringResource(R.string.home_way_scan_text), m)
    }
    val cable: @Composable (Modifier) -> Unit = { m ->
        WayTile(
            Glyphs.Plug,
            stringResource(R.string.home_way_cable_title),
            stringResource(if (wiredOnly) R.string.home_way_cable_text_only else R.string.home_way_cable_text),
            m,
        )
    }
    when {
        wiredOnly -> cable(Modifier.widthIn(max = WAY_TILE_MAX).fillMaxWidth())
        wide -> Row(
            Modifier.widthIn(max = WAY_TILE_MAX * 2 + 20.dp).fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            scan(Modifier.weight(1f).fillMaxHeight())
            cable(Modifier.weight(1f).fillMaxHeight())
        }
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            scan(Modifier.fillMaxWidth())
            cable(Modifier.fillMaxWidth())
        }
    }
}

private val WAY_TILE_MAX = 380.dp

/** 一种连法：上面大图标，下面名称和一句话，居中。 */
@Composable
private fun WayTile(glyph: ImageVector, title: String, text: String, modifier: Modifier) {
    val compact = LocalCompact.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.SegmentBackground)
            .padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(glyph, large = true)
        Text(
            title,
            style = UiType.cardTitle(compact).copy(fontSize = UiType.rowTitle(compact).fontSize * 1.15f),
            color = Palette.Text,
            modifier = Modifier.padding(top = if (compact) 10.dp else 14.dp),
        )
        Text(
            text,
            style = UiType.rowSummary(compact),
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 空闲时的小字：车机蓝牙和无线方式（或只用数据线），自动连接的情况（只是说明的那些）。要处理的事在上方提示条里。 */
@Composable
private fun IdleLines(wireless: WirelessUiState, wiredOnly: Boolean) {
    if (wiredOnly) {
        Line(stringResource(R.string.home_connect_wired_only_note), Palette.Muted)
    } else {
        Line(stringResource(R.string.wireless_tagline) + " " + wirelessNetworkText(wireless), Palette.Muted)
        WirelessIdleLines(wireless)
    }
}

/**
 * 空闲时要车主注意的事，放在卡片上方的琥珀色提示条里：上次连接为什么结束、为什么开始不了、自动连接缺什么
 * （缺的能直接去处理时带一个按钮）。连接进行中这些写在卡片里。
 */
@Composable
private fun HomeBanners(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    wiredOnly: Boolean,
    actions: WirelessActions,
    modifier: Modifier,
) {
    if (wired.phase != Phase.IDLE || wireless.phase != WirelessPhase.IDLE) return
    val wiredNotice = wired.notice?.let { noticeText(it) }
    val wirelessNotice = wireless.notice?.takeUnless { wiredOnly || it == WirelessNotice.WiredOnly }?.let { wirelessNoticeText(it) }
    // 缺身份参数由上面的身份提示卡片说，不重复。
    val hold = wireless.autoHold?.takeIf { !wiredOnly && it.needsAction() && it != AutoHold.IDENTITY_MISSING }
    val holdText = hold?.let { wirelessAutoHoldText(it) }
    if (wiredNotice == null && wirelessNotice == null && holdText == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        wiredNotice?.let { WarnBanner(it) }
        wirelessNotice?.let { WarnBanner(it) }
        if (hold != null && holdText != null) {
            val (label, onAction) = when (hold) {
                AutoHold.OVERLAY_MISSING, AutoHold.UI_NOT_SHOWN, AutoHold.BLUETOOTH_ADDRESS_MISSING ->
                    stringResource(R.string.home_banner_open_settings) to actions.onOpenSettings
                AutoHold.BLUETOOTH_OFF -> stringResource(R.string.wireless_action_enable_bt) to actions.onEnableBluetooth
                AutoHold.PERMISSIONS_MISSING -> stringResource(R.string.wireless_action_grant) to actions.onStart
                else -> null to null
            }
            WarnBanner(holdText, action = label, onAction = onAction)
        }
    }
}

@Composable
private fun IdleButtons(
    wired: ConnectionUiState,
    wireless: WirelessUiState,
    identityLoaded: Boolean,
    wiredOnly: Boolean,
    actions: HomeActions,
    wirelessActions: WirelessActions,
) {
    when {
        wired.notice == Notice.VpnConsentRequired -> PillButton(stringResource(R.string.action_grant_vpn), actions.onGrantVpn, wide = true)
        wired.notice is Notice.TrustStoreCorrupt ->
            PillButton(stringResource(R.string.action_reset_trust), actions.onResetTrustStore, wide = true)
        else -> {
            // 手机插着却没在连（点过停止、还没同意 VPN 授权、插线时界面没开）：给一个连它的按钮，不用拔了重插。
            val plugged = remember(wired.phase, wired.notice, wireless.phase) { actions.phonePlugged() }
            if (plugged) {
                PillButton(stringResource(R.string.home_connect_plugged), actions.onStart, primary = wiredOnly, wide = wiredOnly)
            }
            if (!wiredOnly) WirelessIdleButtons(wireless, identityLoaded, wirelessActions)
        }
    }
}

/** 有线连接中的正文：现在在做什么，配对时一排连接码，要注意的事。 */
@Composable
private fun WiredBody(state: ConnectionUiState, warning: Boolean) {
    val compact = LocalCompact.current
    BodyText(phaseHint(state), if (warning) Palette.Warn else Palette.Text2)
    val pin = state.pinCode
    if (state.phase == Phase.PAIRING && pin != null) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            val (box, gap) = QrPinLayout.pinRow(
                maxWidth.value,
                pin.length,
                if (compact) QrPinLayout.PIN_BOX_COMPACT else QrPinLayout.PIN_BOX,
            )
            PinBoxes(pin, box.dp, gap.dp, Modifier.fillMaxWidth(), description = stringResource(R.string.home_pin_description, pin))
        }
    }
    state.notice?.let { Line(noticeText(it), Palette.Warn) }
}

@Composable
private fun WiredButtons(state: ConnectionUiState, actions: HomeActions, onShowProjection: () -> Unit) {
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

/** 页脚：左边当前模式，右边版本。 */
@Composable
private fun HomeFooter(prefs: WirelessPreferences, modifier: Modifier = Modifier) {
    val style = UiType.footer(LocalCompact.current)
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (LocalCompact.current) 8.dp else 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(stringResource(R.string.home_mode, modeSummary(prefs)), style = style, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.home_version, BuildConfig.VERSION_NAME), style = style, color = Palette.Muted)
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
