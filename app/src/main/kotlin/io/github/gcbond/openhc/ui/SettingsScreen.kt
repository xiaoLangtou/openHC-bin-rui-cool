package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.settings.AppSettings

/** 内容宽到这个值才在左边排分类目录。 */
private val RAIL_MIN = 700.dp

/**
 * 设置页：顶栏左边「返回」和「设置」，正中深浅色切换，右边一句生效说明。宽屏左边是分类目录（画面、连接方式、启动、
 * 声音、方向盘按键、诊断），右边只显示选中的那一类；窄屏不排目录，各组从上到下排一列。版本行在最下面。
 * 大部分设置下次连接时生效，个别项（自动连接、开机启动等）在各自说明里写了；回首页时有连接正用着旧设置会弹框提醒
 * （见 [io.github.gcbond.openhc.settings.ReconnectReminder]）。
 *
 * 每个小节是独立的 composable，画一个分组（[SettingsGroup]）或分组里的一行；新加的行放进对应的分组里，
 * 新的分组放进 [SettingsCategory] 和 [CategoryContent]。
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
        val rail = maxWidth - side * 2 >= RAIL_MIN
        val content: @Composable ColumnScope.(SettingsCategory) -> Unit = { category ->
            CategoryContent(category, settings, screen, systemBluetoothAddress, wireless)
        }
        CompositionLocalProvider(LocalCompact provides compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = side),
            ) {
                SettingsTopBar(onBack, showHint = rail, Modifier.padding(top = if (compact) 10.dp else 18.dp))
                if (rail) {
                    var selected by rememberSaveable { mutableStateOf(SettingsCategory.PICTURE) }
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = if (compact) 12.dp else 20.dp),
                    ) {
                        Column(
                            Modifier
                                .width(if (compact) 180.dp else 220.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (category in SettingsCategory.values()) {
                                NavItem(category, category == selected) { selected = category }
                            }
                        }
                        Spacer(Modifier.width(if (compact) 16.dp else 24.dp))
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                        ) {
                            CompositionLocalProvider(LocalGroupTitles provides false) { content(selected) }
                            VersionFooter()
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.settings_apply_hint),
                        style = UiType.footer(compact),
                        color = Palette.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp),
                    ) {
                        for (category in SettingsCategory.values()) content(category)
                        VersionFooter()
                    }
                }
            }
        }
    }
}

/** 设置的分类：左边目录的一项，对应一个分组。 */
private enum class SettingsCategory(val label: Int) {
    PICTURE(R.string.settings_group_picture),
    CONNECTION(R.string.settings_group_connection),
    STARTUP(R.string.settings_boot_title),
    AUDIO(R.string.settings_audio),
    KEYS(R.string.settings_media_keys),
    DIAGNOSTICS(R.string.settings_group_diagnostics),
}

private fun SettingsCategory.glyph(): ImageVector = when (this) {
    SettingsCategory.PICTURE -> Glyphs.Monitor
    SettingsCategory.CONNECTION -> Glyphs.Wifi
    SettingsCategory.STARTUP -> Glyphs.Power
    SettingsCategory.AUDIO -> Glyphs.Speaker
    SettingsCategory.KEYS -> Glyphs.Wheel
    SettingsCategory.DIAGNOSTICS -> Glyphs.Document
}

/** 一类设置的内容。 */
@Composable
private fun CategoryContent(
    category: SettingsCategory,
    settings: AppSettings,
    screen: ScreenSpec,
    systemBluetoothAddress: String?,
    wireless: WirelessSettingsState,
) {
    when (category) {
        SettingsCategory.PICTURE -> PictureSettingsSection(settings, screen)
        SettingsCategory.CONNECTION -> SettingsGroup(stringResource(R.string.settings_group_connection)) {
            WiredOnlySetting(wireless)
            WirelessModeSetting(wireless)
            if (!wireless.wifiDirect && !wireless.carHotspot) Hotspot5gSetting(wireless)
            BluetoothAddressSection(settings, systemBluetoothAddress)
            LePairingSetting()
            UsbAutoConnectSettingsSection()
            RestoreWifiSetting()
        }
        SettingsCategory.STARTUP ->
            BootStartSettingsSection(autoConnect = wireless.connectOnBluetooth && !wireless.wiredOnly) {
                ConnectOnBluetoothSetting(wireless)
            }
        SettingsCategory.AUDIO -> AudioSettingsSection()
        SettingsCategory.KEYS -> MediaKeySettingsSection()
        SettingsCategory.DIAGNOSTICS -> DiagnosticsSettingsSection()
    }
}

/** 左边目录的一项：图标和名称，选中的那项底色高亮。 */
@Composable
private fun NavItem(category: SettingsCategory, selected: Boolean, onClick: () -> Unit) {
    val compact = LocalCompact.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 46.dp else 54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.NavSelected else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            category.glyph(),
            contentDescription = null,
            tint = if (selected) Palette.Accent else Palette.Muted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(category.label),
            style = UiType.nav(compact).copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = if (selected) Palette.NavSelectedText else Palette.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶栏：左边「返回」和「设置」，正中深浅色切换，右边一句生效说明（窄屏放到顶栏下面）。 */
@Composable
private fun SettingsTopBar(onBack: () -> Unit, showHint: Boolean, modifier: Modifier = Modifier) {
    TopBar(
        start = {
            TopBarButton(stringResource(R.string.action_back), onBack, glyph = Glyphs.Back)
            Spacer(Modifier.width(16.dp))
            PageTitle(stringResource(R.string.settings_title))
        },
        end = {
            if (showHint) {
                Text(
                    stringResource(R.string.settings_apply_hint),
                    style = UiType.footer(LocalCompact.current),
                    color = Palette.Muted,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        },
        modifier = modifier,
    )
}

/** 最下面的版本行。 */
@Composable
private fun VersionFooter() {
    Text(
        stringResource(R.string.home_version, BuildConfig.VERSION_NAME) + "\n" + stringResource(R.string.home_tagline),
        style = UiType.footer(LocalCompact.current),
        color = Palette.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    )
}
