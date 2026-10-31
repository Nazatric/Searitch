@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.searitch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The settings gear: a struck brass medallion.
 *
 * Drawn rather than iconified so it sits in the same material world as the
 * straps and cards: concentric metal shading, a raised rim, and a stamped cog
 * with a light catch along its top edge and shadow beneath (an intaglio cut,
 * not an outline). The cog alone turns when the sheet opens; the medallion
 * stays put, the way real hardware behaves.
 */
@Composable
fun GearButton(onClick: () -> Unit, open: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val settings = LocalSettings.current.value
    val pal = LocalPalette.current
    val haptics = rememberHaptics()

    val rotation by animateFloatAsState(
        targetValue = if (open) 120f else 0f,
        animationSpec = if (settings.motion) {
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(0)
        },
        label = "gearSpin",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            open -> 1.04f
            else -> 1f
        },
        animationSpec = if (settings.motion) {
            spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(0)
        },
        label = "gearScale",
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .scale(scale)
            .shadow(5.dp, CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Settings",
            ) {
                haptics.press()
                onClick()
            }
            .semantics { contentDescription = "Settings" }
            .drawBehind { drawMedallion(pal) },
        contentAlignment = Alignment.Center,
    ) {
        Cog(
            rotation = rotation,
            // engraved brass: the cog is darker metal in a lighter disc, never
            // black — black here read as a sticker sitting on top of the metal
            top = lerp(pal.brassDark, pal.leatherBase, 0.35f),
            bottom = lerp(pal.brassDark, Color.Black, 0.30f),
            modifier = Modifier.size(25.dp),
        )
    }
}

/** The medallion face: turned metal, a rim, and an inner shadow. */
private fun DrawScope.drawMedallion(pal: Palette) {
    val r = size.minDimension / 2f
    // face: light from the top-left, like every other surface in the app
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                lerp(pal.brassLight, Color.White, 0.28f),
                pal.brassLight,
                pal.brassBase,
                pal.brassDeep,
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        radius = r,
    )
    // struck rim: bright inner hairline, dark outer edge
    drawCircle(
        color = lerp(pal.brassLight, Color.White, 0.5f).copy(alpha = 0.85f),
        radius = r - 1.2.dp.toPx(),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = lerp(pal.brassDark, Color.Black, 0.35f).copy(alpha = 0.55f),
        radius = r - 0.5.dp.toPx(),
        style = Stroke(width = 1.1.dp.toPx()),
    )
    // a soft seated shadow so the cog looks pressed into the disc
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f)),
            center = Offset(this.center.x, this.center.y + 1.5.dp.toPx()),
            radius = r * 0.62f,
        ),
        radius = r * 0.62f,
    )
}

/**
 * Just the cog, filled (not outlined) so it survives at 25dp, with a highlight
 * offset up-left and a shadow down-right — the two-tone edge that makes an
 * engraving read as cut metal rather than a flat sticker.
 */
