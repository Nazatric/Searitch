package app.searitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/* ══════════════════════════════ settings model ═════════════════════════ */

/** Where the search field lives while you're looking at results. */
@Serializable
enum class BarPlacement(val label: String) {
    TOP("Top strap"),
    BOTTOM("Bottom dock"),
}

/** Where the search field sits on the landing screen. */
@Serializable
enum class HomeBarPosition(val label: String) {
    TOP("Top"), MIDDLE("Middle"), BOTTOM("Bottom"),
}

@Serializable
enum class GridDensity(val label: String, val minCardDp: Int, val gapDp: Int) {
    COMPACT("Compact", 108, 6),
    COMFORTABLE("Comfortable", 150, 10),
    ROOMY("Roomy", 200, 16),
}

@Serializable
enum class CardStyle(val label: String) {
    PRINT("Prints"),      // paper print with a slight natural tilt
    FLAT("Flat"),         // no tilt, still paper
    BARE("Bare"),         // image only, no paper edge
}

@Serializable
enum class LeatherTone(
    val label: String,
    val dark: Long,
    val base: Long,
    val light: Long,
    val highlight: Long,
) {
    CLASSIC("Classic hide", 0xFF24160D, 0xFF3A2418, 0xFF5A3620, 0xFF6E452A),
    ESPRESSO("Espresso", 0xFF170E08, 0xFF2A1A10, 0xFF452A1A, 0xFF553524),
    OXBLOOD("Oxblood", 0xFF2A0F10, 0xFF43191A, 0xFF5E2626, 0xFF6F3131),
    NIGHT("Midnight", 0xFF14161A, 0xFF22262C, 0xFF343B44, 0xFF414A55),
    OLIVE("Olive drab", 0xFF1B1D10, 0xFF2E3220, 0xFF444A30, 0xFF525A3B),
}

@Serializable
enum class PaperTone(
    val label: String,
    val base: Long,
    val deep: Long,
    val inset: Long,
    val well: Long,
) {
    CREAM("Cream", 0xFFF1E8D4, 0xFFE4D8BC, 0xFFE6DCC3, 0xFFCDBF9F),
    PARCHMENT("Parchment", 0xFFF4E7C8, 0xFFE6D3A9, 0xFFEEDDBB, 0xFFCFB98B),
    BONE("Bone", 0xFFEFECE2, 0xFFDFDACB, 0xFFE6E1D4, 0xFFC9C2AF),
    KRAFT("Kraft", 0xFFE8D9BB, 0xFFD8C49E, 0xFFE0CDAA, 0xFFC0A87F),
}

@Serializable
enum class BrassTone(
    val label: String,
    val light: Long,
    val base: Long,
    val dark: Long,
    val deep: Long,
    val text: Long,
) {
    GOLD("Brass", 0xFFE2C279, 0xFFC9A24B, 0xFF8B6B27, 0xFFBF9A45, 0xFF2F2209),
    SILVER("Silver", 0xFFEDEFF2, 0xFFC3C8CE, 0xFF7B828B, 0xFFB6BCC4, 0xFF1E2226),
    COPPER("Copper", 0xFFF0C0A0, 0xFFCD8B5A, 0xFF8A4E2B, 0xFFC17A4B, 0xFF2A160D),
}

@Serializable
enum class DownloadQuality(val label: String, val hint: String) {
    ORIGINAL("Original file", "Whatever the source hosts — can be very large."),
    LARGE("Large preview", "Caps around 2000px. Smaller, faster saves."),
}

@Immutable
@Serializable
data class AppSettings(
    val homeBar: HomeBarPosition = HomeBarPosition.MIDDLE,
    val barPlacement: BarPlacement = BarPlacement.TOP,
    val gridDensity: GridDensity = GridDensity.COMFORTABLE,
    val cardStyle: CardStyle = CardStyle.PRINT,
    val leather: LeatherTone = LeatherTone.CLASSIC,
    val paper: PaperTone = PaperTone.CREAM,
    val brass: BrassTone = BrassTone.GOLD,
    val textures: Boolean = true,
    val motion: Boolean = true,
    val haptics: Boolean = true,
    val related: Boolean = true,
    val cacheFeeds: Boolean = true,
    val downloadQuality: DownloadQuality = DownloadQuality.ORIGINAL,
    val defaultSafe: Boolean = true,
    val defaultAntiAi: Boolean = true,
    /** Read colour/format/intent out of the words, and suggest refinements. */
    val smartHints: Boolean = true,
) {
    /** The filters a brand-new search starts from. */
    fun baseFilters(): Filters = Filters(safe = defaultSafe, antiAi = defaultAntiAi)
}

