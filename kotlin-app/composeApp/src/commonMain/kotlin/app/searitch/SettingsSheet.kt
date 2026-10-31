@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.searitch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The gear: a brass medallion that spins a third of a turn and pops when the
 * settings sheet is opened. Pure rotation of a vector cog — no assets.
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
            spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(0)
        },
        label = "gearSpin",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f
            open -> 1.06f
            else -> 1f
        },
        animationSpec = if (settings.motion) {
            spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(0)
        },
        label = "gearScale",
    )

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .scale(scale)
            .shadow(4.dp, shape)
            .clip(shape)
            // solid brass, not a translucent well: the gear is the one control
            // that is not a filter, so it gets the metal treatment
            .background(brassBrush(), shape)
            .border(1.dp, pal.brassDark, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Settings",
            ) {
                haptics.press()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Ico.Gear,
            pal.brassText,
            Modifier
                .size(23.dp)
                .rotate(rotation)
                .semantics { contentDescription = "Settings" },
        )
    }
}

/** Full customisation sheet. Edits a draft, applies on close so the app never
 *  flickers through half-finished states while the user is exploring. */
@Composable
fun SettingsSheet(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val holder = LocalSettings.current
    var draft by remember { mutableStateOf(holder.value) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var cacheBusy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(open) {
        cacheBytes = runCatching { FeedCache.sizeBytes() }.getOrDefault(0L)
    }

    fun applyAndClose() {
        holder.save(draft)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = {
            holder.save(draft)
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = LocalPalette.current.leatherBase,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 44.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(DefaultPalette.onLeather.copy(alpha = 0.45f)),
            )
        },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp + navPad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* ── heading ── */
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "SETTINGS",
                        style = TextStyle(
                            color = DefaultPalette.onLeather,
                            fontFamily = cinzel(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            letterSpacing = 3.sp,
                        ),
                    )
                    Text(
                        "Make it yours",
                        color = DefaultPalette.onLeatherSoft,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
                SearitchChip("Done", selected = true) { applyAndClose() }
            }

            Card("SEARCH FIELD") {
                OptionRow(
                    "Where the field sits",
                    BarPlacement.entries,
                    draft.barPlacement,
                    { it.label },
                ) { draft = draft.copy(barPlacement = it) }
                Hint("Bottom dock keeps the field under your thumb while you scroll.")
            }

            Card("GALLERY") {
                OptionRow("Card density", GridDensity.entries, draft.gridDensity, { it.label }) {
                    draft = draft.copy(gridDensity = it)
                }
                OptionRow("Card style", CardStyle.entries, draft.cardStyle, { it.label }) {
                    draft = draft.copy(cardStyle = it)
                }
                ToggleRow("Similar images under a photo", draft.related) {
                    draft = draft.copy(related = it)
                }
            }

            Card("MATERIALS") {
                ToneRow("Leather", draft.leather, LeatherTone.entries, { it.label }, { it.base }) {
                    draft = draft.copy(leather = it)
                }
                ToneRow("Paper", draft.paper, PaperTone.entries, { it.label }, { it.base }) {
                    draft = draft.copy(paper = it)
                }
                ToneRow("Metal", draft.brass, BrassTone.entries, { it.label }, { it.base }) {
                    draft = draft.copy(brass = it)
                }
                ToggleRow("Real hide & paper textures", draft.textures) {
                    draft = draft.copy(textures = it)
                }
                Hint("Turning textures off saves memory on older phones.")
            }

            Card("MOTION & FEEL") {
                ToggleRow("Animations", draft.motion) { draft = draft.copy(motion = it) }
                ToggleRow("Haptics", draft.haptics) { draft = draft.copy(haptics = it) }
            }

            Card("DOWNLOADS") {
                OptionRow(
                    "What Download saves",
                    DownloadQuality.entries,
                    draft.downloadQuality,
                    { it.label },
                ) { draft = draft.copy(downloadQuality = it) }
                Hint(draft.downloadQuality.hint + " Saved to Download/Searitch.")
            }

            Card("SEARCH DEFAULTS") {
                ToggleRow("Safe search on by default", draft.defaultSafe) {
                    draft = draft.copy(defaultSafe = it)
                }
                ToggleRow("Block AI images by default", draft.defaultAntiAi) {
                    draft = draft.copy(defaultAntiAi = it)
                }
                Hint("Applies to new searches. Filter chips in the field still override per search.")
            }

            Card("CACHE") {
                ToggleRow("Keep searched feeds", draft.cacheFeeds) {
                    draft = draft.copy(cacheFeeds = it)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Stored on this device", color = Ink.body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (cacheBusy) "Measuring…" else "${friendlySize(cacheBytes)} of feeds & photos",
                            color = Ink.faint,
                            fontSize = 11.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearitchChip("Refresh", selected = false) {
                            cacheBusy = true
                            scope.launch {
                                cacheBytes = runCatching { FeedCache.sizeBytes() }.getOrDefault(0L)
                                cacheBusy = false
                            }
                        }
                        SearitchChip("Clear", selected = false) {
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
                "SEARITCH · VERSION 1.1",
                style = TextStyle(
                    color = DefaultPalette.onLeatherSoft.copy(alpha = 0.8f),
                    fontFamily = cinzel(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 2.5.sp,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

/* ───────────────────────────── building blocks ────────────────────────── */

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().paperSurface(RoundedCornerShape(8.dp)).padding(14.dp)) {
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

/** A leather-toggle rendered as two chips, so it reads as hardware, not a form. */
@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearitchChip("On", selected = on) { onChange(true) }
            SearitchChip("Off", selected = !on) { onChange(false) }
        }
    }
}
