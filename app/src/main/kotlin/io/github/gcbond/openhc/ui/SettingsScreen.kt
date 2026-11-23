package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.settings.AppSettings

/** 内容宽到这个值才排两列：两列各约 370dp 起，分段选择那排才放得下。 */
private val TWO_COLUMN_MIN = 760.dp

/** 一列最宽多少：大屏上不拉成一整条。 */
private val COLUMN_MAX = 640.dp
private val COLUMN_GAP = 16.dp

/**
 * 设置页：左上「设置」、右上「返回」（和首页的「设置」同一个位置）。宽屏排两列：左列「画面」「方向盘按键」，
 * 右列「连接方式」「启动」「声音」；版本行在底下横跨两列。
 * 窄屏排一列，先左列后右列。改动在下次连接时生效。
 *
 * 每个小节是独立的 composable，画一个分组（[SettingsGroup]）或分组里的一行；新加的行放进对应的分组里，
 * 新的分组放进 [SettingsLeftColumn] 或 [SettingsRightColumn]。
 */
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    screen: ScreenSpec,
    systemBluetoothAddress: String?,
    onBack: () -> Unit,
) {
    val wireless = rememberWirelessSettingsState()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .systemBarsPadding(),
    ) {
        val compact = maxHeight < Dimens.CompactHeight
        val side = if (maxWidth >= Dimens.WIDE_PAGE_MIN) Dimens.PageSide else Dimens.PageSideNarrow
        val twoColumn = maxWidth - side * 2 >= TWO_COLUMN_MIN
        val sectionGap = if (compact) 12.dp else 20.dp
        CompositionLocalProvider(LocalCompact provides compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = side),
            ) {
                SettingsTopBar(onBack)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = if (compact) 8.dp else 16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = if (twoColumn) COLUMN_MAX * 2 + COLUMN_GAP else COLUMN_MAX)
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    ) {
                        if (twoColumn) {
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                                    SettingsLeftColumn(settings, screen)
                                }
                                Spacer(Modifier.width(COLUMN_GAP))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                                    SettingsRightColumn(settings, systemBluetoothAddress, wireless)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) {
                                SettingsLeftColumn(settings, screen)
                                SettingsRightColumn(settings, systemBluetoothAddress, wireless)
                            }
                        }
                        Text(
                            stringResource(R.string.home_version, BuildConfig.VERSION_NAME) + "\n" +
                                stringResource(R.string.home_tagline),
                            style = UiType.footer(compact),
                            color = Palette.Muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 左列：画面、方向盘按键。 */
@Composable
private fun SettingsLeftColumn(settings: AppSettings, screen: ScreenSpec) {
    PictureSettingsSection(settings, screen)
    MediaKeySettingsSection()
}

/** 右列：连接方式、启动、声音、诊断。 */
@Composable
private fun SettingsRightColumn(
    settings: AppSettings,
    systemBluetoothAddress: String?,
    wireless: WirelessSettingsState,
) {
    SettingsGroup(stringResource(R.string.settings_group_connection)) {
        WiredOnlySetting(wireless)
        WirelessModeSetting(wireless)
        BluetoothAddressSection(settings, systemBluetoothAddress)
        LePairingSetting()
        UsbAutoConnectSettingsSection()
        RestoreWifiSetting()
    }
    BootStartSettingsSection(autoConnect = wireless.connectOnBluetooth && !wireless.wiredOnly) { ConnectOnBluetoothSetting(wireless) }
    AudioSettingsSection()
    DiagnosticsSettingsSection()
}

/** 顶栏：「设置」、一句生效说明、右上「返回」。 */
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageTitle(stringResource(R.string.settings_title))
        Text(
            stringResource(R.string.settings_apply_hint),
            style = UiType.cardHeader(LocalCompact.current),
            color = Palette.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        )
        TopBarButton(stringResource(R.string.action_back), onBack)
    }
}
