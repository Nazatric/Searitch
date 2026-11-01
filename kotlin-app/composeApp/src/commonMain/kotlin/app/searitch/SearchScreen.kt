@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.searitch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/* ───────────────────────── smart facets & active filters ─────────────────── */

private data class Facet(val label: String, val count: Int, val next: Filters)

/** Drill-down suggestions computed from the images actually returned, with real counts. */
private fun buildFacets(results: List<SearitchImage>, f: Filters): List<Facet> {
    if (results.size < 6) return emptyList()
    val out = mutableListOf<Facet>()
    val typed = listOf(TypeFilter.JPG, TypeFilter.PNG, TypeFilter.GIF, TypeFilter.SVG)
    results.groupingBy { it.filetype.lowercase() }.eachCount().entries
        .sortedByDescending { it.value }.take(3).forEach { (ext, n) ->
            val t = typed.firstOrNull { it.name.lowercase() == ext }
            if (t != null && n >= 3 && f.type != t) out += Facet("${t.label} only", n, f.copy(type = t))
        }
    val siteMap = mapOf(
        "bing" to ProviderFilter.BING,
        "openverse" to ProviderFilter.OPENVERSE,
        "flickr" to ProviderFilter.FLICKR,
        "wallhaven" to ProviderFilter.WALLHAVEN,
        "wikimedia commons" to ProviderFilter.WIKIMEDIA,
        "inaturalist" to ProviderFilter.INATURALIST,
        "nasa" to ProviderFilter.NASA,
        "the met" to ProviderFilter.MET,
        "art institute of chicago" to ProviderFilter.AIC,
        "cleveland museum of art" to ProviderFilter.CLEVELAND,
        "library of congress" to ProviderFilter.LOC,
        "europeana" to ProviderFilter.EUROPEANA,
        "v&a museum" to ProviderFilter.VAM,
    )
    results.groupingBy { it.sourceSite }.eachCount().entries
        .sortedByDescending { it.value }.take(3).forEach { (site, n) ->
            val p = siteMap[site.lowercase()]
            if (p != null && n >= 3 && f.provider != p) out += Facet(site, n, f.copy(provider = p))
        }
    val wide = results.count { it.width > 0 && it.height > 0 && it.width.toFloat() / it.height >= 1.2f }
    val tall = results.count { it.width > 0 && it.height > 0 && it.width.toFloat() / it.height <= 1f / 1.2f }
    if (wide >= 4 && f.aspect != AspectFilter.WIDE) out += Facet("Landscape", wide, f.copy(aspect = AspectFilter.WIDE))
    if (tall >= 4 && f.aspect != AspectFilter.TALL) out += Facet("Portrait", tall, f.copy(aspect = AspectFilter.TALL))
    val big = results.count { it.width.toLong() * it.height >= 6_000_000L }
    if (big >= 4 && f.size != SizeFilter.LARGE) out += Facet("High-res", big, f.copy(size = SizeFilter.LARGE))
    return out.take(6)
}

private fun activeLabels(f: Filters): List<Pair<String, Filters>> = buildList {
    if (f.type != TypeFilter.ANY) add(f.type.label to f.copy(type = TypeFilter.ANY))
    if (f.aspect != AspectFilter.ANY) add(f.aspect.label to f.copy(aspect = AspectFilter.ANY))
    if (f.size != SizeFilter.ANY) add("${f.size.label} size" to f.copy(size = SizeFilter.ANY))
    colorLabel(f)?.let { add(it to f.copy(color = "", colorHex = "")) }
    if (f.license != LicenseFilter.ANY) add(f.license.label to f.copy(license = LicenseFilter.ANY))
    if (f.provider != ProviderFilter.ALL) add(f.provider.label to f.copy(provider = ProviderFilter.ALL))
    if (!f.safe) add("Safe search: OFF" to f.copy(safe = true))
    if (!f.antiAi) add("AI Art: Allowed" to f.copy(antiAi = true))
}

private fun merge(current: List<SearitchImage>, chunk: List<SearitchImage>): List<SearitchImage> {
    val seenFull = current.mapTo(HashSet()) { it.full }
    val seenId = current.mapTo(HashSet()) { it.id }
    return current + chunk.filter { seenFull.add(it.full) && seenId.add(it.id) }
}

