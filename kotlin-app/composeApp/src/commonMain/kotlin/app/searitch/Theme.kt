package app.searitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
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
import app.searitch.resources.leather_grain
import app.searitch.resources.paper
import app.searitch.resources.paper_grain
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource

/* ───────────── Searitch leather & paper palette (matches the web app) ──── */

/*
 * These three read from the *live* palette instead of holding fixed colours.
 *
 * They used to be plain constants, which meant ~50 call sites (straps, cards,
 * chips, buttons, text) ignored the tone chosen in Settings — the app only
 * repainted its background. As getters over a snapshot state they now restyle
 * the entire UI the moment a tone changes, while every existing call site
 * (`Leather.base`, `Ink.soft`, …) keeps compiling untouched.
 */
object Leather {
    val dark: Color get() = LivePalette.value.leatherDark
    val base: Color get() = LivePalette.value.leatherBase
    val light: Color get() = LivePalette.value.leatherLight
    val highlight: Color get() = LivePalette.value.leatherHighlight
}

object Paper {
    val base: Color get() = LivePalette.value.paperBase
    val deep: Color get() = LivePalette.value.paperDeep
    val inset: Color get() = LivePalette.value.paperInset
    val imageWell: Color get() = LivePalette.value.paperWell
}

object Ink {
    val strong: Color get() = LivePalette.value.inkStrong
    val body: Color get() = LivePalette.value.inkBody
    val soft: Color get() = LivePalette.value.inkSoft
    val faint: Color get() = LivePalette.value.inkFaint
    val onLeather: Color get() = LivePalette.value.onLeather
    val onLeatherSoft: Color get() = LivePalette.value.onLeatherSoft
    val thread: Color get() = LivePalette.value.thread
    val danger: Color get() = Color(0xFF73351F)
}

object Brass {
    val light: Color get() = LivePalette.value.brassLight
    val base: Color get() = LivePalette.value.brassBase
    val dark: Color get() = LivePalette.value.brassDark
    val deep: Color get() = LivePalette.value.brassDeep
    val text: Color get() = LivePalette.value.brassText
}

/** The palette currently painted. Written once per theme change, read everywhere. */
val LivePalette = mutableStateOf(DefaultPalette)

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
   Seamless grain maps (composeResources/drawable/leather_grain.png = 864px,
   paper_grain.png = 576px) tiled at 288dp — exactly 3 texture pixels per dp,
   i.e. 1:1 on a 3x phone and downsampled (crisp) on 2x. Loaded once in
   SearitchTheme.

   Two sets ship:
     • `leather` / `paper` — the original coloured hides from the shipped app.
       Classic hide + Cream stock draw these verbatim, so the default look is
       pixel-identical to v0.1.
     • `*_grain` — grey detail maps drawn over the chosen tone with an overlay
       blend, used for every other tone so they look like dyed material rather
       than brown tinted red.                                                   */

class Textures(
    val leather: ImageBitmap,
    val paper: ImageBitmap,
    val leatherGrain: ImageBitmap,
    val paperGrain: ImageBitmap,
)

val LocalTextures = staticCompositionLocalOf<Textures?> { null }

private val TILE = 288.dp

/**
 * The hide/paper texture as a GPU-tiled shader brush.
 *
 * This used to be a nested `drawImage` loop — for every surface, on every frame:
 * an 864px bitmap blitted dozens of times with high-quality filtering. With 21
 * textured surfaces on screen (every card, chip and strap) that was the single
 * biggest cost in the app, and it is what made scrolling and the settings sheet
 * stutter.
 *
 * A repeated [ImageShader] asks the GPU to tile the bitmap for us: one draw call
 * per surface, no per-frame filtering, and the texture lands on exact device
 * pixels (1 texture px = 1 px) so the grain stays crisp instead of blurred.
 */
@Composable
fun rememberTextureBrush(image: ImageBitmap?): ShaderBrush? =
    remember(image) {
        image
            ?.takeIf { it.width > 1 && it.height > 1 }
            ?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
    }

/** Paints [brush] over the whole surface, optionally as an overlay blend. */
private fun Modifier.textureOverlay(brush: ShaderBrush?, overlay: Boolean): Modifier =
    if (brush == null) this
    else this.drawBehind {
        drawRect(
            brush = brush,
            blendMode = if (overlay) BlendMode.Overlay else BlendMode.SrcOver,
        )
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
    val settings = LocalSettings.current.value
    val textures = LocalTextures.current
    val pal = LocalPalette.current
    // Classic hide = the shipped v0.1 bitmap, untinted, exactly as it looked.
    val original = settings.leather == LeatherTone.CLASSIC
    val tex = when {
        !settings.textures -> null
        original -> textures?.leather
        else -> textures?.leatherGrain
    }
    val brush = rememberTextureBrush(tex)
    return this
        .clip(shape)
        .background(pal.leatherBase)
        .textureOverlay(brush, overlay = !original)
        .background(if (tall) pageSheen else sheetSheen)
}

/** Paper surface: base colour → tiled paper stock. */
@Composable
fun Modifier.paperSurface(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val settings = LocalSettings.current.value
    val textures = LocalTextures.current
    val pal = LocalPalette.current
    // Cream stock = the shipped v0.1 paper bitmap, untinted.
    val original = settings.paper == PaperTone.CREAM
    val tex = when {
        !settings.textures -> null
        original -> textures?.paper
        else -> textures?.paperGrain
    }
    val brush = rememberTextureBrush(tex)
    return this
        .clip(shape)
        .background(pal.paperBase)
        .textureOverlay(brush, overlay = !original)
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
    val leatherGrain = imageResource(Res.drawable.leather_grain)
    val paperGrain = imageResource(Res.drawable.paper_grain)
    val textures = remember(leather, paper, leatherGrain, paperGrain) {
        Textures(leather, paper, leatherGrain, paperGrain)
    }
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
    // keep the static accessors (Leather/Ink/Brass) pointing at this palette
    SideEffect { if (LivePalette.value !== palette) LivePalette.value = palette }
    CompositionLocalProvider(
        LocalSettings provides settings,
        LocalPalette provides palette,
        LocalTextures provides textures,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
