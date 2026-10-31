@file:OptIn(ExperimentalLayoutApi::class)

package app.searitch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.Image as CoilImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch

/** A sharper-than-thumbnail rendition for the hero (Wikimedia serves any standard width). */
private fun heroModel(img: SearitchImage): String =
    if (img.width >= 1280 && Regex("/thumb/.+/\\d+px-").containsMatchIn(img.thumb)) {
        img.thumb.replace(Regex("/\\d+px-"), "/1280px-")
    } else {
        img.full
    }

private val SKELETON_RATIOS = listOf(1.5f, 0.75f, 1.1f, 0.7f, 1.35f, 0.9f)

/** Similar-image sets are kept for the session so re-opening a photo is instant. */
private object RelatedCache {
    private const val LIMIT = 40
    private val map = HashMap<String, List<SearitchImage>>()
    private val order = ArrayDeque<String>()

    fun get(id: String): List<SearitchImage>? = map[id]

    fun put(id: String, list: List<SearitchImage>) {
        map[id] = list
        order.remove(id)
        order.addLast(id)
        while (order.size > LIMIT) map.remove(order.removeFirst())
    }
}

private enum class SaveState { Idle, Saving, Saved, Failed }

@Composable
fun DetailScreen(
    img: SearitchImage,
    onOpenImage: (SearitchImage) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    settingsOpen: Boolean,
) {
    val settings = LocalSettings.current.value
    val scope = rememberCoroutineScope()
    val downloader = rememberImageDownloader()
    val haptics = rememberHaptics()
    var related by remember(img.id) { mutableStateOf(RelatedCache.get(img.id)) }
    var saveState by remember(img.id) { mutableStateOf(SaveState.Idle) }
    var saveMessage by remember(img.id) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(img.id) {
        if (related != null || !settings.related) return@LaunchedEffect
        val fresh = runCatching { Api.related(img) }
            .getOrDefault(emptyList())
            .filter { it.id != img.id }
            .take(18)
        RelatedCache.put(img.id, fresh)
        related = fresh
    }

    val ratio = if (img.width > 0 && img.height > 0) {
        (img.width.toFloat() / img.height).coerceIn(0.5f, 2.2f)
    } else {
        4f / 3f
    }
    val heroPainter = rememberAsyncImagePainter(model = heroModel(img))
    val decodedHero: CoilImage? =
        (heroPainter.state as? AsyncImagePainter.State.Success)?.result?.image
    // On Android 14+, the window switches to HDR only when this decoded image
    // actually contains an Ultra HDR gain map. SDR sources remain ordinary SDR.
    PlatformHdrWindow(image = decodedHero, enabled = true)

    Column(Modifier.fillMaxSize().leatherSurface(tall = true)) {
        /* ── leather strap ── */
        Row(
            modifier = Modifier
                .zIndex(1f)
                .fillMaxWidth()
                .shadow(8.dp)
                .leatherStrap()
                .statusBarsPadding()
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WellButton(onClick = onBack) { Icon(Ico.Back, Ink.thread, Modifier.size(24.dp)) }
            Text(
                img.title,
                style = TextStyle(
                    color = Ink.onLeather,
                    fontFamily = cinzel(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 0.6.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GearButton(onClick = onOpenSettings, open = settingsOpen)
        }

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = settings.gridDensity.minCardDp.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 32.dp + navPad),
            horizontalArrangement = Arrangement.spacedBy(settings.gridDensity.gapDp.dp),
            verticalItemSpacing = settings.gridDensity.gapDp.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            /* ── the mounted photo ── */
            item(span = StaggeredGridItemSpan.FullLine, key = "hero") {
                Column(
                    modifier = Modifier
                        .shadow(10.dp, RoundedCornerShape(8.dp))
                        .paperSurface(RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    // Cards show only the photo, so the title is announced here.
                    Text(
                        displayTitle(img.title) ?: "Untitled photo",
                        color = Ink.body,
                        fontFamily = cinzel(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 10.dp),
                    )
                    Box(
                        Modifier
                            .sharedPhoto(img.id)
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Paper.imageWell),
                    ) {
                        // the grid thumbnail is already cached → paints instantly under the sharp one
                        AsyncImage(
                            model = img.thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        ComposeImage(
                            painter = heroPainter,
                            contentDescription = img.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
                    ) {
                        MetaItem("Source", img.sourceSite, Modifier.widthIn(max = 170.dp))
                        MetaItem("Type", img.filetype.uppercase().ifEmpty { "—" })
                        MetaItem("Dimensions", if (img.width > 0) "${img.width} × ${img.height}" else "—")
                        img.creator?.let { MetaItem("Creator", it, Modifier.widthIn(max = 170.dp)) }
                        img.license?.let { MetaItem("License", it, Modifier.widthIn(max = 170.dp)) }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Saves the file into Download/Searitch — no browser hop.
                        Box(Modifier.weight(1f)) {
                            BrassButton(
                                when (saveState) {
                                    SaveState.Saving -> "Saving…"
                                    SaveState.Saved -> "Saved ✓"
                                    else -> "Download"
                                },
                                Modifier.fillMaxWidth(),
                            ) {
                                if (saveState == SaveState.Saving) return@BrassButton
                                val url = downloadUrlFor(img, settings.downloadQuality)
                                val name = downloadFileName(img, url)
                                saveState = SaveState.Saving
                                saveMessage = null
                                scope.launch {
                                    val outcome = runCatching { downloader.save(url, name) }
                                        .getOrElse { DownloadOutcome(false, it.message ?: "Download failed.") }
                                    saveState = if (outcome.ok) SaveState.Saved else SaveState.Failed
                                    saveMessage = outcome.message
                                    if (outcome.ok) haptics.tap()
                                    // let the button settle back to "Download" after a beat
                                    if (outcome.ok) {
                                        kotlinx.coroutines.delay(2600)
                                        if (saveState == SaveState.Saved) saveState = SaveState.Idle
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .paperField()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { uriHandler.openUri(img.sourceUrl) }
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "SOURCE",
                                color = Ink.soft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.8.sp,
                            )
                            Spacer(Modifier.size(8.dp))
                            Icon(Ico.External, Ink.soft, Modifier.size(16.dp))
                        }
                    }

                    if (saveState == SaveState.Saving) {
                        LinearSaveProgress()
                    }
                    saveMessage?.let { msg ->
                        Text(
                            msg,
                            color = if (saveState == SaveState.Failed) Ink.danger else Ink.soft,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                        )
                    }
                }
            }

            if (settings.related) {
                item(span = StaggeredGridItemSpan.FullLine, key = "similar") {
                    Text(
                        "SIMILAR IMAGES",
                        style = TextStyle(
                            color = Ink.onLeather,
                            fontFamily = cinzel(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 3.sp,
                        ),
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 2.dp),
                    )
                }

                when (val rel = related) {
                    null -> items(6) { i -> SkeletonCard(SKELETON_RATIOS[i % SKELETON_RATIOS.size], i) }
                    else ->
                        if (rel.isEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "none") {
                                Text(
                                    "No similar images found for this one.",
                                    color = Ink.onLeatherSoft,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(6.dp),
                                )
                            }
                        } else {
                            itemsIndexed(rel, key = { _, r -> "rel-${r.id}" }) { index, r ->
                                PhotoCard(r, index) { onOpenImage(it) }
                            }
                        }
                }
            }
        }
    }
}

/** A brass thread that sweeps while the file is being written. */
@Composable
private fun LinearSaveProgress() {
    val pal = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "save")
    val x by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)),
        label = "saveSweep",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 4.dp, end = 4.dp)
            .height(3.dp)
            .clip(CircleShape)
            .background(pal.brassBase.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.34f)
                .height(3.dp)
                .graphicsLayer { translationX = size.width * (x / 0.34f) * 0.66f }
                .clip(CircleShape)
                .background(brassBrush()),
        )
    }
}