private val SKELETON_RATIOS = listOf(1.5f, 0.75f, 1.1f, 0.7f, 1.35f, 0.9f, 1.6f, 0.8f)

/* ───────────────────────────────── screen ────────────────────────────────── */

@Composable
fun SearchScreen(
    session: SearchSession,
    onSearch: (String, Filters) -> Unit,
    onOpenImage: (SearitchImage) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    settingsOpen: Boolean,
) {
    val settings = LocalSettings.current.value
    val scope = rememberCoroutineScope()
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var input by remember(session.id) { mutableStateOf(session.query) }
    var sheetOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(session.filters) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Scroll survives the trip into a photo and back (state lives in the session).
    val gridState = rememberLazyStaggeredGridState(session.scrollIndex, session.scrollOffset)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> session.scrollIndex = i; session.scrollOffset = o }
    }

    // The entrance is a first-impression thing. Once this feed has been on
    // screen, coming back to it must show it already there — cards fading in
    // again from nothing reads as the grid having lost its place, even when the
    // scroll offset is exact.
    LaunchedEffect(session.id) {
        kotlinx.coroutines.delay(900)
        session.entranceDone = true
    }

    /* Cache-first load: a feed we already have paints instantly, then (only if
       it's stale) a quiet refresh streams in behind it. */
    LaunchedEffect(session.id, session.attempt) {
        // first mount of a session loads it; coming back from a photo must not
        // re-query, but an explicit "Try again" bumps [SearchSession.attempt]
        if (session.attempt == 0 && session.started) return@LaunchedEffect
        session.started = true
        val cached = runCatching { FeedCache.load(session.id) }.getOrNull()
        if (cached != null && cached.images.isNotEmpty()) {
            session.results = cached.images
            session.pages = cached.pages
            session.lastCount = cached.images.size
            session.done = true
            session.fromCache = true
            if (FeedCache.isFresh(cached)) return@LaunchedEffect
        }
        session.loading = session.results.isEmpty()
        session.error = null
        var failure: Throwable? = null
        try {
            Api.searchStream(session.query, session.filters, 1).collect { chunk ->
                session.results = merge(session.results, chunk)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }
        session.loading = false
        session.done = true
        session.lastCount = session.results.size
        session.refreshedAt = currentTimeMillis()
        if (session.results.isEmpty()) session.error = failure?.message
        session.fromCache = false
        if (settings.cacheFeeds && session.results.isNotEmpty()) {
            FeedCache.store(
                session.id,
                CachedFeed(session.results, session.pages, currentTimeMillis(), session.query),
                persist = true,
                scope = scope,
            )
        }
    }

    val loadMore: () -> Unit = {
        if (session.hasMore && !session.loadingMore) {
            session.loadingMore = true
            val next = session.pages + 1
            scope.launch {
                var added = 0
                try {
                    Api.searchStream(session.query, session.filters, next).collect { chunk ->
                        val before = session.results.size
                        session.results = merge(session.results, chunk)
                        added += session.results.size - before
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                session.pages = next
                session.lastCount = added
                session.loadingMore = false
                if (settings.cacheFeeds && session.results.isNotEmpty()) {
                    FeedCache.store(
                        session.id,
                        CachedFeed(session.results, session.pages, currentTimeMillis(), session.query),
                        persist = true,
                        scope = scope,
                    )
                }
            }
        }
    }
    val loadMoreNow by rememberUpdatedState(loadMore)
    val sizeNow by rememberUpdatedState(session.results.size)

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= sizeNow - 6) loadMoreNow() }
    }

    val facets = remember(session.results, session.filters) { buildFacets(session.results, session.filters) }
    val active = remember(session.filters) { activeLabels(session.filters) }

    // What the app understood from the words themselves, and what would narrow
    // them further. Both are suggestions, never silent changes: a hint is only
    // applied when it is tapped, and a refinement only when it is added.
    val plan = remember(session.query) { Query.read(session.query) }
    val hints = remember(session.query, session.filters) {
        val withHints = plan.applyTo(session.filters)
        // Only the parts that would actually change something are worth a chip.
        if (withHints == session.filters) emptyList() else plan.understood()
    }
    val refinements = remember(session.results.size, session.query) {
        Query.refinements(session.results, plan)
    }
    val sourcesAnswered = remember(session.results) {
        session.results.map { it.provider }.distinct().size
    }
    val loading = session.results.isEmpty() && !session.done
    val density = settings.gridDensity
    val bottomBar = settings.barPlacement == BarPlacement.BOTTOM

    val showTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 1 || gridState.firstVisibleItemScrollOffset > 480
        }
    }

    fun submit() {
        val q = input.trim()
        if (q.isNotEmpty() && q != session.query) onSearch(q, session.filters)
    }

    Box(Modifier.fillMaxSize().leatherSurface(tall = true)) {
        Column(Modifier.fillMaxSize()) {
            /* ── leather strap ── */
            Box(Modifier.zIndex(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp)
                        .leatherStrap()
                        .statusBarsPadding()
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WellButton(onClick = onBack) { Icon(Ico.Back, Ink.thread, Modifier.size(24.dp)) }
                    if (bottomBar) {
                        // The field lives at the bottom; the strap shows what
                        // you're looking at, so nothing on top competes with it.
                        Text(
                            session.query,
                            style = TextStyle(
                                color = Ink.onLeather,
                                fontFamily = cinzel(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp,
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                    } else {
                        SearchField(
                            input = input,
                            onInput = { input = it },
                            onSubmit = { submit() },
                            activeCount = session.filters.activeCount,
                            onOpenFilters = { draft = session.filters; sheetOpen = true },
                            shared = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GearButton(onClick = onOpenSettings, open = settingsOpen)
                }
                if (!session.done) ProgressThread(Modifier.align(Alignment.BottomCenter))
            }

            /* ── results ── */
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = density.minCardDp.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = navPad + if (bottomBar) 150.dp else 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(density.gapDp.dp),
                verticalItemSpacing = density.gapDp.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (hints.isNotEmpty() || refinements.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "smart") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hints.isNotEmpty()) {
                                Text(
                                    "READ FROM YOUR WORDS",
                                    style = TextStyle(
                                        color = Ink.onLeather.copy(alpha = 0.72f),
                                        fontFamily = cinzel(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        letterSpacing = 2.sp,
                                    ),
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    hints.forEach { hint ->
                                        SearitchChip("use $hint", false) {
                                            onSearch(session.query, plan.applyTo(session.filters))
                                        }
                                    }
                                }
                            }
                            if (refinements.isNotEmpty()) {
                                Text(
                                    "NARROW IT",
                                    style = TextStyle(
                                        color = Ink.onLeather.copy(alpha = 0.72f),
                                        fontFamily = cinzel(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        letterSpacing = 2.sp,
                                    ),
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    refinements.forEach { word ->
                                        SearitchChip("+ $word", false) {
                                            onSearch("${session.query} $word", session.filters)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (active.isNotEmpty() || facets.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine, key = "chips") {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            active.forEach { (label, without) -> SearitchChip("$label ×", true) { onSearch(session.query, without) } }
                            facets.forEach { fc -> SearitchChip("${fc.label}  ${fc.count}", false) { onSearch(session.query, fc.next) } }
                        }
                    }
                }
                when {
                    loading -> items(
                        count = 8,
                        contentType = { "skeleton" },
                    ) { i ->
                        SkeletonCard(SKELETON_RATIOS[i % SKELETON_RATIOS.size], i)
                    }

                    session.results.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                        EmptyState(
                            message = session.error ?: "No images matched. Try a broader keyword or loosen a filter.",
                            isError = session.error != null,
                            active = active,
                            onRemove = { onSearch(session.query, it) },
                            onClear = { onSearch(session.query, settings.baseFilters()) },
                            onRetry = { session.retry() },
                        )
                    }

                    else -> {
                        item(span = StaggeredGridItemSpan.FullLine, key = "count") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${session.results.size}${if (session.hasMore || !session.done) "+" else ""} RESULTS · " +
                                        "${sourcesAnswered} SOURCE${if (sourcesAnswered == 1) "" else "S"} · " +
                                        "“${session.query.uppercase()}”",
                                    style = TextStyle(
                                        color = Ink.onLeather,
                                        fontFamily = cinzel(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 2.sp,
                                    ),
                                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                                )
                                if (session.fromCache) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(Ink.thread.copy(alpha = 0.18f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            "CACHED",
                                            color = Ink.thread,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.sp,
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            items = session.results,
                            key = { _, img -> img.id },
                            // Cards of the same style are interchangeable, so the
                            // grid can recycle a slot instead of building a new one.
                            contentType = { _, _ -> "card-${settings.cardStyle}" },
                        ) { index, img ->
                            PhotoCard(
                                img = img,
                                index = index,
                                // A feed that has already been on screen keeps its
                                // cards still when you come back to it.
                                animateIn = !session.entranceDone,
                            ) { onOpenImage(it) }
                        }
                        if (session.loadingMore) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "more") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = Brass.base,
                                        trackColor = Leather.light,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        /* ── bottom dock ── */
        if (bottomBar) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = navPad + 12.dp),
            ) {
                SearchField(
                    input = input,
                    onInput = { input = it },
                    onSubmit = { submit() },
                    activeCount = session.filters.activeCount,
                    onOpenFilters = { draft = session.filters; sheetOpen = true },
                    modifier = Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(14.dp)),
                )
            }
        }

        /* ── back to top ── */
        if (showTop) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = navPad + if (bottomBar) 92.dp else 20.dp)
                    .size(48.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(brassBrush())
                    .border(1.dp, Brass.dark, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { scope.launch { gridState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center,
            ) { Icon(Ico.Up, Brass.text, Modifier.size(22.dp)) }
        }
    }

    /* ── filter sheet ── */
    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = Leather.base,
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .size(width = 44.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(Ink.thread.copy(alpha = 0.5f)),
                )
            },
        ) {
            FilterSheet(
                draft = draft,
                onChange = { draft = it },
                onApply = {
                    sheetOpen = false
                    onSearch(session.query, draft)
                },
                onDismiss = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { sheetOpen = false }
                },
            )
        }
    }
}

/* ─────────────────────────────── pieces ──────────────────────────────────── */

/** One search field, used by both placements so they behave identically. */
@Composable
private fun SearchField(
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    activeCount: Int,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
    shared: Boolean = false,
) {
    val pal = LocalPalette.current
    Row(
        modifier = modifier
            .then(if (shared) Modifier.sharedField() else Modifier)
            .height(44.dp)
            .paperField()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInput,
            singleLine = true,
            textStyle = TextStyle(color = Ink.strong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(pal.brassDark),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text("Search images…", color = Ink.faint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        if (input.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onInput("") },
                contentAlignment = Alignment.Center,
            ) { Icon(Ico.Close, Ink.faint, Modifier.size(16.dp)) }
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenFilters,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Ico.Filter, Ink.body, Modifier.size(20.dp))
            if (activeCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(14.dp)
                        .background(Color(0xFFB03A26), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$activeCount", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(
    draft: Filters,
    onChange: (Filters) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "FILTERS",
                style = TextStyle(
                    color = Ink.onLeather,
                    fontFamily = cinzel(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 3.sp,
                ),
            )
            SearitchChip("Reset all", selected = false) { onChange(Filters()) }
        }

        Column(
            Modifier.fillMaxWidth().paperSurface(RoundedCornerShape(8.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilterSection("FILE TYPE", TypeFilter.entries, draft.type, { it.label }) { onChange(draft.copy(type = it)) }
            FilterSection("ORIENTATION", AspectFilter.entries, draft.aspect, { it.label }) { onChange(draft.copy(aspect = it)) }
            Column {
                FilterSection("SIZE", SizeFilter.entries, draft.size, { it.label }) {
                    onChange(draft.copy(size = it))
                }
                Text(
                    "Checked against the dimensions the source reports. A picture " +
                        "whose size is unknown is left out rather than guessed at, so " +
                        "Landscape and Large mean exactly that.",
                    color = Ink.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Column {
                SectionLabel("COLOR")
                Text(
                    "Bing and Wallhaven filter by colour in their own index; " +
                        "Openverse matches it as a search word. Sources with no colour " +
                        "data sit this search out rather than show the wrong colour.",
                    color = Ink.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    COLOR_OPTIONS.forEach { c ->
                        // Picking a named swatch clears a wheel pick, and the
                        // other way round: one colour at a time.
                        val selected = draft.color == c.key && draft.colorHex.isEmpty()
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c.hex))
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) Brass.base else Color.Black.copy(alpha = 0.25f),
                                    CircleShape,
                                )
                                .clickable {
                                    onChange(
                                        if (selected) draft.copy(color = "")
                                        else draft.copy(color = c.key, colorHex = ""),
                                    )
                                },
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                ColorWheel(
                    picked = draft.colorHex,
                    onPick = { hex -> onChange(draft.copy(color = "", colorHex = hex)) },
                    onClear = { onChange(draft.copy(colorHex = "", color = "")) },
                )
            }
            Column {
                FilterSection("LICENSE", LicenseFilter.entries, draft.license, { it.label }) {
                    onChange(draft.copy(license = it))
                }
                Text(
                    "Only sources that publish a licence are searched: the museums, " +
                        "Openverse, Wikimedia, NASA, iNaturalist, Europeana and the V&A. " +
                        "Bing, Wallhaven and Flickr index other people's pages and are " +
                        "left out of a licence-filtered search.",
                    color = Ink.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            FilterSection("SOURCE", ProviderFilter.entries, draft.provider, { it.label }) { onChange(draft.copy(provider = it)) }

            Column {
                SectionLabel("SAFE SEARCH")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearitchChip("Strict (Safe ON)", draft.safe) { onChange(draft.copy(safe = true)) }
                    SearitchChip("Off (Unrestricted)", !draft.safe) { onChange(draft.copy(safe = false)) }
                }
                Text(
                    "Bing and Wallhaven filter server-side. Openverse has its own mature " +
                        "flag and Commons, NASA, the museums and the Library of Congress are " +
                        "curated collections. Flickr's public feed has no rating metadata at " +
                        "all, so it is skipped while safe search is on.",
                    color = Ink.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }

            Column {
                SectionLabel("ANTI-AI FILTER")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearitchChip("Block AI Images (ON)", draft.antiAi) { onChange(draft.copy(antiAi = true)) }
                    SearitchChip("Allow AI Art", !draft.antiAi) { onChange(draft.copy(antiAi = false)) }
                }
                Text(
                    "Hides results whose title, tags or creator identify AI generation. Unlabelled AI images may still appear.",
                    color = Ink.faint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrassButton("Apply", Modifier.weight(1f)) { onApply() }
            BrassButton("Close", Modifier.weight(0.6f)) { onDismiss() }
        }
    }
}

/** A thread of light running under the strap while a search is still arriving. */
@Composable
private fun ProgressThread(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "thread")
    val x by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1050, easing = FastOutSlowInEasing)),
        label = "threadX",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                val w = size.width * 0.38f
                val left = size.width * x
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xFFF1D98F), Color.Transparent),
                        startX = left,
                        endX = left + w,
                    ),
                    topLeft = Offset(left, 0f),
                    size = Size(w, size.height),
                )
            },
    )
}

@Composable
private fun EmptyState(
    message: String,
    isError: Boolean,
    active: List<Pair<String, Filters>>,
    onRemove: (Filters) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .paperSurface(RoundedCornerShape(8.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Ico.Search, Ink.faint, Modifier.size(28.dp))
        Text(
            if (isError) "Couldn’t search" else "Nothing found",
            style = TextStyle(color = Ink.danger, fontFamily = cinzel(), fontWeight = FontWeight.Bold, fontSize = 18.sp),
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(message, color = Ink.soft, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        if (active.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                active.forEach { (label, without) -> SearitchChip("Remove $label", false) { onRemove(without) } }
                if (active.size > 1) SearitchChip("Clear all filters", true) { onClear() }
            }
        }
        if (isError || active.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            BrassButton("Try again", Modifier.width(180.dp)) { onRetry() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = TextStyle(
            color = Ink.faint,
            fontFamily = cinzel(),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        ),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun <T> FilterSection(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        SectionLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt -> SearitchChip(text(opt), selected = opt == selected) { onSelect(opt) } }
        }
    }
}
