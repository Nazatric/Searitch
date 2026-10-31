package app.searitch

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

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
            color.isNotEmpty(),
            license != LicenseFilter.ANY,
            provider != ProviderFilter.ALL,
            !safe,
            !antiAi,
        ).count { it }
}
