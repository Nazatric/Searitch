@file:OptIn(ExperimentalLayoutApi::class)

package app.searitch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.Image as CoilImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the hero paints first, and what (if anything) it upgrades to.
 *
 * The old hero painted the *grid thumbnail* — 300x200 for Wallhaven — stretched
 * across the screen, then waited seconds for the original. At phone size the
 * preview is already sharp, so it goes up immediately; the original is only
 * fetched when the preview is actually smaller than that.
 */
private data class HeroRendition(val first: String, val sharp: String?)

private fun heroRendition(img: SearitchImage): HeroRendition {
    // ratioHonest() rewrites a Wallhaven fixed-crop thumb to its /orig/ form.
    val preview = img.heroPicture()
    val first = preview.ifBlank { img.full }
    // A hero is at most a phone wide (~1400px at 3x), so a preview at or above
    // that is already indistinguishable from the original and fetching it would
    // be waste. Below it — a Wallhaven `/orig/` thumb is 213px wide — the original
    // is fetched, or the hero would be an upscale.
    val sharp = when {
        preview.isBlank() -> img.full.takeIf { it != first }
        img.width in 1..1400 -> null
        else -> img.full.takeIf { it != first }
    }
    return HeroRendition(first, sharp)
}

private val SKELETON_RATIOS = listOf(1.5f, 0.75f, 1.1f, 0.7f, 1.35f, 0.9f)

