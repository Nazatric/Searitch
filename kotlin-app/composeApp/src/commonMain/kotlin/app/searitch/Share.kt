package app.searitch

import androidx.compose.runtime.Composable

/**
 * Two things you want to do with a picture the moment you find it, and that no
 * search API can do for you: send it to someone, and hang it on the phone's
 * wall. Both are the platform's job, so both are expect/actual.
 */

/** Sending a find onwards through whatever the device calls sharing. */
interface Sharer {
    fun share(title: String, pageUrl: String)
}

@Composable
expect fun rememberSharer(): Sharer

/** Putting the picture under the clock and the icons. */
interface WallpaperSetter {
    suspend fun set(imageUrl: String): DownloadOutcome
}

@Composable
expect fun rememberWallpaperSetter(): WallpaperSetter
