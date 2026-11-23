package io.github.gcbond.openhc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice

/**
 * 首页顶上的兜底提示：安装包里的连接参数缺少或不能用时一张醒目的卡片，正常时不显示。
 * 连接参数不进仓库，构建时打进安装包（见 app/build.gradle.kts），软件里没有地方填写。
 */
@Composable
internal fun IdentityHomeNotice(identity: IdentityLoader.Result, modifier: Modifier = Modifier) {
    val text = when (identity) {
        is IdentityLoader.Result.Loaded -> return
        IdentityLoader.Result.Missing -> stringResource(R.string.home_identity_missing)
        is IdentityLoader.Result.Invalid -> stringResource(R.string.home_identity_invalid, identity.reason)
    }
    CardBox(modifier.fillMaxWidth(), color = Palette.Warn.copy(alpha = PROMPT_TINT)) {
        Column {
            Text(stringResource(R.string.home_identity_title), style = UiType.status, color = Palette.Warn)
            Text(text, style = UiType.hint, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 提示卡片的底色：提醒色叠一层淡的。 */
private const val PROMPT_TINT = 0.16f

/**
 * 身份参数的状态由 [IdentityHomeNotice] 按当前结果显示，状态卡里不再重复。
 */
internal fun ConnectionUiState.withoutIdentityNotice(): ConnectionUiState =
    if (notice == Notice.IdentityMissing || notice is Notice.IdentityInvalid) copy(notice = null) else this