/** What sits behind a picture that can have holes in it. */
private val HeroBacking = Color(0xFF000000)

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
    val sharer = rememberSharer()
    val wallpaperSetter = rememberWallpaperSetter()
    // The photo as the search returned it, then as the source describes it once
    // its own record has been read. Wallhaven's search payload carries no title
    // and no tags, so this is where "Untitled Photo" and the word "wallpaper" as
    // a similar-image subject both go away.
    var shown by remember(img.id) { mutableStateOf(img) }
    var related by remember(img.id) { mutableStateOf(RelatedCache.get(img.id)) }
    var byLook by remember(img.id) { mutableStateOf(false) }
    var saveState by remember(img.id) { mutableStateOf(SaveState.Idle) }
    var saveMessage by remember(img.id) { mutableStateOf<String?>(null) }
    var wallState by remember(img.id) { mutableStateOf(SaveState.Idle) }
    var wallMessage by remember(img.id) { mutableStateOf<String?>(null) }
    // Pinch-to-scale, and the readout that says how far in you are. ImageSearchMan
    // shows the same thing; without it a zoomed picture gives no clue where you are.
    var zoom by remember(img.id) { mutableFloatStateOf(1f) }
    var pan by remember(img.id) { mutableStateOf(Offset.Zero) }
    // Read inside the gesture loop so widening the pinch doesn't restart it.
    val zoomedNow = rememberUpdatedState(zoom)
    val uriHandler = LocalUriHandler.current
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val platformContext = LocalPlatformContext.current

    LaunchedEffect(img.id) {
        val fuller = runCatching { Api.hydrate(img) }.getOrDefault(img)
        if (fuller != img) shown = fuller
    }

    LaunchedEffect(img.id, shown) {
        if (related != null || !settings.related) return@LaunchedEffect
        val fresh = runCatching { Api.related(shown) }
            .getOrDefault(emptyList())
            .filter { it.id != img.id }
        // Subject already ordered these; the pixels decide the last step.
        val refined = runCatching { Visual.rank(shown, fresh) }
            .getOrNull()
            ?: Visual.Refined(fresh.take(18), false)
        RelatedCache.put(img.id, refined.images)
        byLook = refined.byLook
        related = refined.images
    }

    // From the format first, then the decoder — whatever the bitmap says wins,
    // because a black stage behind an opaque picture is invisible and a paper
    // one behind a cut-out is not.
    val alphaHint = remember(shown.id, shown.filetype, shown.full) { shown.canHaveAlpha() }
    val rendition = remember(img.id) { heroRendition(img) }
    val firstPainter = rememberAsyncImagePainter(model = rendition.first)

    /*
     * Coil's `painter.state` is a StateFlow, not Compose state: reading it with an
     * `is` check compiles, evaluates exactly once, and then never updates. That is
     * why the badge sat on "FINISHING" forever with the full-size image already
     * downloaded and cached — the UI had simply stopped listening. The states are
     * collected as Compose state below, so the badge clears and the sharp version
     * appears the moment the decode lands.
     */
    var retryTick by remember(img.id) { mutableIntStateOf(0) }
    val sharpModel: Any? = remember(img.id, retryTick) {
        val url = rendition.sharp
        when {
            url == null -> null
            // First attempt: the plain URL, so it shares Coil's cache with the
            // rest of the app. A retry needs a new memory-cache key, otherwise
            // Coil would replay the cached failure and the button would do nothing.
            retryTick == 0 -> url
            else -> ImageRequest.Builder(platformContext)
                .data(url)
                .memoryCacheKey("$url#retry$retryTick")
                .build()
        }
    }
    val sharpPainter = sharpModel?.let { rememberAsyncImagePainter(model = it) }

    val firstState by firstPainter.state.collectAsState()
    // An explicitly typed flow, so `collectAsState()` picks the StateFlow overload
    // and needs no initial value.
    val idle = remember { MutableStateFlow<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val sharpFlow: StateFlow<AsyncImagePainter.State> = sharpPainter?.state ?: idle
    val sharpState by sharpFlow.collectAsState()

    val sharpLoaded = sharpState is AsyncImagePainter.State.Success
    val sharpFailed = sharpState is AsyncImagePainter.State.Error
    // Cached images decode in a frame or two; a badge that flashes on every open
    // is noise, so it waits until the fetch is genuinely taking a moment.
    var waited by remember(img.id) { mutableStateOf(false) }
    LaunchedEffect(img.id) {
        delay(400)
        waited = true
    }
    val sharpAlpha by animateFloatAsState(
        targetValue = if (sharpLoaded) 1f else 0f,
        animationSpec = tween(if (settings.motion) 260 else 0),
        label = "heroSharpen",
    )
    val decodedHero: CoilImage? =
        (sharpState as? AsyncImagePainter.State.Success)?.result?.image
            ?: (firstState as? AsyncImagePainter.State.Success)?.result?.image

    // Format hint first, then the decoder itself — the bitmap has the last word,
    // because a black stage behind an opaque picture is invisible while a paper
    // one behind a cut-out is not.
    val decodedAlpha = remember(decodedHero) { decodedHero?.decodedAlpha() }
    val hasAlpha = alphaHint || decodedAlpha == true

    /*
     * The frame is sized to the picture, and the picture is drawn whole.
     *
     * The reported dimensions are the starting frame, but a source can report one
     * shape and send another, and a card can be opened from a cached feed — so
     * the decoded bitmap has the last word. Fit (never Crop) then means the whole
     * image is visible with no bars, because the frame is already its shape.
     */
    var frameRatio by remember(img.id) { mutableStateOf(heroRatio(img)) }
    LaunchedEffect(decodedHero) {
        val bmp = decodedHero
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            frameRatio = (bmp.width.toFloat() / bmp.height).coerceIn(0.24f, 3.2f)
        }
    }
    val shownHeroRatio by animateFloatAsState(
        targetValue = frameRatio,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        label = "heroRatio",
    )
    // On Android 14+, the window switches to HDR only when this decoded image
    // actually contains an Ultra HDR gain map. SDR sources remain ordinary SDR.
    PlatformHdrWindow(image = decodedHero, enabled = true)

    // The photo sits on top of the results screen now, so it takes every tap:
    // otherwise a press on bare leather would reach the grid underneath.
    Column(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            .leatherSurface(tall = true),
    ) {
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
                        displayTitle(shown.title) ?: "${shown.sourceSite} plate",
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
                            .fillMaxWidth()
                            .aspectRatio(shownHeroRatio)
                            .clip(RoundedCornerShape(3.dp))
                            // A PNG with holes in it needs something honest
                            // behind it. Paper would show through the clear
                            // parts as a cream cast, so transparent formats get
                            // black — the detail view only; the cards keep their
                            // paper, where the shape reads fine at thumbnail size.
                            .background(if (hasAlpha) HeroBacking else Paper.imageWell),
                    ) {
                        Box(
                            Modifier
                                // The shared element is the picture alone. The
                                // backing is a separate, static surface, so the
                                // flight from the card never drags a black slab
                                // across the page with it.
                                .sharedPhoto(img.id)
                                .fillMaxSize()
                                .photoGestures(
                                    key = img.id,
                                    zoomed = { zoomedNow.value > 1.005f },
                                    onZoom = { pinch -> zoom = (zoom * pinch).coerceIn(1f, 6f) },
                                    onPan = { pan += it },
                                ),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = zoom
                                        scaleY = zoom
                                        translationX = pan.x
                                        translationY = pan.y
                                    },
                            ) {
                                ComposeImage(
                                    painter = firstPainter,
                                    contentDescription = img.title,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (sharpPainter != null) {
                                    ComposeImage(
                                        painter = sharpPainter,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer { alpha = sharpAlpha },
                                    )
                                }
                            }
                        }
                        if (zoom > 1.005f) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .clip(CircleShape)
                                    .clickable { zoom = 1f; pan = Offset.Zero }
                                    .background(Color(0xCC1C1008))
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    "${(zoom * 10).toInt() / 10f}×  TAP TO FIT",
                                    color = Ink.thread,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.2.sp,
                                )
                            }
                        }
                        /*
                         * The tag reports what is actually happening. While the
                         * full-size file is in flight it says so; if the fetch
                         * fails it stops pretending and offers the retry that
                         * used to be missing — a badge that never clears is worse
                         * than no badge at all.
                         */
                        if (sharpPainter != null && !sharpLoaded && (sharpFailed || waited)) {
                            val failed = sharpFailed
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (failed) {
                                            Modifier.clickable { retryTick++ }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .background(Color(0xCC1C1008))
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    if (failed) "TAP TO RETRY" else "FINISHING",
                                    color = Ink.thread,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.2.sp,
                                )
                            }
                        }
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

                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PaperAction("Share", Modifier.weight(1f)) {
                            sharer.share(displayTitle(shown.title) ?: shown.sourceSite, shown.sourceUrl)
                        }
                        PaperAction(
                            when (wallState) {
                                SaveState.Saving -> "Preparing…"
                                SaveState.Saved -> "Opened ✓"
                                else -> "Wallpaper"
                            },
                            Modifier.weight(1f),
                        ) {
                            if (wallState == SaveState.Saving) return@PaperAction
                            val url = downloadUrlFor(shown, DownloadQuality.ORIGINAL)
                            wallState = SaveState.Saving
                            wallMessage = null
                            scope.launch {
                                val outcome = runCatching { wallpaperSetter.set(url) }
                                    .getOrElse { DownloadOutcome(false, it.message ?: "Couldn't set it.") }
                                wallState = if (outcome.ok) SaveState.Saved else SaveState.Failed
                                wallMessage = outcome.message
                                if (outcome.ok) {
                                    haptics.tap()
                                    // The device's setter is in front of the app
                                    // now, so the button resets quietly rather
                                    // than sitting on "Opened ✓" for the next
                                    // time the screen is seen.
                                    kotlinx.coroutines.delay(1200)
                                    wallState = SaveState.Idle
                                }
                            }
                        }
                    }
                    wallMessage?.let { msg ->
                        Text(
                            msg,
                            color = if (wallState == SaveState.Failed) Ink.danger else Ink.soft,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                        )
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
                // Say what it searched for: the subject it took from this image's
                // own tags, or from its title when it has none. A plain call, not
                // `remember` — the grid's content lambda isn't composable.
                val keywords = Api.subjectLabel(shown)
                item(span = StaggeredGridItemSpan.FullLine, key = "similar") {
                    Column(Modifier.padding(top = 14.dp, bottom = 2.dp, start = 2.dp)) {
                        Text(
                            "SIMILAR IMAGES",
                            style = TextStyle(
                                color = Ink.onLeather,
                                fontFamily = cinzel(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 3.sp,
                            ),
                        )
                        if (keywords.isNotEmpty()) {
                            Text(
                                if (byLook) "matching “$keywords” · nearest look first"
                                else "matching “$keywords”",
                                color = Ink.onLeatherSoft,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }

                when (val rel = related) {
                    null -> items(6) { i -> SkeletonCard(SKELETON_RATIOS[i % SKELETON_RATIOS.size], i) }
                    else ->
                        if (rel.isEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "none") {
                                Text(
                                    if (keywords.isEmpty()) {
                                        "No similar images found for this one."
                                    } else {
                                        "Nothing came back for “$keywords”."
                                    },
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

/**
 * Two fingers zoom the picture; at fit size one finger still scrolls the page,
 * and once zoomed in one finger pans it.
 *
 * The first version of this used the general transform detector, which treats a
 * single-finger drag as a pan: it swallowed the gesture, so the photo panel —
 * the biggest thing on the screen — could not be dragged and the page only
 * scrolled from the thumbnail strip below it. This waits for a second finger
 * before it consumes anything, so the picture scrolls like every other part of
 * the page and pinching still works.
 */
private fun Modifier.photoGestures(
    key: Any,
    zoomed: () -> Boolean,
    onZoom: (Float) -> Unit,
    onPan: (Offset) -> Unit,
): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                when {
                    pressed.size >= 2 -> {
                        // A real pinch is ours.
                        val pinch = event.calculateZoom()
                        if (pinch != 1f) {
                            onZoom(pinch)
                            pressed.forEach { it.consume() }
                        }
                    }
                    // One finger pans only once the picture is bigger than its
                    // frame; at fit size it stays with the page's scroll.
                    zoomed() && pressed.size == 1 -> {
                        val change = pressed.first()
                        val delta = change.position - change.previousPosition
                        if (delta != Offset.Zero) {
                            onPan(delta)
                            change.consume()
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * The quiet sibling of [BrassButton]: same paper field as SOURCE, for actions
 * that are worth having but shouldn't compete with Download.
 */
@Composable
private fun PaperAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .paperField()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = Ink.soft,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
