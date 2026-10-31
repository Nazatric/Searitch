package app.searitch

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android downloads go straight into Download/Searitch.
 *
 * API 29+  → streamed in-process into MediaStore, so the file lands instantly,
 *            shows up in Files/Gallery, and gets a unique name if one already
 *            exists. No permission needed.
 * API 24-28 → handed to DownloadManager (system notification) because scoped
 *            storage didn't exist yet; that path needs storage permission.
 */
@Composable
actual fun rememberImageDownloader(): ImageDownloader {
    val context = LocalContext.current
    return remember(context) {
        AndroidImageDownloader(context.applicationContext, context.findActivity())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

private class AndroidImageDownloader(
    private val context: Context,
    private val activity: Activity?,
) : ImageDownloader {

    override suspend fun save(url: String, fileName: String): DownloadOutcome {
        if (url.isBlank()) return DownloadOutcome(false, "This image has no file to download.")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(url, fileName)
        } else {
            saveWithDownloadManager(url, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveWithMediaStore(url: String, fileName: String): DownloadOutcome =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val ext = extensionOf(url).ifEmpty { fileName.substringAfterLast('.', "jpg") }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeForExtension(ext))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "Searitch",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            var conn: HttpURLConnection? = null
            var target: Uri? = null
            try {
                val live = open(url) ?: return@withContext DownloadOutcome(false, "Couldn't reach that file.")
                conn = live
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext DownloadOutcome(false, "Android refused a new download entry.")
                live.inputStream.use { input ->
                    resolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output, 64 * 1024)
                    } ?: return@withContext DownloadOutcome(false, "Couldn't open the download file.")
                }
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                DownloadOutcome(true, "Saved to Download/Searitch/$fileName")
            } catch (e: Throwable) {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                DownloadOutcome(false, e.message ?: "Download failed.")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    /** Only used below API 29; DownloadManager writes the public folder itself. */
    private fun saveWithDownloadManager(url: String, fileName: String): DownloadOutcome {
        val granted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val asked = runCatching {
                activity?.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE,
                )
            }.isSuccess
            return DownloadOutcome(
                false,
                if (asked) "Allow storage access, then tap Download again."
                else "Allow storage access for Searitch, then tap Download again.",
            )
        }
        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Searitch")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Searitch/$fileName",
                )
                .addRequestHeader("User-Agent", USER_AGENT)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            DownloadOutcome(true, "Downloading to Download/Searitch/$fileName")
        } catch (e: Throwable) {
            DownloadOutcome(false, e.message ?: "Download failed.")
        }
    }

    private fun open(url: String): HttpURLConnection? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            null
        } else {
            conn
        }
    } catch (e: Throwable) {
        null
    }

    private companion object {
        const val REQUEST_STORAGE = 4711
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Searitch/1.1"
    }
}
