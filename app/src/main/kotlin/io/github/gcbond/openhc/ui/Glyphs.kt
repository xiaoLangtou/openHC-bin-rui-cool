package io.github.gcbond.openhc.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 界面上的线条图标：24×24 的画布、2 单位粗的圆头线，颜色由使用处着色。都是简单几何图形，在这里直接画，不用图标库。
 */
internal object Glyphs {
    val Back by lazy { stroke("back", "M15 18l-6-6 6-6") }
    val Chevron by lazy { stroke("chevron", "M9 18l6-6-6-6") }
    val Gear by lazy {
        stroke(
            "gear",
            "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z M12 5a7 7 0 1 0 0 14a7 7 0 1 0 0-14z " +
                "M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1l2.1-2.1 M17 7l2.1-2.1",
        )
    }
    val Sun by lazy {
        stroke(
            "sun",
            "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 " +
                "M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4",
        )
    }
    val Moon by lazy { stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val Wifi by lazy {
        stroke("wifi", "M2 8.8a15 15 0 0 1 20 0 M5 12.4a10.5 10.5 0 0 1 14 0 M8.5 15.9a5.5 5.5 0 0 1 7 0 M12 19.5h0.01")
    }
    val Phone by lazy { stroke("phone", "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M11 18h2") }
    val Plug by lazy { stroke("plug", "M9 2v4 M15 2v4 M7 6h10v5a5 5 0 0 1-10 0z M12 16v6") }
    val Alert by lazy { stroke("alert", "M12 3l10 18H2z M12 10v5 M12 18h0.01") }
    val Monitor by lazy { stroke("monitor", "M3 4h18v13H3z M8 21h8 M12 17v4") }
    val Power by lazy { stroke("power", "M12 2v9 M6.3 5.8a8 8 0 1 0 11.4 0") }
    val Speaker by lazy { stroke("speaker", "M11 5L6 9H3v6h3l5 4z M15.5 8.5a5 5 0 0 1 0 7 M18.5 5.5a9 9 0 0 1 0 13") }
    val Wheel by lazy {
        stroke(
            "wheel",
            "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z " +
                "M3.5 10.5h6 M14.5 10.5h6 M12 14.5V21",
        )
    }
    val Document by lazy { stroke("document", "M14 2H6v20h12V6z M14 2v4h4 M8 13h8 M8 17h5") }

    private fun stroke(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
