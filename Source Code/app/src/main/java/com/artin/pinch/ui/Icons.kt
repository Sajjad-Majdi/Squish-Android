package com.artin.pinch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Hand-drawn line icons, so no icon library ships in the APK. */
object Ic {
    private fun line(name: String, w: Float = 1.9f, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    private fun solid(name: String, block: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx + rx, cy)
        arcTo(rx, ry, 0f, true, true, cx - rx, cy)
        arcTo(rx, ry, 0f, true, true, cx + rx, cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, c: Float) {
        moveTo(l + c, t)
        lineTo(r - c, t); arcTo(c, c, 0f, false, true, r, t + c)
        lineTo(r, b - c); arcTo(c, c, 0f, false, true, r - c, b)
        lineTo(l + c, b); arcTo(c, c, 0f, false, true, l, b - c)
        lineTo(l, t + c); arcTo(c, c, 0f, false, true, l + c, t)
        close()
    }

    val plus = line("plus", 2.2f) { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val close = line("close") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) }
    val check = line("check", 2.4f) { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
    val back = line("back", 2.1f) { moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f) }
    val forward = line("forward", 2.1f) { moveTo(9.5f, 5f); lineTo(16.5f, 12f); lineTo(9.5f, 19f) }
    val share = line("share") {
        circle(18f, 5.5f, 2.5f); circle(6f, 12f, 2.5f); circle(18f, 18.5f, 2.5f)
        moveTo(8.3f, 10.8f); lineTo(15.7f, 6.7f); moveTo(8.3f, 13.2f); lineTo(15.7f, 17.3f)
    }
    val trash = line("trash") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 7f)
        moveTo(10.2f, 11f); lineTo(10.2f, 16f); moveTo(13.8f, 11f); lineTo(13.8f, 16f)
    }
    val pinch = line("pinch") {
        moveTo(7.5f, 3.5f); lineTo(12f, 7f); lineTo(16.5f, 3.5f)
        moveTo(7.5f, 20.5f); lineTo(12f, 17f); lineTo(16.5f, 20.5f)
        ellipse(12f, 12f, 6.5f, 2.8f)
    }
    val history = line("history") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val settings = line("settings") {
        moveTo(4f, 7f); lineTo(12.5f, 7f); moveTo(17.5f, 7f); lineTo(20f, 7f)
        moveTo(4f, 17f); lineTo(6.5f, 17f); moveTo(11.5f, 17f); lineTo(20f, 17f)
        circle(15f, 7f, 2.5f); circle(9f, 17f, 2.5f)
    }
    val folder = line("folder") {
        moveTo(3.5f, 7.5f); arcTo(2f, 2f, 0f, false, true, 5.5f, 5.5f); lineTo(9.5f, 5.5f); lineTo(11.5f, 7.5f)
        lineTo(18.5f, 7.5f); arcTo(2f, 2f, 0f, false, true, 20.5f, 9.5f); lineTo(20.5f, 17.5f)
        arcTo(2f, 2f, 0f, false, true, 18.5f, 19.5f); lineTo(5.5f, 19.5f); arcTo(2f, 2f, 0f, false, true, 3.5f, 17.5f); close()
    }
    val open = line("open") {
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(18f, 14f); lineTo(18f, 19f); lineTo(5f, 19f); lineTo(5f, 6f); lineTo(10f, 6f)
    }
    val sort = line("sort") {
        moveTo(8f, 5f); lineTo(8f, 19f); moveTo(5f, 8f); lineTo(8f, 5f); lineTo(11f, 8f)
        moveTo(16f, 19f); lineTo(16f, 5f); moveTo(13f, 16f); lineTo(16f, 19f); lineTo(19f, 16f)
    }
    val photo = line("photo") {
        roundRect(3.5f, 5f, 20.5f, 19f, 2.5f)
        moveTo(3.8f, 16.5f); lineTo(8.5f, 12f); lineTo(12f, 15.5f); lineTo(14.5f, 13f); lineTo(20.2f, 18f)
        circle(15.5f, 9f, 1.4f)
    }
    val video = line("video") {
        roundRect(3.5f, 6f, 15.5f, 18f, 2.5f)
        moveTo(15.5f, 10.5f); lineTo(20.5f, 7.5f); lineTo(20.5f, 16.5f); lineTo(15.5f, 13.5f)
    }
    val play = solid("play") { moveTo(8f, 5.5f); lineTo(8f, 18.5f); lineTo(18.5f, 12f); close() }
    val sparkle = solid("sparkle") {
        moveTo(12f, 2.5f); lineTo(14f, 10f); lineTo(21.5f, 12f); lineTo(14f, 14f)
        lineTo(12f, 21.5f); lineTo(10f, 14f); lineTo(2.5f, 12f); lineTo(10f, 10f); close()
    }
}
