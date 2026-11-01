package app.searitch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Two layers, the way a well-behaved Android app caches:
 *
 *  • memory — an LRU of the last 24 feeds, so going back is *instant*;
 *  • disk   — one JSON file per feed, so a search survives an app restart.
 *
 * A feed is only written to disk once it has settled, and stale-but-present
 * feeds render immediately while a refresh streams in behind them.
 */
@Serializable
data class CachedFeed(
    val images: List<SearitchImage> = emptyList(),
    val pages: Int = 1,
    val savedAt: Long = 0L,
    val query: String = "",
)

object FeedCache {
    const val MEMORY_LIMIT = 24
    const val FRESH_MS = 10 * 60 * 1000L        // instantly serve, don't refetch
    const val USABLE_MS = 7 * 24 * 60 * 60 * 1000L  // older than this: ignore

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /*
     * All reads/writes happen on the UI dispatcher (the cache is written from a
     * composition-scoped coroutine), so a plain map is enough — and it keeps the
     * LRU portable: kotlin.collections.LinkedHashMap isn't an open class on the
     * JS/Wasm targets, so eviction is tracked explicitly instead.
     */
    private val memory = HashMap<String, CachedFeed>()
    private val order = ArrayDeque<String>()

    private fun touch(key: String, feed: CachedFeed) {
        memory[key] = feed
        order.remove(key)
        order.addLast(key)
        while (order.size > MEMORY_LIMIT) {
            val cold = order.removeFirst()
            memory.remove(cold)
        }
    }

    private fun read(key: String): CachedFeed? {
        val hit = memory[key] ?: return null
        touch(key, hit)
        return hit
    }

    private val pending = mutableSetOf<String>()

    fun key(query: String, filters: Filters): String {
        val raw = buildString {
            append(query.trim().lowercase())
            append('|').append(filters.type).append('|').append(filters.aspect)
            append('|').append(filters.size).append('|').append(filters.color)
            append('|').append(filters.colorHex)
            append('|').append(filters.license).append('|').append(filters.provider)
            append('|').append(filters.safe).append('|').append(filters.antiAi)
        }
        return "${fnv(raw)}-${raw.length}"
    }

    private fun fileName(key: String) = "feed-$key.json"

    fun peek(key: String): CachedFeed? = read(key)

    /** Memory first, then disk. Null when nothing usable is stored. */
    suspend fun load(key: String): CachedFeed? {
        read(key)?.let { return it }
        val raw = PlatformStore.readFile(fileName(key)) ?: return null
        val feed = runCatching { json.decodeFromString(CachedFeed.serializer(), raw) }.getOrNull()
            ?: return null
        // a clock that went backwards (a page reload on web) means "unknown
        // age" — serve it and treat it as fresh rather than refetching blindly
        val age = now() - feed.savedAt
        if (age > 0 && age > USABLE_MS) return null
        touch(key, feed)
        return feed
    }

    fun store(key: String, feed: CachedFeed, persist: Boolean, scope: CoroutineScope?) {
        touch(key, feed)
        if (!persist || scope == null) return
        // one write in flight per feed is enough; the newest state wins next time
        if (!pending.add(key)) return
        scope.launch {
            try {
                PlatformStore.writeFile(fileName(key), json.encodeToString(CachedFeed.serializer(), feed))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                pending.remove(key)
            }
        }
    }

    suspend fun clearAll() {
        memory.clear()
        order.clear()
        PlatformStore.clearFiles()
    }

    /** Feeds only: the image cache is a separate figure in Settings. */
    suspend fun sizeBytes(): Long = PlatformStore.feedsSize()

    fun isFresh(feed: CachedFeed): Boolean {
        val age = now() - feed.savedAt
        return age <= 0 || age <= FRESH_MS
    }

    private fun now(): Long = currentTimeMillis()

    private fun fnv(text: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }
}

/** expect/actual clock keeps the common code free of platform date APIs. */
expect fun currentTimeMillis(): Long

/* ══════════════════════════════ session ═════════════════════════════════ */

/**
 * Everything one search owns — results, paging, cache state and the scroll
 * position. It lives above the navigation graph, which is what stops the feed
 * from being thrown away and re-queried when you come back from a photo.
 */
class SearchSession(
    val query: String,
    val filters: Filters,
) {
    val id: String = FeedCache.key(query, filters)

    var results by mutableStateOf<List<SearitchImage>>(emptyList())
    var pages by mutableIntStateOf(1)
    var lastCount by mutableIntStateOf(0)
    var done by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var fromCache by mutableStateOf(false)
    var refreshedAt by mutableStateOf(0L)

    /** Restored so returning from a photo lands exactly where you left. */
    var scrollIndex by mutableIntStateOf(0)
    var scrollOffset by mutableIntStateOf(0)

    /**
     * Whether this feed's cards have already made their entrance. Coming back
     * from a photo must not replay it: cards fading in again from nothing reads
     * as the grid having lost its place, even when the scroll is exact.
     */
    var entranceDone by mutableStateOf(false)

    var started by mutableStateOf(false)

    /** Bumped by "Try again" so the loader re-runs without a new session. */
    var attempt by mutableIntStateOf(0)

    fun retry() {
        error = null
        done = false
        loading = results.isEmpty()
        attempt++
    }

    val hasMore: Boolean get() = done && lastCount >= 12 && pages < 20
}
