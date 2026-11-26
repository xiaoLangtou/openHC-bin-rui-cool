package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AppSettings

/** 页面顶上正中的深浅色切换：太阳是浅色，月亮是深色。点了马上换、记进设置。 */
@Composable
internal fun ThemeToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    val compact = LocalCompact.current
    val pick = { dark: Boolean ->
        Palette.dark = dark
        settings.darkTheme = dark
    }
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Palette.Card)
            .border(1.dp, Palette.CardBorder, RoundedCornerShape(percent = 50))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeChoice(Glyphs.Sun, stringResource(R.string.theme_light), !Palette.dark, Palette.Sun, compact) { pick(false) }
        ThemeChoice(Glyphs.Moon, stringResource(R.string.theme_dark), Palette.dark, Palette.Moon, compact) { pick(true) }
    }
}

@Composable
private fun ThemeChoice(
    glyph: ImageVector,
    label: String,
    selected: Boolean,
    selectedTint: Color,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (compact) 48.dp else 54.dp, if (compact) 36.dp else 40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) Palette.Button else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = label, tint = if (selected) selectedTint else Palette.Muted, modifier = Modifier.size(22.dp))
    }
}
