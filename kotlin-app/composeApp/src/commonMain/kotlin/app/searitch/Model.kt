package app.searitch

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Wallhaven serves `/small/` at 300×200 and `/lg/` at 432×243 — fixed landscape
 * crops, whatever the wallpaper's real shape. Drawing one of those in a portrait
 * frame crops the crop again, which is why a tall illustration arrived looking
 * like a zoomed-in fragment. `/orig/` is the only rendition that keeps the real
 * aspect ratio, and it is still a thumbnail (a 1559×2197 wall arrives at
 * 213×300), so it costs nothing.
 *
 * Applied at display time as well as when a result is parsed, so feeds already
 * written to disk by an older build are repaired without a migration.
 */
fun ratioHonest(url: String?): String? {
    if (url.isNullOrBlank()) return null
    if (!url.contains("th.wallhaven.cc")) return url
    return url.replace("/small/", "/orig/").replace("/lg/", "/orig/")
}

/** The rendition a grid card draws. */
fun SearitchImage.cardPicture(): String = ratioHonest(thumb) ?: full

/** The rendition the detail hero paints first. */
fun SearitchImage.heroPicture(): String = ratioHonest(preview) ?: cardPicture()

/**
 * Whether the picture can have see-through parts.
 *
 * PNG, WebP and SVG carry an alpha channel; JPEG never does. Some sources leave
 * `filetype` empty or send "image/jpeg" through it, so the URL is asked as well —
 * a `.png` on the end of the biggest rendition is a better answer than nothing.
 * Only used to choose what sits *behind* the picture, so a wrong guess is a
 * shade, never a failure.
 */
fun SearitchImage.canHaveAlpha(): Boolean {
    val declared = filetype.substringAfter('/', filetype).lowercase().trim()
    if (declared in ALPHA_FORMATS) return true
    if (declared.isNotEmpty() && declared in setOf("jpg", "jpeg", "gif", "bmp")) return false
    return listOf(full, preview ?: "", thumb).any { url ->
        url.substringBefore('?').substringAfterLast('.').lowercase() in ALPHA_FORMATS
    }
}

private val ALPHA_FORMATS = setOf("png", "svg", "webp", "avif", "tiff", "tif")

/** One normalized image result, regardless of which source it came from. */
@Immutable
@Serializable
data class SearitchImage(
    val id: String,
    val provider: String,
    val title: String,
    val thumb: String,
    /** Lower-res rendition the UI falls back to if `thumb` fails. */
    val thumbFallback: String? = null,
    /**
     * Screen-resolution rendition, between [thumb] and [full].
     * The detail hero paints this first: at phone size it is already sharp, so
     * a photo never appears blurry while the original downloads.
     */
    val preview: String? = null,
    val full: String,
    val width: Int,
    val height: Int,
    val filetype: String,
    val sourceSite: String,
    val sourceUrl: String,
    val creator: String? = null,
    val license: String? = null,
    /** Real semantic tags/categories from the source — powers smart similarity. */
    val tags: List<String> = emptyList(),
)

enum class TypeFilter(val label: String) {
    ANY("Any"), JPG("JPG"), PNG("PNG"), GIF("GIF"), SVG("SVG"), TRANSPARENT("Transparent")
}

enum class AspectFilter(val label: String) {
    ANY("Any"), WIDE("Landscape"), TALL("Portrait"), SQUARE("Square")
}

