@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.searitch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.size.Precision

/** Simple, dependency-free navigation back-stack. */
sealed interface Screen {
    data object Home : Screen
    data class Search(val session: SearchSession) : Screen
    data class Detail(val image: SearitchImage) : Screen
}

/**
 * Holds every search the user has done this session, above the navigation
 * graph. Screens can be thrown away and rebuilt without losing a feed — which
 * is exactly what used to make Back re-run the search and shuffle the results.
 */
private class SessionHost {
    private val map = mutableStateMapOf<String, SearchSession>()
    private val order = mutableStateListOf<String>()

    fun forKey(query: String, filters: Filters): SearchSession {
        val key = FeedCache.key(query, filters)
        map[key]?.let { session ->
            order.remove(key)
            order.add(key)
            return session
        }
        val session = SearchSession(query, filters)
        map[key] = session
        order.add(key)
        // keep the last few searches warm; drop the coldest one's memory only
        while (order.size > 6) {
            val cold = order.removeAt(0)
            map.remove(cold)
        }
        return session
    }
}

@Composable
fun App(settings: SettingsState = remember { SettingsState.load() }) {
    /*
     * One image loader for the whole app.
     *
     * Coil's defaults are already the right shape — a quarter of the heap for
     * bitmaps and a shared disk cache — so this only adds the two things that
     * matter for a photo grid: the network fetcher, and an inexact decode size,
     * which lets one cached bitmap serve a card that lands at 212 px and one that
     * lands at 218 px instead of keeping two near-identical copies.
     */
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .precision(Precision.INEXACT)
            .build()
    }

    val host = remember { SessionHost() }
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var settingsOpen by remember { mutableStateOf(false) }
    val motion = settings.value.motion

    fun push(screen: Screen) = backStack.add(screen)

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /**
     * A new query — or a filter change on the current one — becomes a session.
     * Changing filters while results are on screen replaces the top of the
     * stack instead of stacking a second search screen.
     */
    fun openSearch(rawQuery: String, rawFilters: Filters) {
        // A file-format word is a statement about the file, not a preference:
        // "transparent png of a fox" can only mean PNG, so it is applied and
        // shows up as a chip the user can remove. Colour and size words stay
        // suggestions — "red panda" is not a request for red things.
        val plan = Query.read(rawQuery)
        val query = plan.text.ifEmpty { rawQuery.trim() }
        val filters = if (rawFilters.type == TypeFilter.ANY && plan.type != null) {
            rawFilters.copy(type = plan.type)
        } else {
            rawFilters
        }
        val session = host.forKey(query, filters)
        Recents.add(query)
        val top = backStack.lastOrNull()
        if (top is Screen.Search) {
            backStack[backStack.lastIndex] = Screen.Search(session)
        } else {
            push(Screen.Search(session))
        }
    }

    // Android back button / gesture pops the stack; at Home it lets the system
    // close the app, which is what a gesture-nav user expects.
    PlatformBackHandler(enabled = backStack.size > 1) { pop() }

    SearitchTheme(settings) {
        // system bars follow the hide, so the app never shows a grey scrim seam
        PlatformSystemBars(
            darkIcons = LocalPalette.current.leatherBase.luminance() > 0.42f,
            scrim = settings.value.leather.dark,
        )
        Surface(Modifier.fillMaxSize(), color = LocalPalette.current.leatherDark) {
            /*
             * Photos and the search field fly between screens (shared elements).
             *
             * One content at a time, which is the pattern shared elements are
             * built for: a key that exists in two visible contents at once has no
             * defined owner, and Compose draws it in a third, floating layer that
             * never settles — which is exactly what a photo did when it was shown
             * as an overlay above a still-composed grid.
             *
             * The grid no longer needs to be kept alive to come back to the right
             * place: a card's frame is now fixed by the size its source reported,
             * so a rebuilt grid lays out at identical heights and the saved
             * scroll index lands on the same card, first frame.
             */
            SharedTransitionLayout {
                AnimatedContent(
                    targetState = backStack.last(),
                    contentKey = { s ->
                        when (s) {
                            is Screen.Home -> "home"
                            is Screen.Search -> "search:${s.session.id}"
                            is Screen.Detail -> "detail:${s.image.id}"
                        }
                    },
                    transitionSpec = {
                        val photoFlight = targetState is Screen.Detail || initialState is Screen.Detail
                        val reduce = !motion
                        when {
                            reduce -> fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                            photoFlight -> fadeIn(tween<Float>(200)) togetherWith fadeOut(tween<Float>(200))
                            else -> {
                                val pushing = targetState is Screen.Search
                                val spec = spring<IntOffset>(
                                    dampingRatio = 0.85f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                                if (pushing) {
                                    (slideInHorizontally(spec) { it / 4 } + fadeIn(tween(220)))
                                        .togetherWith(slideOutHorizontally(spec) { -it / 5 } + fadeOut(tween(180)))
                                } else {
                                    (slideInHorizontally(spec) { -it / 5 } + fadeIn(tween(220)))
                                        .togetherWith(slideOutHorizontally(spec) { it / 4 } + fadeOut(tween(180)))
                                }
                            }
                        }
                    },
                    label = "screens",
                ) { screen ->
                    CompositionLocalProvider(
                        LocalSharedScope provides this@SharedTransitionLayout,
                        LocalVisibilityScope provides this@AnimatedContent,
                    ) {
                        when (screen) {
                            is Screen.Home -> HomeScreen(
                                onSearch = ::openSearch,
                                onOpenSettings = { settingsOpen = true },
                                settingsOpen = settingsOpen,
                            )

                            is Screen.Search -> SearchScreen(
                                session = screen.session,
                                onSearch = ::openSearch,
                                onOpenImage = { push(Screen.Detail(it)) },
                                onBack = { pop() },
                                onOpenSettings = { settingsOpen = true },
                                settingsOpen = settingsOpen,
                            )

                            is Screen.Detail -> DetailScreen(
                                img = screen.image,
                                onOpenImage = { push(Screen.Detail(it)) },
                                onFindColour = { hex ->
                                    // Stay inside the words that are already on
                                    // screen and narrow them by the colour: the
                                    // picture was found by those words, so they
                                    // are the right context for "more like this
                                    // blue".
                                    val search = backStack.lastOrNull { it is Screen.Search } as? Screen.Search
                                    val query = search?.session?.query
                                        ?: Api.subjectLabel(screen.image).ifBlank { Api.cleanQuery(screen.image.title) }
                                    val base = search?.session?.filters ?: Filters()
                                    openSearch(query, base.copy(color = "", colorHex = hex))
                                },
                                onBack = { pop() },
                                onOpenSettings = { settingsOpen = true },
                                settingsOpen = settingsOpen,
                            )
                        }
                    }
                }
            }

            // The sheet is an app-level overlay so it can open from any screen
            // and keeps its own animated gear state while it does.
            SettingsSheet(open = settingsOpen, onDismiss = { settingsOpen = false })
        }
    }
}
