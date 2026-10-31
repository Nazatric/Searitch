package app.searitch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Searitch/1.1"

/** Refuse anything bigger than this: the grid needs a thumbnail, not a poster. */
private const val MAX_BYTES = 4 * 1024 * 1024

/**
 * Downloads [url] and samples it. The decode is subsampled to roughly the grid
 * size, so a 4000 px wallpaper costs about as much as a 100 px chip.
 */
actual suspend fun probeLuminance(url: String, cols: Int, rows: Int): FloatArray? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = fetch(url) ?: return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            // inSampleSize is a power of two; aim just above the grid so the
            // samples are real pixels rather than one pixel stretched.
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= cols && bounds.outHeight / (sample * 2) >= rows) {
                sample *= 2
            }

            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: return@withContext null

            val out = sampleGrid(bitmap, cols, rows)
            bitmap.recycle()
            out
        } catch (e: Throwable) {
            null
        }
    }

private fun fetch(url: String): ByteArray? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 8_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "image/*,*/*;q=0.8")
    }
    return try {
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val declared = conn.contentLengthLong
        if (declared > MAX_BYTES) {
            conn.disconnect()
            return null
        }
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(32 * 1024)
        conn.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) return null
            }
        }
        conn.disconnect()
        out.toByteArray()
    } catch (e: Throwable) {
        runCatching { conn.disconnect() }
        null
    }
}

private fun sampleGrid(bitmap: Bitmap, cols: Int, rows: Int): FloatArray? {
    if (bitmap.width < 4 || bitmap.height < 4) return null
    val w = bitmap.width
    val h = bitmap.height
    // One pass over the pixels that matter instead of getPixel() per sample:
    // the sampled rows are contiguous strips.
    val out = FloatArray(cols * rows)
    val row = IntArray(w)
    var lastY = -1
    val xs = IntArray(cols) { i -> min(w - 1, max(0, (i * (w - 1)) / (cols - 1))) }
    for (y in 0 until rows) {
        val py = min(h - 1, max(0, (y * (h - 1)) / (rows - 1)))
        if (py != lastY) {
            bitmap.getPixels(row, 0, w, 0, py, w, 1)
            lastY = py
        }
        for (x in 0 until cols) {
            val c = row[xs[x]]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[y * cols + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
    }
    return out
}
