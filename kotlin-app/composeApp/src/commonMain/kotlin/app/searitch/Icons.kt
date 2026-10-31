package app.searitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
                // funnel: wide rim tapering to a short stem
                val funnel = Path().apply {
                    moveTo(3.8f * u, 5f * u)
                    lineTo(20.2f * u, 5f * u)
                    lineTo(13.6f * u, 13.2f * u)
                    lineTo(13.6f * u, 19.4f * u)
                    lineTo(10.4f * u, 21f * u)
                    lineTo(10.4f * u, 13.2f * u)
                    close()
                }
                drawPath(funnel, tint, style = stroke)
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
                /*
                 * A real cog: eight trapezoidal teeth traced as one closed
                 * outline (outer flat top, then a step down to the root circle).
                 * A hub circle + radial spikes — the obvious shortcut — reads as
                 * a torch/sun at 20dp, so it is drawn as a path instead.
                 */
                val teeth = 8
                val step = 360f / teeth      // 45°
                val toothHalf = 12f          // half the flat top
                val rootStart = 15f          // tooth flank ends here
                val rootEnd = 33f            // root arc ends where the next tooth starts
                val rOuter = 10.4f
                val rRoot = 7.3f

                fun at(deg: Float, r: Float): Offset {
                    val a = deg * PI / 180.0
                    return p(12f + (r * cos(a)).toFloat(), 12f + (r * sin(a)).toFloat())
                }

                val cog = Path()
                for (i in 0 until teeth) {
                    val base = i * step - 90f
                    val a1 = at(base - toothHalf, rOuter)
                    val a2 = at(base + toothHalf, rOuter)
                    val a3 = at(base + rootStart, rRoot)
                    val a4 = at(base + rootEnd, rRoot)
                    if (i == 0) cog.moveTo(a1.x, a1.y) else cog.lineTo(a1.x, a1.y)
                    cog.lineTo(a2.x, a2.y)
                    cog.lineTo(a3.x, a3.y)
                    cog.lineTo(a4.x, a4.y)
                }
                cog.close()
                drawPath(cog, tint, style = stroke)
                drawCircle(tint, 3.2f * u, p(12f, 12f), style = stroke)
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
