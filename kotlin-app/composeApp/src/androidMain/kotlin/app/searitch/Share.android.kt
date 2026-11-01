package app.searitch

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

/**
 * Hands the picture to the phone's own wallpaper setter.
 *
 * The old version called `WallpaperManager.setStream` directly, which set the
 * wallpaper with no say in the matter: no crop, no choice of home screen or lock
 * screen, no live-wallpaper route — nothing the device's own screen offers. What
 * a person expects from "set as wallpaper" is the setter their phone ships with,
 * the one they reach from Gallery, and that is an intent:
 *
 *  1. `ACTION_CROP_AND_SET_WALLPAPER` — the system's own crop-and-set screen
 *     (API 19+), which on MIUI/HyperOS and stock Android opens the wallpaper
 *     editor with home / lock / both.
 *  2. `ACTION_ATTACH_DATA` with an image MIME type — the "Set as" sheet every
 *     OEM understands, for the devices whose cropper activity isn't exported.
 *  3. Only if neither exists, the direct set, so the button is never dead.
 *
 * The file has to be a `content://` URI, hence FileProvider: a `file://` URI is
 * refused by the system on Android 7 and later.
 */
@Composable
actual fun rememberWallpaperSetter(): WallpaperSetter {
    val context = LocalContext.current
    return remember(context) {
        AndroidWallpaperSetter(context)
    }
}

private class AndroidWallpaperSetter(private val context: Context) : WallpaperSetter {

    override suspend fun set(imageUrl: String): DownloadOutcome = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext DownloadOutcome(false, "This image has no file.")
        val file = try {
            cacheCopy(imageUrl) ?: return@withContext DownloadOutcome(false, "Couldn't reach that file.")
        } catch (e: Throwable) {
            return@withContext DownloadOutcome(false, "Couldn't download that image.")
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Throwable) {
            return@withContext DownloadOutcome(false, "Couldn't hand the file to your device.")
        }

        // The system cropper first, then the generic "Set as" sheet.
        val cropper = Intent(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (launch(cropper)) return@withContext DownloadOutcome(true, "Choose where to set it.")

        val attach = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, "image/*")
            putExtra("mimeType", "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch(attach)) return@withContext DownloadOutcome(true, "Pick \"Set as wallpaper\" from the sheet.")

        // Nothing to open: do it the old way rather than fail.
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                WallpaperManager.getInstance(context).setStream(stream)
            }
            DownloadOutcome(true, "Set as your wallpaper.")
        } catch (e: Throwable) {
            DownloadOutcome(false, e.message?.take(120) ?: "Android couldn't set that as the wallpaper.")
        }
    }

    /**
     * Tries the intent and reports whether something took it.
     *
     * Deliberately not `resolveActivity`: since Android 11 a package-visibility
     * filter can hide an app that would happily answer, so asking first can say
     * "no" to a device that does have a wallpaper setter. Starting it and
     * catching the refusal is the accurate question.
     */
    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** One cached copy per image, so pressing the button twice doesn't re-fetch. */
    private fun cacheCopy(url: String): File? {
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        val name = "wall-${Integer.toHexString(url.hashCode())}.${extensionOf(url).ifEmpty { "jpg" }}"
        val target = File(folder, name)
        if (target.exists() && target.length() > 0L) return target

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            val temp = File(folder, "$name.part")
            conn.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) } }
            // A half-written file would land on the wallpaper as a grey slab.
            if (temp.length() <= 0L) {
                temp.delete()
                null
            } else {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) target else temp
            }
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
