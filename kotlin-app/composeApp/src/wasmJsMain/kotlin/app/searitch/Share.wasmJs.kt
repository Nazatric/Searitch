package app.searitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/**
 * The browser has no share sheet to reach from Kotlin common code and no wall to
 * hang, so sharing opens the page — which is where the browser's own share and
 * "set as wallpaper" affordances live — and the wallpaper button says plainly
 * that it can't, instead of failing silently.
 */
@Composable
actual fun rememberSharer(): Sharer {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        object : Sharer {
            override fun share(title: String, pageUrl: String) {
                runCatching { uriHandler.openUri(pageUrl) }
            }
        }
    }
}

@Composable
actual fun rememberWallpaperSetter(): WallpaperSetter = remember {
    object : WallpaperSetter {
        override suspend fun set(imageUrl: String): DownloadOutcome =
            DownloadOutcome(false, "Wallpapers need the phone build — download it here and set it from your desktop.")
    }
}
