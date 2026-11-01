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
import androidx.compose.foundation.layout.Box
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
    fun openSearch(query: String, filters: Filters) {
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

    // The screen under any photo: the photo itself is an overlay, never a
    // replacement, so the grid it came from is never torn down.
    val base: Screen = backStack.lastOrNull { it !is Screen.Detail } ?: Screen.Home
    val detail = backStack.lastOrNull() as? Screen.Detail

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
             * A photo used to open by *replacing* the results screen, which threw
             * the grid away — its cards were re-created on the way back, by which
             * time the shared element had already been measured against a grid
             * that had not restored its scroll yet. The picture therefore flew to
             * the wrong place and only found its slot once the list was dragged.
             * The results screen now stays composed underneath and the photo
             * opens on top of it, so the card the photo came from never stops
             * existing and the flight back always has somewhere true to land.
             */
            SharedTransitionLayout {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = base,
                        contentKey = { s ->
                            when (s) {
                                is Screen.Home -> "home"
                                is Screen.Search -> "search:${s.session.id}"
                                // Never the base — a photo is an overlay.
                                is Screen.Detail -> "detail"
                            }
                        },
                        transitionSpec = {
                            val reduce = !motion
                            when {
                                reduce -> fadeIn(tween(160)) togetherWith fadeOut(tween(120))
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
                                is Screen.Home, is Screen.Detail -> HomeScreen(
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
                            }
                        }
                    }

                    // The photo, over the results it came from. A fade, because
                    // the flight itself is the shared element — a slide on top of
                    // it would be two movements for one gesture.
                    AnimatedContent(
                        targetState = detail,
                        contentKey = { d -> d?.image?.id ?: "none" },
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val quick = if (motion) tween<Float>(220) else tween<Float>(1)
                            fadeIn(quick) togetherWith fadeOut(quick)
                        },
                        label = "photo",
                    ) { d ->
                        if (d == null) {
                            Box(Modifier.fillMaxSize())
                        } else {
                            CompositionLocalProvider(
                                LocalSharedScope provides this@SharedTransitionLayout,
                                LocalVisibilityScope provides this@AnimatedContent,
                            ) {
                                DetailScreen(
                                    img = d.image,
                                    onOpenImage = { push(Screen.Detail(it)) },
                                    onBack = { pop() },
                                    onOpenSettings = { settingsOpen = true },
                                    settingsOpen = settingsOpen,
                                )
                            }
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
