package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.settings.ClarityPreferences

/**
 * 「画面」组里帧率下面的「清晰度」：标准 / 高 / 最高，决定声明给手机的目标码率（见 [VideoClarity]）。下次连接生效。
 * 分段里只写档位名，选中那档的码率写在说明的开头。
 */
@Composable
internal fun ClaritySettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { ClarityPreferences(context) }
    var clarity by remember { mutableStateOf(prefs.clarity) }
    val options = VideoClarity.entries
    ChoiceRow(
        title = stringResource(R.string.settings_clarity),
        summary = stringResource(R.string.settings_clarity_summary),
        options = options.map { stringResource(shortLabel(it)) },
        selected = options.indexOf(clarity),
        description = clarityLabel(clarity) + "。" + stringResource(R.string.settings_clarity_hint),
    ) { index ->
        clarity = options[index]
        prefs.clarity = options[index]
    }
}

private fun shortLabel(clarity: VideoClarity): Int = when (clarity) {
    VideoClarity.STANDARD -> R.string.settings_clarity_standard_short
    VideoClarity.HIGH -> R.string.settings_clarity_high_short
    VideoClarity.HIGHEST -> R.string.settings_clarity_highest_short
    VideoClarity.STEADY -> R.string.settings_clarity_steady_short
}

@Composable
private fun clarityLabel(clarity: VideoClarity): String {
    val mbps = (clarity.bitrate ?: 0) / 1_000_000
    return when (clarity) {
        VideoClarity.STANDARD -> stringResource(R.string.settings_clarity_standard)
        VideoClarity.HIGH -> stringResource(R.string.settings_clarity_high, mbps)
        VideoClarity.HIGHEST -> stringResource(R.string.settings_clarity_highest, mbps)
        VideoClarity.STEADY -> stringResource(R.string.settings_clarity_steady, mbps, (clarity.minBitrate ?: 0) / 1_000_000)
    }
}
