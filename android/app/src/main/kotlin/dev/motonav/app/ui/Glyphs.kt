package dev.motonav.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Bộ biểu tượng nét mảnh của bản thiết kế (design/MotoNav-App-redesign.svg), khung 24 × 24, nét 2, đầu tròn.
 * Chép thẳng đường vẽ từ file thiết kế để app và bản vẽ giống hệt nhau; màu do `Icon(tint = …)` quyết định.
 */
object Glyphs {
    val Back = stroke("M19 12H5 M11 5l-7 7 7 7")
    val Close = stroke("M6 6l12 12 M18 6L6 18")
    val Search = stroke("M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13z M15.5 15.5 L20.5 20.5")
    val Gear = stroke(
        "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4z M12 18.5a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13z " +
            "M12 2.5v3 M12 18.5v3 M2.5 12h3 M18.5 12h3 M5.3 5.3l2.1 2.1 M16.6 16.6l2.1 2.1 M5.3 18.7l2.1-2.1 M16.6 7.4l2.1-2.1",
    )
    val Display = stroke("M4 5h16a1.5 1.5 0 0 1 1.5 1.5v9A1.5 1.5 0 0 1 20 17H4a1.5 1.5 0 0 1-1.5-1.5v-9A1.5 1.5 0 0 1 4 5z M9 20.5h6 M12 17v3.5")
    val Gps = stroke("M12 18.5a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13z M12 2v3.5 M12 18.5V22 M2 12h3.5 M18.5 12H22 M12 13.2a1.2 1.2 0 1 0 0-2.4 1.2 1.2 0 0 0 0 2.4z")
    val Battery = stroke("M4 7.5h13a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 15V9A1.5 1.5 0 0 1 4 7.5z M21.5 10.5v3 M6 11v2 M9 11v2")
    val Clock = stroke("M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17z M12 7.5V12l3 2")
    val Pin = stroke("M12 21s-6.5-5.7-6.5-11a6.5 6.5 0 0 1 13 0c0 5.3-6.5 11-6.5 11z M12 12.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6z")
    val NoGps = stroke("M12 21s-6.5-5.7-6.5-11a6.5 6.5 0 0 1 13 0c0 5.3-6.5 11-6.5 11z M4 4l16 16")
    val Chevron = stroke("M9 5l7 7-7 7")
    val Check = stroke("M5 12.5l4.5 4.5L19 7.5", width = 3f)
    val Replay = stroke("M4.5 12a7.5 7.5 0 1 0 2.2-5.3 M4.5 4v4h4")
    val Share = stroke("M12 15V3.5 M7.5 8L12 3.5 16.5 8 M5 13v6.5h14V13")
    val Bulb = stroke("M9 18h6 M10 21h4 M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z")
    val Play = fill("M8 5.5v13l10.5-6.5z")
    val More = fill(
        "M5 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z M12 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z " +
            "M19 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z",
    )

    private fun stroke(d: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun fill(d: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
            .build()
}
