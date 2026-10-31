package app.searitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.searitch.resources.Res
import app.searitch.resources.cinzel_bold
import app.searitch.resources.leather
import app.searitch.resources.paper
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource

/* ───────────── Searitch leather & paper palette (matches the web app) ──── */

object Leather {
    val dark = Color(0xFF24160D)
    val base = Color(0xFF3A2418)
    val light = Color(0xFF5A3620)
    val highlight = Color(0xFF6E452A)
}

object Paper {
    val base = Color(0xFFF1E8D4)
    val deep = Color(0xFFE4D8BC)
    val inset = Color(0xFFE6DCC3)
    val imageWell = Color(0xFFCDBF9F)
}

object Ink {
    val strong = Color(0xFF2E2419)
    val body = Color(0xFF443720)
    val soft = Color(0xFF5F5138)
    val faint = Color(0xFF7D6845)
    val onLeather = Color(0xFFEADBBD)
    val onLeatherSoft = Color(0xFFB99A62)
    val thread = Color(0xFFE8CD96)
    val danger = Color(0xFF73351F)
}

object Brass {
    val light = Color(0xFFE2C279)
    val base = Color(0xFFC9A24B)
    val dark = Color(0xFF8B6B27)
    val deep = Color(0xFFBF9A45)
    val text = Color(0xFF2F2209)
}

/** Brass gradient in the tone the user picked in Settings. */
@Composable
fun brassBrush(): Brush {
    val pal = LocalPalette.current
    return remember(pal) { Brush.verticalGradient(listOf(pal.brassLight, pal.brassDeep)) }
}

/** Gold-stamp lettering: a bright top, a dark shine band, a warm bottom. */
@Composable
fun foilBrush(): Brush {
    val pal = LocalPalette.current
    return remember(pal) {
        Brush.verticalGradient(
            0f to pal.brassLight,
            0.46f to pal.brassBase,
            0.52f to pal.brassDark,
            1f to pal.brassLight,
        )
    }
}

/** The wordmark / heading face, bundled with the app (no streaming). */
@Composable
fun cinzel(): FontFamily = FontFamily(Font(Res.font.cinzel_bold, FontWeight.Bold))

/* ───────────────────────── real tiled textures ───────────────────────────
   The same seamless, high-res leather & paper bitmaps as the web app
   (composeResources/drawable/leather.png = 864px, paper.png = 576px), tiled
   at 288dp — exactly 3 texture pixels per dp, i.e. 1:1 on a 3x phone and
   downsampled (crisp) on 2x. Loaded once in SearitchTheme.                  */

class Textures(val leather: ImageBitmap, val paper: ImageBitmap)

val LocalTextures = staticCompositionLocalOf<Textures?> { null }

private val TILE = 288.dp

/** Tiles [image] across the bounds. Skips the 1×1 placeholder some targets
 *  (Wasm) return while a resource is still loading, so nothing flashes. */
fun Modifier.tiledTexture(image: ImageBitmap?, tile: Dp): Modifier =
    if (image == null) this
    else this.clipToBounds().drawBehind {
        if (image.width <= 1 || image.height <= 1) return@drawBehind
        val t = tile.toPx().toInt().coerceAtLeast(1)
        val dst = IntSize(t, t)
        var y = 0
        while (y < size.height) {
            var x = 0
            while (x < size.width) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(x, y),
                    dstSize = dst,
                    filterQuality = FilterQuality.High,
                )
                x += t
            }
            y += t
        }
    }

private val pageSheen = Brush.verticalGradient(
    0f to Color(0x1FFFD796),
    0.5f to Color(0x00000000),
    1f to Color(0x57000000),
)
private val strapSheen = Brush.verticalGradient(listOf(Color(0x2BFFE4B8), Color(0x38000000)))
private val sheetSheen = Brush.verticalGradient(listOf(Color(0x24FFE4B8), Color(0x38000000)))
private val fieldShade = Brush.verticalGradient(
    0f to Color(0x4D6E5530),
    0.45f to Color(0x00FFFFFF),
    1f to Color(0x33FFFFFF),
)

/** Leather page: base colour → tiled hide → soft vignette. */
@Composable
fun Modifier.leatherSurface(shape: Shape = RectangleShape, tall: Boolean = false): Modifier {
    val tex = if (LocalSettings.current.value.textures) LocalTextures.current?.leather else null
    val pal = LocalPalette.current
    return this
        .clip(shape)
        .background(pal.leatherBase)
        .tiledTexture(tex, TILE)
        .background(if (tall) pageSheen else sheetSheen)
}

/** Paper surface: base colour → tiled paper stock. */
@Composable
fun Modifier.paperSurface(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val tex = if (LocalSettings.current.value.textures) LocalTextures.current?.paper else null
    val pal = LocalPalette.current
    return this
        .clip(shape)
        .background(pal.paperBase)
        .tiledTexture(tex, TILE)
}

/** A field debossed into the leather: paper with a shaded top edge and a hairline. */
@Composable
fun Modifier.paperField(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    this
        .paperSurface(shape)
        .background(fieldShade)
        .border(1.dp, Color(0x473C2812), shape)

/** A row of slanted saddle stitches with a soft shadow, like real thread. */
@Composable
fun Modifier.stitchRow(fromBottom: Dp = 5.dp, inset: Dp = 8.dp): Modifier {
    val thread = LocalPalette.current.thread
    return drawBehind {
        val y = size.height - fromBottom.toPx()
        val step = 12.dp.toPx()
        val len = 8.dp.toPx()
        val thick = 1.9.dp.toPx()
        val dx = len * 0.91f
        val dy = len * 0.40f
        val lift = 1.dp.toPx()
        val end = size.width - inset.toPx()
        var x = inset.toPx()
        while (x + dx <= end) {
            drawLine(Color(0x80000000), Offset(x, y + dy / 2 + lift), Offset(x + dx, y - dy / 2 + lift), thick, StrokeCap.Round)
            drawLine(thread, Offset(x, y + dy / 2), Offset(x + dx, y - dy / 2), thick, StrokeCap.Round)
            x += step
        }
    }
}

/** A leather strap (app bar): hide, sheen, top highlight and a stitched seam. */
@Composable
fun Modifier.leatherStrap(): Modifier =
    this
        .leatherSurface(RectangleShape)
        .background(strapSheen)
        .drawBehind { drawLine(Color(0x4DFFE2B4), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1f) }
        .stitchRow()

@Composable
fun SearitchTheme(settings: SettingsState, content: @Composable () -> Unit) {
    val leather = imageResource(Res.drawable.leather)
    val paper = imageResource(Res.drawable.paper)
    val textures = remember(leather, paper) { Textures(leather, paper) }
    val palette = settings.palette
    val scheme = remember(palette) {
        darkColorScheme(
            primary = palette.brassBase,
            onPrimary = palette.brassText,
            background = palette.leatherDark,
            onBackground = palette.onLeather,
            surface = palette.paperBase,
            onSurface = Ink.strong,
            secondary = palette.leatherLight,
            onSecondary = palette.onLeather,
        )
    }
    CompositionLocalProvider(
        LocalSettings provides settings,
        LocalPalette provides palette,
        LocalTextures provides textures,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
