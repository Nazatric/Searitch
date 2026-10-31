package app.searitch

import androidx.compose.runtime.Composable

/** What happened when the user pressed Download. */
data class DownloadOutcome(val ok: Boolean, val message: String)

interface ImageDownloader {
    /**
     * Pulls [url] down and puts it in the device's downloads area. Suspends
     * only while the file is being written — callers show progress from a
     * coroutine.
     */
    suspend fun save(url: String, fileName: String): DownloadOutcome
}

@Composable
expect fun rememberImageDownloader(): ImageDownloader

/* ────────────────────────────── helpers ────────────────────────────────── */

private val BAD_CHARS = Regex("[^a-zA-Z0-9 _.-]")

/** A tidy, collision-free-ish file name derived from the photo's title. */
fun downloadFileName(img: SearitchImage, url: String): String {
    val ext = extensionOf(url).ifEmpty { img.filetype.lowercase().ifEmpty { "jpg" } }
    val base = displayTitle(img.title)
        ?.replace(Regex("\\s+"), " ")
        ?.replace(BAD_CHARS, "")
        ?.trim()
        ?.trimEnd('.', '-', '_')
        ?.take(60)
        ?.takeIf { it.isNotBlank() }
        ?: "searitch-${img.id.hashCode().toUInt().toString(16).take(8)}"
    val shortId = img.id.hashCode().toUInt().toString(16).take(4)
    return "$base-$shortId.$ext"
}

fun extensionOf(url: String): String {
    val raw = url.substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
    return if (raw.length in 2..5 && raw.all { it.isLetterOrDigit() }) raw else ""
}

fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "avif" -> "image/avif"
    "tif", "tiff" -> "image/tiff"
    else -> "image/*"
}

/**
 * Which rendition of [img] the current quality setting should fetch.
 * Wikimedia and friends expose arbitrary widths through their thumb URLs, so
 * "Large preview" really is a smaller file rather than a renamed original.
 */
fun downloadUrlFor(img: SearitchImage, quality: DownloadQuality): String = when (quality) {
    DownloadQuality.ORIGINAL -> img.full
    DownloadQuality.LARGE -> {
        val thumb = img.thumb
        if (img.width > 2400 && Regex("/thumb/.+/\\d+px-").containsMatchIn(thumb)) {
            thumb.replace(Regex("/\\d+px-"), "/2000px-")
        } else {
            img.full
        }
    }
}

/** "1.84 MB" — used by the cache rows in Settings. */
fun friendlySize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> {
        val mb = bytes / (1024L * 1024L)
        val frac = (bytes % (1024L * 1024L)) * 10 / (1024L * 1024L)
        "$mb.${frac} MB"
    }
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}
