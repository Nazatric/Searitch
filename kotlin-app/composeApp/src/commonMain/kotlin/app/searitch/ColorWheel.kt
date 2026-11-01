package app.searitch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Every colour, not twelve.
 *
 * The twelve named swatches stay, because the sources that filter by colour only
 * understand names — Bing's index, and Openverse, which takes a colour as a
 * search word. The wheel is for everything between them: it keeps the exact hex,
 * presses it onto Wallhaven (which does filter by hex) and tells the name-only
 * sources the nearest name, so a wheel pick never quietly searches for nothing.
 *
 * Value is pinned at full and saturation runs from the middle out — the shape
 * everyone already knows from a colour picker, and one gesture instead of three
 * sliders.
 */
@Composable
fun ColorWheel(
    picked: String,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val pal = LocalPalette.current
    var hue by remember { mutableFloatStateOf(210f) }
    var sat by remember { mutableFloatStateOf(0.70f) }

    // Dragging emits a colour per frame, but most frames land on the same 8-bit
    // shade. Only a real change is reported, so the sheet isn't rebuilt 60 times
    // a second for nothing.
    val lastSent = remember { mutableStateOf("") }

    // A wheel pick that came from a saved filter puts the marker back where it
    // was. A value this wheel just emitted is skipped, otherwise quantising it
    // back to hue/saturation would nudge the marker under the finger.
    LaunchedEffect(picked) {
        if (picked == lastSent.value) return@LaunchedEffect
        val rgb = parseHex(picked) ?: return@LaunchedEffect
        val hsv = rgbToHsv(rgb)
        if (hsv[1] > 0.05f) {
            hue = hsv[0]
            sat = hsv[1]
        }
    }

    val currentHex = hexString(hsvToRgb(hue, sat, 1f))
    val selected = picked.isNotEmpty() && picked.equals(currentHex, ignoreCase = true)

    fun pickAt(x: Float, y: Float, width: Float) {
        val radius = width / 2f
        if (radius <= 0f) return
        val dx = x - radius
        val dy = y - radius
        val dist = hypot(dx, dy)
        if (dist > radius) return
        // atan2 runs clockwise from 3 o'clock, and so does the sweep gradient.
        hue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        sat = (dist / radius).coerceIn(0f, 1f)
        val hex = hexString(hsvToRgb(hue, sat, 1f))
        if (hex != lastSent.value) {
            lastSent.value = hex
            onPick(hex)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(156.dp)
                // One gesture handler for tap and drag. Two detectors on the same
                // node fight over the pointer: the tap one holds the down event
                // until it sees an up, and a drag then starts late or not at all.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val width = size.width.toFloat()
                        val down = awaitFirstDown()
                        pickAt(down.position.x, down.position.y, width)
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    pickAt(change.position.x, change.position.y, width)
                                    change.consume()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        ) {
            Canvas(Modifier.size(156.dp)) {
                val r = size.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)
                drawCircle(brush = Brush.sweepGradient(hueRing()), radius = r, center = centre)
                // White in the middle fading out turns the hue ring into a full
                // hue + saturation wheel in one draw.
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White,
                        0.55f to Color.White.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                        center = centre,
                        radius = r,
                    ),
                    radius = r,
                    center = centre,
                )
                drawCircle(
                    color = pal.brassDark,
                    radius = r - 1.dp.toPx(),
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
                val angle = hue * PI.toFloat() / 180f
                val marker = Offset(
                    centre.x + cos(angle) * sat * r,
                    centre.y + sin(angle) * sat * r,
                )
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = 11f, center = marker)
                drawCircle(Color.White, radius = 9f, center = marker)
                drawCircle(Color(currentHex.toIntOrNull(16) ?: 0), radius = 6.5f, center = marker)
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                "#$currentHex",
                color = Ink.strong,
                fontFamily = cinzel(),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Text(
                nearestColorName(currentHex)?.let { "nearest name: ${it.label}" } ?: "custom shade",
                color = Ink.faint,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(parseHex(currentHex) ?: 0))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) pal.brassLight else Color.Black.copy(alpha = 0.25f),
                            CircleShape,
                        )
                        .clickable { if (selected) onClear() else onPick(currentHex) },
                )
                Text(
                    if (selected) "chosen · tap to clear" else "tap the disc to use it",
                    color = Ink.soft,
                    fontSize = 11.5.sp,
                )
            }
            if (picked.isNotEmpty()) {
                Text(
                    "clear colour",
                    color = Ink.soft,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 13 evenly spaced stops around the ring, then back to the start. */
private fun hueRing(): List<Color> = (0..12).map { i ->
    Color(hsvToRgb(i * 30f % 360f, 1f, 1f))
}

/** HSV → 0xRRGGBB. Hue in degrees, saturation and value in 0..1. */
fun hsvToRgb(hue: Float, s: Float, v: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1 - abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
    return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** 0xRRGGBB → [hue 0..360, saturation 0..1, value 0..1]. */
fun rgbToHsv(rgb: Int): FloatArray {
    val r = ((rgb shr 16) and 0xFF) / 255f
    val g = ((rgb shr 8) and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val hue = when {
        d < 0.0001f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf((hue + 360f) % 360f, if (max <= 0f) 0f else d / max, max)
}
