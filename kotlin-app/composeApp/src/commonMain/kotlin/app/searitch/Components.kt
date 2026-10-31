@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.searitch

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/* ───────────────────────── shared-element plumbing ───────────────────────
   App.kt wraps the screens in SharedTransitionLayout + AnimatedContent and
   publishes both scopes here, so any composable can tag a shared element.   */

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** A photo that lifts out of its grid card and grows into the detail hero (and back). */
@Composable
fun Modifier.sharedPhoto(id: String): Modifier {
    val shared = LocalSharedScope.current
    val visibility = LocalVisibilityScope.current
    if (shared == null || visibility == null) return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            sharedContentState = rememberSharedContentState(key = "photo-$id"),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.8f, stiffness = 260f) },
        )
    }
}

/** The search field glides from the home card up into the results strap. */
@Composable
fun Modifier.sharedField(): Modifier {
    val shared = LocalSharedScope.current
    val visibility = LocalVisibilityScope.current
    if (shared == null || visibility == null) return this
    return with(shared) {
        this@sharedField.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "field"),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 300f) },
        )
    }
}

/* ─────────────────────── press-spring interaction scale ─────────────────── */

@Composable
fun Modifier.pressable(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/* ───────────────────────────── buttons ──────────────────────────────────── */

@Composable
fun BrassButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .pressable(interaction)
            .shadow(3.dp, shape)
            .background(brassBrush(), shape)
            .border(1.dp, pal.brassDark, shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = pal.brassText,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.8.sp,
        )
    }
}

/** A recessed well set into the leather, holding a gold icon. */
@Composable
fun WellButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .pressable(interaction)
            .clip(shape)
            .background(Color(0x66160B04))
            .border(1.dp, Color(0x66000000), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun SearitchChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .pressable(interaction)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(brassBrush(), CircleShape)
                else Modifier.paperSurface(CircleShape),
            )
            .border(
                1.dp,
                if (selected) pal.brassDark else Ink.faint.copy(alpha = 0.3f),
                CircleShape,
            )
            .clickable(interactionSource = interaction, indication = null) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) pal.brassText else Ink.soft,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Color(0xFFEAD9B6),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(0xD61C1008), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/* ───────────────────────────── photo print ──────────────────────────────── */

fun cardRatio(img: SearitchImage): Float =
    if (img.width > 0 && img.height > 0) (img.width.toFloat() / img.height).coerceIn(0.62f, 1.8f)
    else 4f / 3f

fun displayTitle(title: String): String? {
    val t = title.trim()
    if (t.isEmpty()) return null
    if (t.equals("untitled", ignoreCase = true)) return null
    if (Regex("^wallpaper\\s+\\d+\\s*[×x]\\s*\\d+$", RegexOption.IGNORE_CASE).matches(t)) return null
    if (Regex("^image\\s+\\d+$", RegexOption.IGNORE_CASE).matches(t)) return null
    return t
}

fun tiltOf(id: String): Float {
    var h = 0
    for (ch in id) h = h * 33 + ch.code
    return ((h ushr 1) % 21 - 10) * 0.24f
}

@Composable
fun PhotoCard(img: SearitchImage, index: Int, onClick: (SearitchImage) -> Unit) {
    // Only the first screenful animates in; anything later just exists.
    val motionOn = LocalSettings.current.value.motion
    var entered by remember(img.id) { mutableStateOf(index >= 24 || !motionOn) }
    LaunchedEffect(img.id, motionOn) {
        if (!motionOn) { entered = true; return@LaunchedEffect }
        if (!entered) {
            delay((index % 10) * 40L)
            entered = true
        }
    }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "appear",
    )
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(3.dp)
    val tilt = remember(img.id) { tiltOf(img.id) }
    val settings = LocalSettings.current.value
    val style = settings.cardStyle
    val motion = settings.motion
    val pal = LocalPalette.current
    val rotation = if (style == CardStyle.PRINT) tilt * appear else 0f

    Column(
        modifier = Modifier
            .graphicsLayer {
                alpha = appear
                translationY = if (motion) (1f - appear) * 40f else 0f
                rotationZ = if (motion) rotation else 0f
            }
            .pressable(interaction)
            .then(
                when (style) {
                    CardStyle.BARE -> Modifier
                    CardStyle.FLAT -> Modifier
                        .shadow(3.dp, shape)
                        .paperSurface(shape)
                    CardStyle.PRINT -> Modifier
                        .shadow(4.dp, shape)
                        .paperSurface(shape)
                },
            )
            .clickable(interactionSource = interaction, indication = null) { onClick(img) }
            .padding(if (style == CardStyle.BARE) 0.dp else 7.dp),
    ) {
        Box(
            Modifier
                .sharedPhoto(img.id)
                .clip(RoundedCornerShape(if (style == CardStyle.BARE) 6.dp else 2.dp))
                .background(pal.paperWell),
        ) {
            AsyncImage(
                model = img.thumb,
                contentDescription = img.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(cardRatio(img)),
            )
        }
    }
}

/* ─────────────────────────── metadata line ──────────────────────────────── */

@Composable
fun MetaItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label.uppercase(),
            color = Ink.faint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Text(
            value,
            color = Ink.body,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────────────── skeleton placeholder ───────────────────────────── */

@Composable
fun SkeletonCard(ratio: Float, index: Int) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 40L); entered = true }
    val appear by animateFloatAsState(if (entered) 1f else 0f, label = "skeleton")
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 24f }
            .paperSurface(shape)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(2.dp))
                .background(Paper.imageWell),
        )
    }
}
