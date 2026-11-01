package app.searitch

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One search, as it was made: the words, the filters that were on, and when.
 *
 * The list used to be bare strings, which meant tapping a past search silently
 * dropped every filter you had picked for it — a search for "fox" with PNG and
 * portrait on came back as plain "fox". Keeping the filters makes a past search
 * a real shortcut rather than a reminder.
 */
@Serializable
data class RecentSearch(
    val query: String,
    val filters: Filters = Filters(),
    /** Wall-clock millis, or 0 when it came from the old plain-string list. */
    val at: Long = 0L,
    /** How many times it has been run; a repeated search is a favourite. */
    val uses: Int = 1,
)

/**
 * The last few searches, and what they were made of.
 *
 * Two keys exist on purpose. `v1` held plain strings; it is still read once so
 * nobody loses the searches they already had, and everything new is written to
 * `v3` as structured records. Nothing migrates in place — the old list is left
 * alone as a fallback.
 */
object Recents {
    private const val KEY = "recent.searches.v3"
    private const val LEGACY_KEY = "recent.searches.v1"
    private const val LIMIT = 8
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<RecentSearch> {
        val raw = runCatching { PlatformStore.getString(KEY) }.getOrNull()
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                json.decodeFromString(ListSerializer(RecentSearch.serializer()), raw)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return legacy()
    }

    private fun legacy(): List<RecentSearch> {
        val raw = runCatching { PlatformStore.getString(LEGACY_KEY) }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), raw)
                .filter { it.isNotBlank() }
                .map { RecentSearch(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Records a search. Runs through every entry point — the home field, the
     * results field, a facet, a refinement — so "recent" means what the app
     * actually searched for, not just what was typed on the first screen.
     */
    fun add(query: String, filters: Filters = Filters()) {
        val q = query.trim()
        if (q.isEmpty()) return
        val existing = list()
        val match = existing.firstOrNull {
            it.query.equals(q, ignoreCase = true) && it.filters == filters
        }
        val next = buildList {
            add(RecentSearch(q, filters, at = 0L, uses = (match?.uses ?: 0) + 1))
            existing.forEach { r ->
                if (r !== match) add(r)
            }
        }.take(LIMIT)
        runCatching {
            PlatformStore.putString(KEY, json.encodeToString(ListSerializer(RecentSearch.serializer()), next))
        }
    }

    /**
     * Searches to offer while typing: ones that start with what has been typed,
     * then ones that contain it. The app's own history is the only suggestion
     * source that can be honest about what it is.
     */
    fun matching(prefix: String, limit: Int = 4): List<RecentSearch> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        val all = list()
        val starts = all.filter { it.query.lowercase().startsWith(p) && it.query.lowercase() != p }
        val contains = all.filter { it.query.lowercase().contains(p) && it !in starts }
        return (starts + contains).take(limit)
    }

    fun clear() {
        runCatching { PlatformStore.remove(KEY) }
        runCatching { PlatformStore.remove(LEGACY_KEY) }
    }
}
