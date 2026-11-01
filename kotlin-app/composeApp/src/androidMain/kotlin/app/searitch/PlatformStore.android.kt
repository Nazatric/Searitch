package app.searitch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android store: SharedPreferences for settings, filesDir for cached feeds.
 * [attach] is called once from MainActivity with the application context.
 */
actual object PlatformStore {
    private const val PREFS = "searitch.settings"
    private const val DIR = "feeds"

    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
    }

    actual fun getString(key: String): String? =
        runCatching { prefs()?.getString(key, null) }.getOrNull()

    actual fun putString(key: String, value: String) {
        runCatching { prefs()?.edit()?.putString(key, value)?.apply() }
    }

    actual fun remove(key: String) {
        runCatching { prefs()?.edit()?.remove(key)?.apply() }
    }

    actual suspend fun readFile(name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(dir(), name)
            if (f.isFile) f.readText() else null
        }.getOrNull()
    }

    actual suspend fun writeFile(name: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            val d = dir() ?: return@runCatching
            // write-then-rename so a killed process never leaves half a feed
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            val target = File(d, name)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
        Unit
    }

    actual suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        runCatching { dir()?.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList() }
            .getOrDefault(emptyList())
    }

    actual suspend fun clearFiles() = withContext(Dispatchers.IO) {
        runCatching {
            dir()?.listFiles()?.forEach { it.delete() }
            // the decoded-image cache lives beside it; clearing the feed cache
            // is a "free up space" action, so clear that too.
            appContext?.let { ctx -> File(ctx.cacheDir, "image_cache").deleteRecursively() }
        }
        Unit
    }

    /** Coil's disk cache, which is the only large thing in cacheDir. */
    private fun imageDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.cacheDir, "image_cache")
    }

    actual suspend fun imageCacheSize(): Long = withContext(Dispatchers.IO) {
        runCatching {
            var total = 0L
            imageDir()?.walkTopDown()?.filter { it.isFile }?.forEach { total += it.length() }
            total
        }.getOrDefault(0L)
    }

    actual suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        runCatching { imageDir()?.deleteRecursively() }
        Unit
    }

    actual suspend fun filesSize(): Long = withContext(Dispatchers.IO) {
        runCatching {
            var total = 0L
            dir()?.listFiles()?.forEach { total += it.length() }
            appContext?.let { ctx ->
                File(ctx.cacheDir, "image_cache").walkTopDown().filter { it.isFile }.forEach { total += it.length() }
            }
            total
        }.getOrDefault(0L)
    }
}
