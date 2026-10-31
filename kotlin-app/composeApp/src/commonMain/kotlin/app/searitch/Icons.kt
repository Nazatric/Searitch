package app.searitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Vector icons drawn with strokes — crisp at any density, no icon-font dependency. */
enum class Ico { Back, Search, Filter, Close, Copy, Download, External, Up, Check, Shield, Sparkles, Gear, Refresh, Trash }

@Composable
fun Icon(kind: Ico, tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val w = 2.4f * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), w, StrokeCap.Round)

        when (kind) {
            Ico.Back -> {
                line(14.5f, 5f, 7.5f, 12f)
                line(7.5f, 12f, 14.5f, 19f)
            }
            Ico.Search -> {
                drawCircle(tint, 6.5f * u, p(11f, 11f), style = stroke)
                line(16f, 16f, 20.5f, 20.5f)
            }
            Ico.Filter -> {
                line(4f, 7f, 14f, 7f)
                line(18f, 7f, 20f, 7f)
                line(4f, 17f, 6f, 17f)
                line(10f, 17f, 20f, 17f)
                drawCircle(tint, 2f * u, p(16f, 7f), style = stroke)
                drawCircle(tint, 2f * u, p(8f, 17f), style = stroke)
            }
            Ico.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            Ico.Copy -> {
                drawRoundRect(tint, p(8.5f, 8.5f), Size(11f * u, 11f * u), CornerRadius(2.5f * u), style = stroke)
                drawRoundRect(tint, p(4f, 4f), Size(11f * u, 11f * u), CornerRadius(2.5f * u), style = stroke)
            }
            Ico.Download -> {
                line(12f, 4f, 12f, 15f)
                line(7f, 11f, 12f, 16f)
                line(17f, 11f, 12f, 16f)
                line(5f, 20f, 19f, 20f)
            }
            Ico.External -> {
                line(14f, 5f, 19f, 5f)
                line(19f, 5f, 19f, 10f)
                line(19f, 5f, 11f, 13f)
                line(5f, 7f, 5f, 19f)
                line(5f, 19f, 17f, 19f)
                line(17f, 19f, 17f, 14f)
                line(5f, 7f, 10f, 7f)
            }
            Ico.Up -> {
                line(12f, 19f, 12f, 6f)
                line(6f, 11f, 12f, 5f)
                line(18f, 11f, 12f, 5f)
            }
            Ico.Check -> {
                line(5f, 12.5f, 9.5f, 17f)
                line(9.5f, 17f, 19f, 7.5f)
            }
            Ico.Shield -> {
                line(12f, 2f, 20f, 5f)
                line(20f, 5f, 20f, 12f)
                line(20f, 12f, 12f, 22f)
                line(12f, 22f, 4f, 12f)
                line(4f, 12f, 4f, 5f)
                line(4f, 5f, 12f, 2f)
            }
            Ico.Gear -> {
                // hub + eight teeth, so it reads as a cog at 20dp
                drawCircle(tint, 3.4f * u, p(12f, 12f), style = stroke)
                for (i in 0 until 8) {
                    val a = i * (PI / 4.0)
                    val c = cos(a).toFloat()
                    val s2 = sin(a).toFloat()
                    line(12f + c * 6.4f, 12f + s2 * 6.4f, 12f + c * 9.7f, 12f + s2 * 9.7f)
                }
            }
            Ico.Refresh -> {
                drawCircle(tint, 6.6f * u, p(12f, 12f), style = stroke)
                line(12f, 2.6f, 12f, 6.4f)
                line(12f, 2.6f, 8.8f, 5.4f)
                line(12f, 2.6f, 15.2f, 5.4f)
            }
            Ico.Trash -> {
                line(4.5f, 7f, 19.5f, 7f)
                line(9f, 4.5f, 15f, 4.5f)
                line(6.5f, 7f, 7.5f, 20f)
                line(17.5f, 7f, 16.5f, 20f)
                line(7.5f, 20f, 16.5f, 20f)
                line(10.5f, 10f, 10.8f, 17f)
                line(13.5f, 10f, 13.2f, 17f)
            }
            Ico.Sparkles -> {
                line(12f, 3f, 12f, 21f)
                line(3f, 12f, 21f, 12f)
                line(6f, 6f, 18f, 18f)
                line(6f, 18f, 18f, 6f)
            }
        }
    }
}
