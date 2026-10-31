package app.searitch

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val UA =
    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Searitch/1.1"

@Composable
actual fun rememberSharer(): Sharer {
    val context = LocalContext.current
    return remember(context) {
        object : Sharer {
            override fun share(title: String, pageUrl: String) {
                // The link, not the bytes: no permission, no temporary file to
                // clean up, and the person receiving it lands on the page that
                // credits whoever made the picture.
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, "$title\n$pageUrl")
                }
                val chooser = Intent.createChooser(intent, "Share this picture")
                // The context here is the activity's, but the flag costs nothing
                // and keeps this working if it is ever handed an application one.
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(chooser) }
            }
        }
    }
}

@Composable
actual fun rememberWallpaperSetter(): WallpaperSetter {
    val context = LocalContext.current
    return remember(context) {
        object : WallpaperSetter {
            override suspend fun set(imageUrl: String): DownloadOutcome = withContext(Dispatchers.IO) {
                if (imageUrl.isBlank()) return@withContext DownloadOutcome(false, "This image has no file.")
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", UA)
                    }
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        return@withContext DownloadOutcome(false, "Couldn't reach that file.")
                    }
                    // WallpaperManager decodes and scales it itself; the stream is
                    // only held for as long as that takes.
                    conn.inputStream.use { stream ->
                        WallpaperManager.getInstance(context).setStream(stream)
                    }
                    DownloadOutcome(true, "Set as your wallpaper.")
                } catch (e: Throwable) {
                    DownloadOutcome(false, e.message?.take(120) ?: "Android couldn't set that as the wallpaper.")
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }
        }
    }
}