/* ═══════════════════════════ composition locals ═════════════════════════ */

val LocalSettings = staticCompositionLocalOf { SettingsState(AppSettings()) }

/** Resolved colours for the current tone selection. */
@Immutable
class Palette(
    val leatherDark: Color,
    val leatherBase: Color,
    val leatherLight: Color,
    val leatherHighlight: Color,
    val paperBase: Color,
    val paperDeep: Color,
    val paperInset: Color,
    val paperWell: Color,
    val brassLight: Color,
    val brassBase: Color,
    val brassDark: Color,
    val brassDeep: Color,
    val brassText: Color,
    val thread: Color,
    val onLeather: Color,
    val onLeatherSoft: Color,
    // ink is derived from the paper, so every paper tone keeps its contrast
    val inkStrong: Color,
    val inkBody: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val fieldBorder: Color,
    val wellShadow: Color,
)

fun paletteFor(s: AppSettings): Palette = Palette(
    leatherDark = Color(s.leather.dark),
    leatherBase = Color(s.leather.base),
    leatherLight = Color(s.leather.light),
    leatherHighlight = Color(s.leather.highlight),
    paperBase = Color(s.paper.base),
    paperDeep = Color(s.paper.deep),
    paperInset = Color(s.paper.inset),
    paperWell = Color(s.paper.well),
    brassLight = Color(s.brass.light),
    brassBase = Color(s.brass.base),
    brassDark = Color(s.brass.dark),
    brassDeep = Color(s.brass.deep),
    brassText = Color(s.brass.text),
    thread = lerp(Color(s.brass.light), Color.White, 0.42f),
    onLeather = lerp(Color(s.paper.base), Color.White, 0.62f),
    onLeatherSoft = lerp(Color(s.paper.base), Color.White, 0.26f),
    // ink = the paper darkened; never pure black, so it reads as printed ink
    inkStrong = lerp(Color(s.paper.base), Color.Black, 0.80f),
    inkBody = lerp(Color(s.paper.base), Color.Black, 0.72f),
    inkSoft = lerp(Color(s.paper.base), Color.Black, 0.60f),
    inkFaint = lerp(Color(s.paper.base), Color.Black, 0.47f),
    fieldBorder = lerp(Color(s.paper.well), Color.Black, 0.55f).copy(alpha = 0.28f),
    wellShadow = Color(s.leather.dark),
)

val DefaultPalette = paletteFor(AppSettings())

val LocalPalette = staticCompositionLocalOf { DefaultPalette }

/* ═══════════════════════════════ state ══════════════════════════════════ */

private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val SETTINGS_KEY = "app.settings.v2"

/**
 * Observable settings that persist themselves. Every change is written through
 * immediately, so a force-stop never loses a preference.
 */
class SettingsState(initial: AppSettings = AppSettings()) {
    var value by mutableStateOf(initial)
        private set

    var palette: Palette by mutableStateOf(paletteFor(initial))
        private set

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(value)
        if (next == value) return
        value = next
        palette = paletteFor(next)
        persist()
    }

    /** Replace the whole settings object (the sheet edits a draft copy). */
    fun save(next: AppSettings) {
        if (next == value) return
        value = next
        palette = paletteFor(next)
        persist()
    }

    private fun persist() {
        runCatching { PlatformStore.putString(SETTINGS_KEY, settingsJson.encodeToString(AppSettings.serializer(), value)) }
    }

    companion object {
        fun load(): SettingsState {
            val raw = runCatching { PlatformStore.getString(SETTINGS_KEY) }.getOrNull()
            val parsed = raw?.let {
                runCatching { settingsJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull()
            }
            return SettingsState(parsed ?: AppSettings())
        }
    }
}

/* ═════════════════════════════ haptics ═════════════════════════════════ */

interface Haptics {
    fun tap()
    fun press()
}

@Composable
expect fun rememberHaptics(): Haptics