@Composable
fun Cog(
    rotation: Float,
    top: Color,
    bottom: Color,
    modifier: Modifier = Modifier,
    teeth: Int = 9,
    highlight: Color = Color.White.copy(alpha = 0.35f),
    shadowColor: Color = Color.Black.copy(alpha = 0.35f),
) {
    Canvas(modifier.rotate(rotation)) {
        val u = size.minDimension / 24f
        fun path(dx: Float, dy: Float): Path {
            val p = Path()
            val step = 360f / teeth
            val halfTop = 360f / teeth * 0.24f     // half of the flat crest
            val halfRoot = 360f / teeth * 0.40f    // half of the root gap
            val rCrest = 10.6f
            val rRoot = 7.6f
            fun at(deg: Float, rr: Float): Offset {
                val a = deg * PI / 180.0
                return Offset(
                    (12f + rr * cos(a)).toFloat() * u + dx,
                    (12f + rr * sin(a)).toFloat() * u + dy,
                )
            }
            for (i in 0 until teeth) {
                val base = i * step - 90f
                val a = at(base - halfTop, rCrest)
                val b = at(base + halfTop, rCrest)
                val c = at(base + halfRoot, rRoot)
                val d = at(base + step - halfRoot, rRoot)
                if (i == 0) p.moveTo(a.x, a.y) else p.lineTo(a.x, a.y)
                p.lineTo(b.x, b.y)
                p.lineTo(c.x, c.y)
                p.lineTo(d.x, d.y)
            }
            p.close()
            return p
        }

        val lift = 0.7.dp.toPx()
        drawPath(path(0f, lift), highlight, style = Fill)          // light catch below
        drawPath(path(-lift, -lift), shadowColor, style = Fill)     // cut edge above
        drawPath(
            path(0f, 0f),
            Brush.linearGradient(
                colors = listOf(top, bottom),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            style = Fill,
        )
        // hub: a dark well with a bright lip, which reads as a bore without
        // needing a clear-blend layer for one small hole
        drawCircle(color = bottom.copy(alpha = 0.55f), radius = 3.2f * u)
        drawCircle(color = highlight, radius = 3.2f * u, style = Stroke(1f * u))
        drawCircle(color = shadowColor, radius = 2.3f * u, style = Stroke(1f * u))
    }
}

/**
 * Full customisation sheet.
 *
 * Every control applies immediately — the tone/texture/density behind the sheet
 * repaints as you tap, which is the only honest way to judge a material. The
 * sheet is deliberately not a "draft + Apply" form: that hid the effect of half
 * the settings behind a button press.
 */
@Composable
fun SettingsSheet(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val holder = LocalSettings.current
    val draft = holder.value
    fun update(next: AppSettings) = holder.save(next)
    var cacheBytes by remember { mutableStateOf(0L) }
    var cacheBusy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(open) {
        cacheBytes = runCatching { FeedCache.sizeBytes() }.getOrDefault(0L)
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = sheetState,
        // the container stays transparent so the *textured* hide below shows;
        // a flat fill here was what made the sheet look like a different app
        containerColor = Color.Transparent,
        scrimColor = Color(0x99000000),
        tonalElevation = 0.dp,
        // square top edge: the strap is a page, not a floating card
        shape = RectangleShape,
        // The sheet owns its top edge: no floating grab pill over a flat gutter.
        // Its header is a real leather strap that runs up *behind* the status bar,
        // so the clock and battery sit on the same hide, sheen and stitching as
        // the home screen instead of hovering over bare window.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .leatherSurface(tall = true),
        ) {
            /* ── strap: fixed, runs full-bleed under the status bar ── */
            Column(
                Modifier
                    .fillMaxWidth()
                    .leatherStrap()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Ink.onLeather.copy(alpha = 0.40f)),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "SETTINGS",
                            style = TextStyle(
                                color = Ink.onLeather,
                                fontFamily = cinzel(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                letterSpacing = 3.sp,
                            ),
                        )
                        Text(
                            "Make it yours",
                            color = Ink.onLeatherSoft,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearitchChip("Defaults", selected = false) { update(AppSettings()) }
                        SearitchChip("Done", selected = true) { close() }
                    }
                }
            }

            /* ── page: the cards scroll, the strap stays put ── */
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp)
                    .padding(bottom = 24.dp + navPad),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A live sample: the chosen hide, stock and metal rendered exactly as
                // the app paints them, so a tone is judged on the real thing.
                MaterialPreview(Modifier.padding(horizontal = 16.dp))

                Card("HOME SCREEN") {
                    PositionSlider(
                        value = draft.homeBar,
                        onChange = { update(draft.copy(homeBar = it)) },
                    )
                    Hint("Where the search bar sits on the landing screen.")
                }

                Card("SEARCH FIELD") {
                    OptionRow(
                        "Where the field sits",
                        BarPlacement.entries,
                        draft.barPlacement,
                        { it.label },
                    ) { update(draft.copy(barPlacement = it)) }
                    Hint("Bottom dock keeps the field under your thumb while you scroll.")
                }

                Card("GALLERY") {
                    OptionRow("Card density", GridDensity.entries, draft.gridDensity, { it.label }) {
                        update(draft.copy(gridDensity = it))
                    }
                    OptionRow("Card style", CardStyle.entries, draft.cardStyle, { it.label }) {
                        update(draft.copy(cardStyle = it))
                    }
                    ToggleRow("Similar images under a photo", draft.related) {
                        update(draft.copy(related = it))
                    }
                }

                Card("MATERIALS") {
                    ToneRow("Leather", draft.leather, LeatherTone.entries, { it.label }, { it.base }) {
                        update(draft.copy(leather = it))
                    }
                    ToneRow("Paper", draft.paper, PaperTone.entries, { it.label }, { it.base }) {
                        update(draft.copy(paper = it))
                    }
                    ToneRow("Metal", draft.brass, BrassTone.entries, { it.label }, { it.base }) {
                        update(draft.copy(brass = it))
                    }
                    ToggleRow("Real hide & paper textures", draft.textures) {
                        update(draft.copy(textures = it))
                    }
                    Hint("Turning textures off saves memory on older phones.")
                }

                Card("MOTION & FEEL") {
                    ToggleRow("Animations", draft.motion) { update(draft.copy(motion = it)) }
                    ToggleRow("Haptics", draft.haptics) { update(draft.copy(haptics = it)) }
                }

                Card("DOWNLOADS") {
                    OptionRow(
                        "What Download saves",
                        DownloadQuality.entries,
                        draft.downloadQuality,
                        { it.label },
                    ) { update(draft.copy(downloadQuality = it)) }
                    Hint(draft.downloadQuality.hint + " Saved to Download/Searitch.")
                }

                Card("SEARCH DEFAULTS") {
                    ToggleRow("Safe search on by default", draft.defaultSafe) {
                        update(draft.copy(defaultSafe = it))
                    }
                    ToggleRow("Block AI images by default", draft.defaultAntiAi) {
                        update(draft.copy(defaultAntiAi = it))
                    }
                    Hint("Applies to new searches. Filter chips in the field still override per search.")
                }

                Card("CACHE") {
                    ToggleRow("Keep searched feeds", draft.cacheFeeds) {
                        update(draft.copy(cacheFeeds = it))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Stored on this device",
                                color = Ink.body,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (cacheBusy) "measuring…" else friendlySize(cacheBytes),
                                color = Ink.faint,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        // full-width row of two equal buttons: no label can be squeezed
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionChip(
                                "Refresh",
                                Modifier.weight(1f),
                                enabled = !cacheBusy,
                            ) {
                                cacheBusy = true
                                scope.launch {
                                    cacheBytes = runCatching { FeedCache.sizeBytes() }.getOrDefault(0L)
                                    cacheBusy = false
                                }
                            }
                            ActionChip(
                                "Clear cache",
                                Modifier.weight(1f),
                                enabled = !cacheBusy,
                            ) {
                                cacheBusy = true
                                scope.launch {
                                    runCatching { FeedCache.clearAll() }
                                    cacheBytes = runCatching { FeedCache.sizeBytes() }.getOrDefault(0L)
                                    cacheBusy = false
                                }
                            }
                        }
                    }
                    Hint("Photos stay cached while you browse, so returning to a search is instant.")
                }

                Text(
                    "SEARITCH · VERSION 1.5",
                    style = TextStyle(
                        color = Ink.onLeatherSoft.copy(alpha = 0.8f),
                        fontFamily = cinzel(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 2.5.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 6.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/* ───────────────────────────── building blocks ────────────────────────── */

/**
 * A brass trackslider for the landing-screen search bar.
 *
 * Discrete: three detents, each labelled, so the thumb *is* the answer rather
 * than an abstract number. The trough is the same debossed paper as a field and
 * the thumb is the same metal as every other control.
 */
@Composable
private fun PositionSlider(value: HomeBarPosition, onChange: (HomeBarPosition) -> Unit) {
    val pal = LocalPalette.current
    val options = HomeBarPosition.entries
    val index = options.indexOf(value).coerceAtLeast(0)
    val fraction by animateFloatAsState(
        targetValue = index / (options.size - 1).toFloat(),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "homeBarSlider",
    )

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .paperField(CircleShape)
                .pointerInput(options.size) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val slot = (off.x / size.width * (options.size - 1)).roundToInt()
                            onChange(options[slot.coerceIn(options.indices)])
                        },
                        onDrag = { change, _ ->
                            val slot = (change.position.x / size.width * (options.size - 1))
                                .roundToInt()
                            onChange(options[slot.coerceIn(options.indices)])
                        },
                    )
                }
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // thumb
            val track = maxWidth - 14.dp
            val thumb = 22.dp
            val travel = track - thumb
            Box(
                Modifier
                    .offset { IntOffset((travel.value * fraction * density).toInt(), 0) }
                    .size(thumb)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(brassBrush(), CircleShape)
                    .border(1.dp, pal.brassDark, CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            options.forEach { opt ->
                Text(
                    opt.label.uppercase(),
                    color = if (opt == value) Ink.body else Ink.faint,
                    fontSize = 9.5.sp,
                    fontWeight = if (opt == value) FontWeight.Black else FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onChange(opt) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** A chip that fills its slot, so a long label can never be clipped mid-word. */
@Composable
private fun ActionChip(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .paperSurface(CircleShape)
            .border(1.dp, Ink.faint.copy(alpha = if (enabled) 0.3f else 0.15f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Ink.soft else Ink.faint.copy(alpha = 0.6f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MaterialPreview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .leatherStrap()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // a paper card on the hide, with a brass button seated in it
        Column(
            Modifier
                .weight(1f)
                .shadow(3.dp, RoundedCornerShape(6.dp))
                .paperSurface(RoundedCornerShape(6.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Aa",
                color = Ink.strong,
                fontFamily = cinzel(),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Text("STOCKED HIDE", color = Ink.faint, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(brassBrush(), CircleShape)
                    .border(1.dp, LocalPalette.current.brassDark, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "BRASS",
                    color = LocalPalette.current.brassText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PREVIEW", color = Ink.onLeatherSoft, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            // the real medallion, just not pressable in here
            val pal = LocalPalette.current
            Box(
                Modifier
                    .size(44.dp)
                    .shadow(5.dp, CircleShape)
                    .clip(CircleShape)
                    .drawBehind { drawMedallion(pal) },
                contentAlignment = Alignment.Center,
            ) {
                Cog(
                    rotation = 0f,
                    top = lerp(pal.brassDark, pal.leatherBase, 0.35f),
                    bottom = lerp(pal.brassDark, Color.Black, 0.30f),
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .paperSurface(RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Text(
            title,
            style = TextStyle(
                color = Ink.faint,
                fontFamily = cinzel(),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            ),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Ink.faint, fontSize = 11.sp)
}

@Composable
private fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, color = Ink.body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                SearitchChip(text(opt), selected = opt == selected) { onSelect(opt) }
            }
        }
    }
}

@Composable
private fun <T> ToneRow(
    label: String,
    selected: T,
    options: List<T>,
    text: (T) -> String,
    swatch: (T) -> Long,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, color = Ink.body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                val isOn = opt == selected
                val haptics = rememberHaptics()
                Row(
                    Modifier
                        .clip(CircleShape)
                        .then(
                            if (isOn) Modifier.background(brassBrush(), CircleShape)
                            else Modifier.paperSurface(CircleShape),
                        )
                        .border(
                            1.dp,
                            if (isOn) LocalPalette.current.brassDark else Ink.faint.copy(alpha = 0.3f),
                            CircleShape,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.tap()
                            onSelect(opt)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(swatch(opt)))
                            .border(1.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text(opt),
                        color = if (isOn) LocalPalette.current.brassText else Ink.soft,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * On/off is a *switch*, not a pair of chips: chips mean "choose one of these"
 * everywhere else in the app (filters, options), so booleans get their own
 * brass-hardware control.
 */
@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    val haptics = rememberHaptics()
    val motion = LocalSettings.current.value.motion
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
            ) {
                haptics.tap()
                onChange(!on)
            }
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Ink.body,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 10.dp).weight(1f),
        )
        /*
         * A paper trough with a brass knob — the same debossed field as the search
         * bar, so the switch belongs to the page instead of sitting on top of it.
         * (A dark leather channel here read as a black pill: exactly the "cheap
         * 3D widget" problem.)
         */
        val travel by animateFloatAsState(
            targetValue = if (on) 1f else 0f,
            animationSpec = if (motion) {
                spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
            } else {
                tween(0)
            },
            label = "knob",
        )
        Box(
            Modifier
                .size(width = 46.dp, height = 26.dp)
                .paperField(CircleShape)
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset((20f * travel * density).toInt(), 0) }
                    .size(20.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        if (on) brassBrush()
                        else Brush.verticalGradient(
                            listOf(Color.White, pal.paperDeep, pal.paperWell),
                        ),
                        CircleShape,
                    )
                    .border(1.dp, if (on) pal.brassDark else pal.paperWell, CircleShape),
            )
        }
    }
}
