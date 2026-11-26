package io.github.gcbond.openhc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 全屏原生风格 HiCar 启动动画（Issue #113）：纯 Compose 绘制，黑底白字标，
 * 不用 MediaPlayer / VideoView 播视频——那会抢占和投屏共用的硬件解码器，动画放完画面反而出不来。
 *
 * 观感（对照 `_dump/issue113-frames/f03~f08`）：纯黑底，居中「HUAWEI HiCar」字标，
 * 进入时 900ms 内从 0.94 倍柔和放大「起亮浮现」，一道银白高光自左向右扫过字标；
 * 之后停稳为清晰亮白，叠一层极轻微的呼吸透明度（0.86 ↔ 1.0，周期 2400ms），
 * 没有状态文字、没有转圈。进出场各 300ms 淡入淡出。
 *
 * [visible] 为 false 淡出结束后整层退出组合树，触摸完全交还底层投屏画面。
 * 动画期间单击被本层吃掉（防误触底层首页按钮），双击或按返回键（调用方处理）触发 [onDismiss] 收起。
 */
@Composable
internal fun HiCarSplashOverlay(visible: Boolean, onDismiss: () -> Unit) {
    // pointerInput(Unit) 只捕获第一次的 lambda：用 rememberUpdatedState 保持最新引用，
    // 不然外层 Screen / attempt 变化后重建的 onDismiss 收起的是旧状态（attempt 记错人）。
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 300, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(durationMillis = 300, easing = LinearOutSlowInEasing)),
    ) {
        val compact = LocalCompact.current
        val fontSize = if (compact) 32.sp else 44.sp
        // 起亮浮现：scale 0.94 → 1.0；高光扫过完成后淡出，剩下清晰亮白字标。
        val appear = remember { Animatable(0f) }
        val shine = remember { Animatable(1f) }
        // 停稳后的呼吸微动效（起亮完成后才启动）。
        val breathe = remember { Animatable(1f) }
        var textSize by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
            shine.animateTo(0f, tween(durationMillis = 250, easing = LinearOutSlowInEasing))
            breathe.animateTo(
                0.86f,
                infiniteRepeatable(tween(durationMillis = 1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 吃掉所有单击防误触底层；双击是逃生出口。
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { latestOnDismiss() })
                },
            contentAlignment = Alignment.Center,
        ) {
            val w = textSize.width.coerceAtLeast(1).toFloat()
            val h = textSize.height.coerceAtLeast(1).toFloat()
            // 底层字标从微暗起亮渐变为纯白；纯白叠纯白什么都看不见，起亮先压暗才有「亮起来」的余地。
            val baseColor = Color.White.copy(alpha = 0.35f + 0.65f * appear.value)
            // 银白高光：一道柔边的亮带随起亮进度自左向右扫过字标，扫完淡出。
            val sweepX = w * (appear.value * 1.6f - 0.3f)
            val shineBrush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xCCFFFFFF),
                    Color.White,
                    Color(0xCCFFFFFF),
                    Color.Transparent,
                ),
                start = Offset(sweepX - w * 0.35f, 0f),
                end = Offset(sweepX + w * 0.35f, h),
            )
            Box(
                modifier = Modifier
                    .onSizeChanged { textSize = it }
                    .graphicsLayer {
                        val s = 0.94f + 0.06f * appear.value
                        scaleX = s
                        scaleY = s
                        alpha = breathe.value
                    },
            ) {
                Text(wordmark(fontSize, color = baseColor), fontSize = fontSize)
                Text(
                    wordmark(fontSize, color = Color.Transparent, brush = shineBrush),
                    fontSize = fontSize,
                    modifier = Modifier.graphicsLayer { alpha = shine.value },
                )
            }
        }
    }
}

/**
 * 字标：HUAWEI（SemiBold、宽字距）+ 空格 + HiCar（Medium、紧字距）。
 * [color] 是显式指定的字色（不吃主题字色——浅色主题下也不能变黑字）；[brush] 存在时覆盖字色
 * （SpanStyle 的 color 与 brush 互斥），扫光层传 Color.Transparent 兜底。
 */
private fun wordmark(fontSize: TextUnit, color: Color, brush: Brush? = null): AnnotatedString {
    val foreground = if (brush != null) SpanStyle(brush = brush) else SpanStyle(color = color)
    return buildAnnotatedString {
        withStyle(foreground.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)) { append("HUAWEI") }
        append(" ")
        withStyle(foreground.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)) { append("HiCar") }
    }
}
