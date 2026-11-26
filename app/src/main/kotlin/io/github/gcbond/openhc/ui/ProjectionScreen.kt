package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.media.VideoDecoder
import io.github.gcbond.openhc.projection.ProjectionSurface

/**
 * 全屏投屏页：手机画面铺满屏幕（藏起状态栏和导航栏），触摸交给 [onInput]。
 * 返回键回首页、连接保持（由调用方处理），首页可以再点「回到投屏画面」。
 *
 * [showWaitingText]：等首帧的白字「正在等待手机画面…」显不显示。开着 HiCar 启动动画时调用方传 false，
 * 免得动画淡出时底下透出一行白字；解码器问题提示不受影响，一直显示。
 */
@Composable
internal fun ProjectionScreen(
    state: ConnectionUiState,
    decoder: VideoDecoder?,
    touchResets: Int,
    onInput: (HiSightInput) -> Unit,
    showWaitingText: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProjectionSurface(
            decoder = decoder,
            videoWidth = state.videoWidth,
            videoHeight = state.videoHeight,
            onInput = onInput,
            modifier = Modifier.fillMaxSize(),
            fullScreen = io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen,
            touchResetKey = touchResets,
        )
        if (!state.firstFrame && showWaitingText) {
            Text(stringResource(R.string.projection_waiting_frame), color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        state.decoderProblem?.let {
            Text(
                stringResource(R.string.projection_decoder_problem, it),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xAA000000))
                    .padding(8.dp),
            )
        }
    }
}
