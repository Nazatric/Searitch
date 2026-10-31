package app.searitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/**
 * A browser can't silently write to the file system, so the web build opens
 * the file — the browser's own save dialog is the download there.
 */
@Composable
actual fun rememberImageDownloader(): ImageDownloader {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        object : ImageDownloader {
            override suspend fun save(url: String, fileName: String): DownloadOutcome {
                return try {
                    uriHandler.openUri(url)
                    DownloadOutcome(true, "Opened in a new tab — save it from there.")
                } catch (e: Throwable) {
                    DownloadOutcome(false, "Couldn't open that file.")
                }
            }
        }
    }
}