enum class SizeFilter(val label: String) {
    ANY("Any"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large")
}

enum class LicenseFilter(val label: String) {
    ANY("Any"), COMMERCIAL("Commercial use"), MODIFICATION("Allows editing")
}

enum class ProviderFilter(val label: String) {
    ALL("All sources"),
    BING("Bing — full web"),
    YANDEX("Yandex — full web"),
    OPENVERSE("Openverse"),
    WIKIMEDIA("Wikimedia"),
    WALLHAVEN("Wallhaven"),
    FLICKR("Flickr"),
    INATURALIST("iNaturalist"),
    NASA("NASA"),
    MET("The Met"),
    AIC("Art Institute of Chicago"),
    CLEVELAND("Cleveland Museum"),
    LOC("Library of Congress"),
    EUROPEANA("Europeana"),
    VAM("V&A Museum"),
}

data class ColorOption(val key: String, val label: String, val hex: Long, val wallhaven: String)

/**
 * The colour wheel can pick any shade there is, but the sources that filter by
 * colour only understand a handful of names. A custom pick therefore keeps its
 * exact hex for the one source that takes a hex (Wallhaven) and is described to
 * the name-only sources by its nearest named colour — which is what a person
 * means when they say "roughly red" anyway.
 */
fun nearestColorName(hex: String): ColorOption? {
    val rgb = parseHex(hex) ?: return null
    val r0 = (rgb shr 16) and 0xFF
    val g0 = (rgb shr 8) and 0xFF
    val b0 = rgb and 0xFF
    return COLOR_OPTIONS.minByOrNull { option ->
        val r = ((option.hex shr 16) and 0xFF).toInt()
        val g = ((option.hex shr 8) and 0xFF).toInt()
        val b = (option.hex and 0xFF).toInt()
        // Weighted the way the eye sees it, so a deep cyan reads "blue", not "green".
        val dr = (r0 - r) * 0.30
        val dg = (g0 - g) * 0.59
        val db = (b0 - b) * 0.11
        dr * dr + dg * dg + db * db
    }
}

/** "3aa7ff" or "#3AA7FF" → 0x3AA7FF. */
fun parseHex(hex: String): Int? {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6 || clean.any { it.digitToIntOrNull(16) == null }) return null
    return clean.toIntOrNull(16)
}

/** 0xRRGGBB → "#RRGGBB", which is what the Wallhaven API wants. */
fun hexString(rgb: Int): String = (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

/** What the filter sheet calls the current colour. */
fun colorLabel(f: Filters): String? {
    if (f.colorHex.isNotEmpty()) {
        val named = nearestColorName(f.colorHex)
        val hex = f.colorHex.uppercase()
        return if (named != null) "#$hex · near ${named.label}" else "#$hex"
    }
    return COLOR_OPTIONS.firstOrNull { it.key == f.color }?.label
}

val COLOR_OPTIONS = listOf(
    ColorOption("red", "Red", 0xFFC0392B, "cc0000"),
    ColorOption("orange", "Orange", 0xFFE67E22, "ff9900"),
    ColorOption("yellow", "Yellow", 0xFFF1C40F, "ffcc33"),
    ColorOption("green", "Green", 0xFF27AE60, "669900"),
    ColorOption("teal", "Teal", 0xFF16A085, "66cccc"),
    ColorOption("blue", "Blue", 0xFF2980B9, "0066cc"),
    ColorOption("purple", "Purple", 0xFF8E44AD, "663399"),
    ColorOption("pink", "Pink", 0xFFE84393, "ea4c88"),
    ColorOption("brown", "Brown", 0xFF8D5A2B, "996633"),
    ColorOption("black", "Black", 0xFF1B1B1B, "000000"),
    ColorOption("white", "White", 0xFFF5F0E6, "ffffff"),
    ColorOption("gray", "Gray", 0xFF95A5A6, "999999"),
)

@Immutable
@Serializable
data class Filters(
    val type: TypeFilter = TypeFilter.ANY,
    val aspect: AspectFilter = AspectFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY,
    val color: String = "",
    /** A colour from the wheel, "RRGGBB". Takes precedence over [color]. */
    val colorHex: String = "",
    val license: LicenseFilter = LicenseFilter.ANY,
    val provider: ProviderFilter = ProviderFilter.ALL,
    val safe: Boolean = true,
    val antiAi: Boolean = true,
) {
    val activeCount: Int
        get() = listOf(
            type != TypeFilter.ANY,
            aspect != AspectFilter.ANY,
            size != SizeFilter.ANY,
            color.isNotEmpty() || colorHex.isNotEmpty(),
            license != LicenseFilter.ANY,
            provider != ProviderFilter.ALL,
            !safe,
            !antiAi,
        ).count { it }
}
