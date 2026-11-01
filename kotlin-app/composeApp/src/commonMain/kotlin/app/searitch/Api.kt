package app.searitch

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Searitch search layer (common code — identical on Android and Wasm).
 *
 * Fourteen keyless sources, queried in parallel and merged as each one answers:
 * Bing and Yandex (the open web), Openverse, Wikimedia Commons, Wallhaven,
 * Flickr, iNaturalist, NASA, the Met, the Art Institute of Chicago, the Cleveland
 * Museum of Art, the Library of Congress, Europeana and the V&A.
 *
 * No source needs a key or an account. Filters are only applied where a source
 * can genuinely honour them — see [providers]. On the Wasm target the sources
 * that send no CORS headers (Bing, Wallhaven, Flickr) stay quiet instead of
 * failing the search.
 */
object Api {

    private const val PAGE_SIZE = 20

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = HttpClient {
        install(ContentNegotiation) { json(this@Api.json) }
        expectSuccess = false
    }

    // ------------------------------------------------------------ DTOs ----

    @Serializable
    private data class OvResponse(val results: List<OvImage> = emptyList())

    @Serializable
    private data class OvImage(
        val id: String = "",
        val title: String? = null,
        val url: String? = null,
        val thumbnail: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val filetype: String? = null,
        val source: String? = null,
        val provider: String? = null,
        @SerialName("foreign_landing_url") val foreignLandingUrl: String? = null,
        val creator: String? = null,
        val license: String? = null,
        @SerialName("license_version") val licenseVersion: String? = null,
        val tags: List<OvTag> = emptyList(),
    )

    @Serializable
    private data class OvTag(val name: String = "")

    @Serializable
    private data class WmResponse(val query: WmQuery? = null)

    @Serializable
    private data class WmQuery(val pages: List<WmPage> = emptyList())

    @Serializable
    private data class WmPage(
        val pageid: Long = 0,
        val title: String = "",
        val imageinfo: List<WmImageInfo> = emptyList(),
    )

    @Serializable
    private data class WmImageInfo(
        val url: String? = null,
        val descriptionurl: String? = null,
        val thumburl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val mime: String? = null,
        val extmetadata: WmExtMeta? = null,
    )

    @Serializable
    private data class WmExtMeta(
        @SerialName("Artist") val artist: WmMetaValue? = null,
        @SerialName("LicenseShortName") val licenseShortName: WmMetaValue? = null,
    )

    @Serializable
    private data class WmMetaValue(val value: String? = null)

    @Serializable
    private data class WhResponse(val data: List<WhImage> = emptyList())

    @Serializable
    private data class WhImage(
        val id: String = "",
        val url: String? = null,
        val path: String? = null,
        @SerialName("dimension_x") val dimensionX: Int? = null,
        @SerialName("dimension_y") val dimensionY: Int? = null,
        @SerialName("file_type") val fileType: String? = null,
        val thumbs: WhThumbs? = null,
        val tags: List<OvTag> = emptyList(),
        val category: String? = null,
    )

    /** `/w/{id}` wraps its single wall in `data`; `/search` puts a list there. */
    @Serializable
    private data class WhSingle(val data: WhImage? = null)

    @Serializable
    private data class WhThumbs(
        val small: String? = null,
        val large: String? = null,
        val original: String? = null,
    )

    // --------------------------------------------------------- helpers ----

    private fun extFromMime(mime: String?): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/svg+xml" -> "svg"
        "image/webp" -> "webp"
        else -> mime?.substringAfter('/') ?: ""
    }

    private fun stripHtml(html: String?): String? =
        html?.replace(Regex("<[^>]*>"), "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Openverse has no colour field — it searches text. So a colour reaches it as
     * a word in the query, which is the only thing it can honour. Sources that
     * *do* filter by colour (Bing, Wallhaven) get a real parameter instead, and
     * sources with no colour metadata at all are skipped when a colour is picked
     * — see [providers].
     */
    private fun colorTerm(f: Filters): String = colorName(f)?.let { " $it" } ?: ""

    /**
     * The colour the search is actually about, as a name the sources understand.
     * A wheel pick is exact for Wallhaven (which takes a hex) but has to be
     * described as its nearest name to everything else.
     */
    private fun colorName(f: Filters): String? =
        if (f.colorHex.isNotEmpty()) nearestColorName(f.colorHex)?.key
        else f.color.takeIf { it.isNotEmpty() }

    /** A colour is on — either a named swatch or a wheel pick. */
    private fun hasColour(f: Filters): Boolean = f.color.isNotEmpty() || f.colorHex.isNotEmpty()

    /** The exact hex for the one source that filters by hex. */
    private fun colorHexOrNull(f: Filters): String? =
        parseHex(f.colorHex)?.let { hexString(it) }
            ?: COLOR_OPTIONS.firstOrNull { it.key == f.color }?.wallhaven

    /**
     * Orientation and size are checked against the dimensions the source
     * actually reported. An image whose size is unknown is left out when the
     * filter is on — "Landscape" then means landscape, not "probably".
     */
    private fun matchesAspect(img: SearitchImage, aspect: AspectFilter): Boolean {
        if (aspect == AspectFilter.ANY) return true
        if (img.width <= 0 || img.height <= 0) return false
        val r = img.width.toDouble() / img.height
        return when (aspect) {
            AspectFilter.WIDE -> r >= 1.2
            AspectFilter.TALL -> r <= 1 / 1.2
            else -> r > 1 / 1.2 && r < 1.2
        }
    }

    private fun matchesSize(img: SearitchImage, size: SizeFilter): Boolean {
        if (size == SizeFilter.ANY) return true
        if (img.width <= 0 || img.height <= 0) return false
        val mp = img.width.toDouble() * img.height / 1_000_000
        return when (size) {
            SizeFilter.SMALL -> mp < 1
            SizeFilter.MEDIUM -> mp in 1.0..6.0
            else -> mp >= 6
        }
    }

    private fun matchesType(img: SearitchImage, type: TypeFilter): Boolean = when (type) {
        TypeFilter.ANY -> true
        TypeFilter.TRANSPARENT -> img.filetype == "png" || img.filetype == "svg"
        else -> img.filetype == type.name.lowercase()
    }

    // ------------------------------------------------------- providers ----

    private suspend fun searchOpenverse(q: String, f: Filters, page: Int): List<SearitchImage> {
        val url = URLBuilder().apply {
            takeFrom("https://api.openverse.org/v1/images/")
            parameters.append("q", q + colorTerm(f))
            parameters.append("page", page.toString())
            parameters.append("page_size", PAGE_SIZE.toString())
            parameters.append("mature", if (f.safe) "false" else "true")
            when (f.type) {
                TypeFilter.TRANSPARENT -> parameters.append("extension", "png,svg")
                TypeFilter.ANY -> Unit
                else -> parameters.append("extension", f.type.name.lowercase())
            }
            when (f.aspect) {
                AspectFilter.WIDE -> parameters.append("aspect_ratio", "wide")
                AspectFilter.TALL -> parameters.append("aspect_ratio", "tall")
                AspectFilter.SQUARE -> parameters.append("aspect_ratio", "square")
                AspectFilter.ANY -> Unit
            }
            if (f.size != SizeFilter.ANY) parameters.append("size", f.size.name.lowercase())
            when (f.license) {
                LicenseFilter.COMMERCIAL -> parameters.append("license_type", "commercial")
                LicenseFilter.MODIFICATION -> parameters.append("license_type", "modification")
                LicenseFilter.ANY -> Unit
            }
        }.buildString()

        val res: OvResponse = http.get(url).body()
        return res.results.map { it.toImage() }
    }

    private fun OvImage.toImage(): SearitchImage {
        val fullUrl = url ?: ""
        return SearitchImage(
            id = "openverse:$id",
            provider = "openverse",
            title = title ?: "Untitled",
            // Never use the Openverse thumbnail proxy: it lives on the API
            // host and can hang for tens of seconds, stalling the whole grid.
            thumb = fullUrl.ifEmpty { thumbnail ?: "" },
            full = fullUrl,
            width = width ?: 0,
            height = height ?: 0,
            filetype = filetype ?: fullUrl.substringAfterLast('.', "").lowercase()
                .let { if (it == "jpeg") "jpg" else it }.take(5),
            sourceSite = (source ?: provider ?: "openverse").replace('_', ' '),
            sourceUrl = foreignLandingUrl ?: fullUrl,
            creator = creator,
            license = license?.let { "${it.uppercase()} ${licenseVersion ?: ""}".trim() },
            tags = tags.mapNotNull { it.name.trim().lowercase().takeIf(String::isNotEmpty) }.take(8),
        )
    }

    private val MIME_FOR_TYPE = mapOf(
        TypeFilter.JPG to "image/jpeg", TypeFilter.PNG to "image/png",
        TypeFilter.GIF to "image/gif", TypeFilter.SVG to "image/svg+xml",
        TypeFilter.TRANSPARENT to "image/png",
    )

    private suspend fun searchWikimedia(q: String, f: Filters, page: Int): List<SearitchImage> {
        var search = q
        MIME_FOR_TYPE[f.type]?.let { search += " filemime:$it" }

        val url = URLBuilder().apply {
            takeFrom("https://commons.wikimedia.org/w/api.php")
            parameters.append("action", "query")
            parameters.append("format", "json")
            parameters.append("formatversion", "2")
            parameters.append("origin", "*")
            parameters.append("generator", "search")
            parameters.append("gsrsearch", search)
            parameters.append("gsrnamespace", "6")
            parameters.append("gsrlimit", PAGE_SIZE.toString())
            parameters.append("gsroffset", ((page - 1) * PAGE_SIZE).toString())
            parameters.append("prop", "imageinfo")
            parameters.append("iiprop", "url|size|mime|extmetadata")
            parameters.append("iiurlwidth", "640")
        }.buildString()

        // Do not set browser-forbidden User-Agent headers in common Ktor code.
        val res: WmResponse = http.get(url).body()

        return (res.query?.pages ?: emptyList()).mapNotNull { pg ->
            val info = pg.imageinfo.firstOrNull() ?: return@mapNotNull null
            val mime = info.mime ?: return@mapNotNull null
            if (!mime.startsWith("image/")) return@mapNotNull null
            val img = SearitchImage(
                id = "wikimedia:${pg.pageid}",
                provider = "wikimedia",
                title = pg.title.removePrefix("File:").substringBeforeLast('.'),
                thumb = info.thumburl ?: info.url ?: "",
                preview = info.thumburl?.replace(Regex("/\\d+px-"), "/1280px-"),
                full = info.url ?: "",
                width = info.width ?: 0,
                height = info.height ?: 0,
                filetype = extFromMime(mime),
                sourceSite = "Wikimedia Commons",
                sourceUrl = info.descriptionurl ?: info.url ?: "",
                creator = stripHtml(info.extmetadata?.artist?.value),
                license = info.extmetadata?.licenseShortName?.value,
            )
            // Commons has no native aspect/size params — filter honestly here.
            if (matchesAspect(img, f.aspect) && matchesSize(img, f.size)) img else null
        }
    }

    private suspend fun searchWallhaven(q: String, f: Filters, page: Int): List<SearitchImage> {
        // Wallhaven art is not openly licensed and only serves large JPG/PNG.
        if (f.license != LicenseFilter.ANY) return emptyList()
        if (f.type in listOf(TypeFilter.GIF, TypeFilter.SVG, TypeFilter.TRANSPARENT)) return emptyList()
        if (f.size == SizeFilter.SMALL) return emptyList()

        val url = URLBuilder().apply {
            takeFrom("https://wallhaven.cc/api/v1/search")
            parameters.append("q", q)
            parameters.append("page", page.toString())
            parameters.append("purity", if (f.safe) "100" else "110")
            parameters.append("categories", "111")
            parameters.append("sorting", "relevance")
            colorHexOrNull(f)?.let { parameters.append("colors", it) }
            when (f.aspect) {
                AspectFilter.WIDE -> parameters.append("ratios", "landscape")
                AspectFilter.TALL -> parameters.append("ratios", "portrait")
                AspectFilter.SQUARE -> parameters.append("ratios", "1x1")
                AspectFilter.ANY -> Unit
            }
            if (f.size == SizeFilter.LARGE) parameters.append("atleast", "2560x1440")
        }.buildString()

        val res: WhResponse = http.get(url).body()
        return res.data.mapNotNull { r ->
            val ft = extFromMime(r.fileType)
            if (f.type != TypeFilter.ANY && ft != f.type.name.lowercase()) return@mapNotNull null
            SearitchImage(
                id = "wallhaven:${r.id}",
                provider = "wallhaven",
                // Wallhaven's search payload carries no title *and* no tags, which
                // is where "Untitled Photo" came from: the old label here was
                // "Wallpaper 1964×3500", displayTitle() threw that away as noise,
                // and the caption fell through to Untitled. It is labelled from
                // what the payload does say until hydrate() fetches the wall's own
                // record and its real tags arrive.
                title = wallhavenLabel(r.tags.map { it.name }, r.category, r.dimensionX, r.dimensionY),
                // `small` (300×200) and `lg` (432×243) are fixed landscape
                // CROPS — a 1964×3500 phone wall arrives as a 432×243 slice of
                // its middle. `original` is the only rendition that keeps the
                // real shape, and it is still a small file (213×300 for that
                // wall). Cropping it again in the UI is what produced the
                // zoomed-in fragments.
                thumb = ratioHonest(r.thumbs?.original) ?: r.path ?: "",
                preview = ratioHonest(r.thumbs?.original) ?: r.path,
                full = r.path ?: "",
                width = r.dimensionX ?: 0,
                height = r.dimensionY ?: 0,
                filetype = ft,
                sourceSite = "Wallhaven",
                sourceUrl = r.url ?: r.path ?: "",
                tags = wallhavenTags(r.tags),
            )
        }
    }

    /* ─────────────────────── Wallhaven's missing half ─────────────────────── */

    /**
     * Wallhaven tags every wall by hand — "fox girl", "fox ears", "flute",
     * "blue eyes" — but only serves them on the wall's own record. The search
     * endpoint returns them empty, which left every Wallhaven photo both
     * untitled and unsimilar.
     */
    private fun wallhavenTags(raw: List<OvTag>): List<String> =
        raw.mapNotNull { it.name.trim().lowercase().takeIf(String::isNotEmpty) }
            .distinct()
            .take(8)

    /** Words that describe the shelf a wall sits on, not the wall itself. */
    private val WALLHAVEN_GENERIC = setOf(
        "anime", "art", "artwork", "illustration", "digital art", "anime girls",
        "wallpaper", "fanart", "fan art", "original", "vector", "render",
    )

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)
    }

    /**
     * A human label built from the site's own words. Never "Untitled": if the
     * record has tags the specific ones name it, and if it somehow has none the
     * category and size still say something true.
     */
    private fun wallhavenLabel(
        tags: List<String>,
        category: String?,
        w: Int?,
        h: Int?,
    ): String {
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val specific = clean.filterNot { it in WALLHAVEN_GENERIC }
        val named = (if (specific.isNotEmpty()) specific else clean)
            .distinct()
            .take(3)
            .map { titleCase(it) }
        if (named.isNotEmpty()) return named.joinToString(" · ")
        val cat = category?.trim()?.takeIf { it.isNotEmpty() }?.let { titleCase(it) }
        val size = if ((w ?: 0) > 0 && (h ?: 0) > 0) "$w × $h" else null
        return listOfNotNull(cat, size).joinToString(" · ").ifBlank { "Wallhaven plate" }
    }

    /**
     * Fills in what a search response left out. Today that is Wallhaven's tags
     * and title; the call only happens when a photo is actually opened, so it
     * costs one small JSON request per visit, not one per card on the grid.
     */
    suspend fun hydrate(img: SearitchImage): SearitchImage {
        if (img.provider != "wallhaven" || img.tags.isNotEmpty()) return img
        val raw = img.id.substringAfter(':').trim()
        if (raw.isEmpty() || raw == img.id) return img
        return runCatching {
            val res: WhSingle = http.get("https://wallhaven.cc/api/v1/w/$raw").body()
            val d = res.data ?: return@runCatching img
            val tags = wallhavenTags(d.tags)
            if (tags.isEmpty()) return@runCatching img
            img.copy(
                title = wallhavenLabel(d.tags.map { it.name }, d.category, img.width, img.height),
                tags = tags,
            )
        }.getOrDefault(img)
    }

    // ---------------------------------------------------------- flickr ----

    @Serializable
    private data class FlFeed(val items: List<FlItem> = emptyList())

    @Serializable
    private data class FlItem(
        val title: String = "",
        val link: String = "",
        val media: FlMedia? = null,
        val author: String = "",
    )

    @Serializable
    private data class FlMedia(val m: String = "")

    private suspend fun searchFlickr(q: String, f: Filters, page: Int): List<SearitchImage> {
        // The public feed has no safe-rating parameter or mature metadata.
        // Strict Safe Search excludes it rather than guessing.
        if (f.safe) return emptyList()
        // JPG-only, no paging and no dimensions; also Android-only (no CORS).
        if (page > 1) return emptyList()
        if (f.type != TypeFilter.ANY && f.type != TypeFilter.JPG) return emptyList()
        if (f.license != LicenseFilter.ANY) return emptyList()
        if (f.aspect != AspectFilter.ANY || f.size != SizeFilter.ANY) return emptyList()

        val tags = q.trim().replace(Regex("\\s+"), ",")
        val url = URLBuilder().apply {
            takeFrom("https://api.flickr.com/services/feeds/photos_public.gne")
            parameters.append("tags", tags)
            parameters.append("format", "json")
            parameters.append("nojsoncallback", "1")
        }.buildString()

        // Flickr feeds escape single quotes with \' (invalid JSON) — repair.
        val raw = http.get(url).bodyAsText().replace("\\'", "'")
        val feed = json.decodeFromString<FlFeed>(raw)

        return feed.items.mapNotNull { item ->
            val thumb = item.media?.m?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val photoId = Regex("/(\\d{6,})/?").find(item.link)?.groupValues?.get(1) ?: thumb
            SearitchImage(
                id = "flickr:$photoId",
                provider = "flickr",
                title = item.title.trim().ifEmpty { "Untitled" },
                thumb = thumb,
                full = thumb.replace("_m.", "_b."),
                width = 0,
                height = 0,
                filetype = "jpg",
                sourceSite = "Flickr",
                sourceUrl = item.link.ifEmpty { thumb },
                creator = Regex("\\(\"?([^\")]+)\"?\\)").find(item.author)?.groupValues?.get(1),
            )
        }
    }

    // ------------------------------------------------------- aggregate ----

    /* Circuit breaker (clock-free, portable): a source that times out twice
       in a row is skipped for the next few searches, so a dead upstream can
       never stall the UI again. */
    private class Health(
        var fails: Int = 0,
        var skipLeft: Int = 0,
        /** Moving average of how long this source takes, in ms; 0 = not known yet. */
        var latency: Long = 0,
        var lastResults: Int = 0,
        var lastOk: Boolean = false,
        var answered: Int = 0,
    )

    private val health = mutableMapOf<String, Health>()

    /** One line of what the app currently believes about a source. */
    data class SourceStatus(
        val name: String,
        val latencyMs: Long,
        val lastResults: Int,
        val lastOk: Boolean,
        val skipped: Boolean,
        val failures: Int,
    )

    /**
     * A source that timed out twice in a row is left alone for the next few
     * searches, so a dead upstream can't keep stalling the grid. The counter is
     * decremented per search rather than on a clock, which keeps this identical
     * on every platform.
     */
    private fun shouldSkip(name: String): Boolean {
        val h = health[name] ?: return false
        return if (h.skipLeft > 0) {
            h.skipLeft--
            true
        } else false
    }

    private fun recordResult(name: String, ok: Boolean, elapsedMs: Long = 0, count: Int = 0) {
        val h = health.getOrPut(name) { Health() }
        h.lastOk = ok
        h.lastResults = count
        h.answered++
        if (ok) {
            h.fails = 0
            h.skipLeft = 0
            // Only a real answer teaches the timeout anything; a failure is what
            // the timeout exists to survive, not a measurement of it.
            if (elapsedMs > 0) {
                h.latency = if (h.latency == 0L) elapsedMs else (h.latency * 4 + elapsedMs) / 5
            }
        } else {
            h.fails++
            if (h.fails >= 2) h.skipLeft = 5
        }
    }

    /** What the Settings sheet shows: which sources are fast, dead, or skipped. */
    fun sourceReport(): List<SourceStatus> = ALL_SOURCES.map { (name, _) ->
        val h = health[name]
        SourceStatus(
            name = name,
            latencyMs = h?.latency ?: 0,
            lastResults = h?.lastResults ?: 0,
            lastOk = h?.lastOk ?: false,
            skipped = (h?.skipLeft ?: 0) > 0,
            failures = h?.fails ?: 0,
        )
    }

    /** Human name for a source key, for the diagnostics panel. */
    fun sourceTitle(name: String): String = ALL_SOURCES.firstOrNull { it.first == name }?.second ?: name

    /**
     * How long a source is given.
     *
     * A fixed budget is either too short for a slow museum API or too long for a
     * dead CDN. Each source gets a floor from what it normally costs, and the
     * budget then follows its own measured latency with headroom for a slow
     * network — so a source that has been answering in 400 ms is not waited on
     * for six seconds, and one that needs four seconds is not cut off at three.
     */
    private fun budgetFor(name: String): Long {
        val floor = when (name) {
            "openverse" -> 4_000
            "bing" -> 5_000
            // Yandex renders a full page of results server-side; it is a big answer.
            "yandex" -> 6_000
            "loc" -> 5_000
            else -> 3_500
        }
        val measured = health[name]?.latency ?: 0L
        if (measured <= 0L) return floor
        // 2.5× the usual time, never below the floor, never above 8 s.
        return (measured * 5 / 2).coerceIn(floor, 8_000L)
    }

    /** Sources whose API takes a colour and really filters by it. */
    private val COLOUR_AWARE = setOf(
        ProviderFilter.BING, ProviderFilter.WALLHAVEN, ProviderFilter.OPENVERSE,
    )

    /**
     * Sources that publish a licence we can actually test a licence filter
     * against. Wallhaven, Flickr and Bing index other people's pages, so they
     * are dropped rather than passed off as "commercial use" when one is asked
     * for.
     */
    private val LICENCE_AWARE = setOf(
        ProviderFilter.OPENVERSE, ProviderFilter.WIKIMEDIA, ProviderFilter.INATURALIST,
        ProviderFilter.NASA, ProviderFilter.MET, ProviderFilter.AIC,
        ProviderFilter.CLEVELAND, ProviderFilter.LOC, ProviderFilter.EUROPEANA,
        ProviderFilter.VAM,
    )

    /** Every source the app knows, with the name a person would use for it. */
    private val ALL_SOURCES = listOf(
        "bing" to "Bing",
        "yandex" to "Yandex",
        "openverse" to "Openverse",
        "wikimedia" to "Wikimedia Commons",
        "wallhaven" to "Wallhaven",
        "flickr" to "Flickr",
        "inaturalist" to "iNaturalist",
        "nasa" to "NASA",
        "met" to "The Met",
        "aic" to "Art Institute of Chicago",
        "cleveland" to "Cleveland Museum",
        "loc" to "Library of Congress",
        "europeana" to "Europeana",
        "vam" to "V&A Museum",
    )

    /**
     * The one place that decides which sources a search touches.
     *
     * A filter is never applied by pretending: a source that cannot honour it is
     * left out, so what comes back is always what was asked for.
     */
    private fun providers(
        q: String,
        f: Filters,
        page: Int,
    ): List<Pair<String, suspend () -> List<SearitchImage>>> {
        val all: List<Triple<ProviderFilter, String, suspend () -> List<SearitchImage>>> = listOf(
            Triple(ProviderFilter.BING, "bing") { searchBing(q, f, page) },
            Triple(ProviderFilter.YANDEX, "yandex") { searchYandex(q, f, page) },
            Triple(ProviderFilter.OPENVERSE, "openverse") { searchOpenverse(q, f, page) },
            Triple(ProviderFilter.WIKIMEDIA, "wikimedia") { searchWikimedia(q, f, page) },
            Triple(ProviderFilter.WALLHAVEN, "wallhaven") { searchWallhaven(q, f, page) },
            Triple(ProviderFilter.FLICKR, "flickr") { searchFlickr(q, f, page) },
            Triple(ProviderFilter.INATURALIST, "inaturalist") { searchINaturalist(q) },
            Triple(ProviderFilter.NASA, "nasa") { searchNasa(q) },
            Triple(ProviderFilter.MET, "met") { searchMet(q) },
            Triple(ProviderFilter.AIC, "aic") { searchAic(q, f, page) },
            Triple(ProviderFilter.CLEVELAND, "cleveland") { searchCleveland(q, f, page) },
            Triple(ProviderFilter.LOC, "loc") { searchLoc(q, f, page) },
            Triple(ProviderFilter.EUROPEANA, "europeana") { searchEuropeana(q) },
            Triple(ProviderFilter.VAM, "vam") { searchVam(q) },
        )
        return all
            .filter { (p, _, _) ->
                (f.provider == ProviderFilter.ALL || f.provider == p) &&
                    (!hasColour(f) || p in COLOUR_AWARE) &&
                    (f.license == LicenseFilter.ANY || p in LICENCE_AWARE)
            }
            .map { (_, name, run) -> name to run }
    }

    /**
     * What the sources are actually asked for.
     *
     * People type quotes, double spaces and trailing commas; every source
     * treats those differently, and half of them answer "nothing" to a stray
     * quote. It is tidied once, here, so a query behaves the same everywhere.
     */
    fun cleanQuery(raw: String): String = raw
        .replace('\u00A0', ' ')
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * A search with no query gate.
     *
     * Only for the similar-photos pipeline: there the words are a *subject*
     * taken from the picture itself, and a neighbour that doesn't repeat the
     * word in its title is still a neighbour. Every user-facing search goes
     * through [search] or [searchStream], gate and all.
     */
    suspend fun rawSearch(q: String, filters: Filters, page: Int): List<SearitchImage> =
        coroutineScope {
            val plan = Query.read(q)
            providers(plan.text, filters, page)
                .filterNot { shouldSkip(it.first) }
                .map { (name, run) ->
                    async {
                        val started = currentTimeMillis()
                        val r = runCatching { withTimeout(budgetFor(name)) { run() } }
                        val raw = r.getOrDefault(emptyList())
                        recordResult(name, r.isSuccess, currentTimeMillis() - started, raw.size)
                        raw.filter { passesFilters(it, filters) }
                    }
                }
                .awaitAll()
                .let { lists -> Ranker.dedupe(Ranker.mix(lists, plan), plan) }
        }

    suspend fun search(q: String, filters: Filters, page: Int): List<SearitchImage> =
        coroutineScope {
            val plan = Query.read(q)
            val lists = providers(plan.text, filters, page).filterNot { shouldSkip(it.first) }.map { (name, run) ->
                async {
                    val started = currentTimeMillis()
                    val r = runCatching { withTimeout(budgetFor(name)) { run() } }
                    val raw = r.getOrDefault(emptyList())
                    recordResult(name, r.isSuccess, currentTimeMillis() - started, raw.size)
                    // Only results that answer the query are allowed through.
                    gate(plan, raw)
                }
            }.awaitAll()

            // Rank inside each source, then interleave sources under a cap, then
            // collapse near-copies onto their best copy. Filters decide what is
            // allowed; the ranking only decides the order of what is left.
            Ranker.dedupe(
                Ranker.mix(lists, plan),
                plan,
            ).filter { passesFilters(it, filters) }
        }


    // ═══════════════ direct source providers (billion-image catalogue) ═══════
    // Openverse indexes 52 sites holding ~915 million images. Its own endpoint
    // is slow and its thumbnail proxy hangs, so we query the underlying APIs
    // DIRECTLY, in parallel. Every provider below is keyless.

    private fun extOfUrl(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
        return if (ext.equals("jpeg", true)) "jpg" else ext.lowercase()
    }

    /**
     * Fetches JSON text, or null when the source answered "no such thing" (4xx).
     *
     * Transport failures, timeouts and 5xx are raised on purpose: they mean the
     * source is unreachable or broken, and the health breaker needs to see that
     * rather than silently return an empty page.
     */
    private suspend fun getJson(url: String, ms: Long = 6000): String? {
        // Do not set User-Agent here: browsers forbid that header for Wasm Fetch.
        val res = http.get(url)
        return when {
            res.status.value in 200..299 -> res.bodyAsText()
            res.status.value >= 500 -> error("HTTP ${res.status.value} from ${url.substringBefore('?')}")
            else -> null
        }
    }

    /** Like [getJson], but a transport or HTTP failure is raised instead of hidden. */
    private suspend fun getTextOrThrow(url: String): String {
        val res = http.get(url)
        if (res.status.value !in 200..299) error("HTTP ${res.status.value} for $url")
        return res.bodyAsText()
    }

    private inline fun <reified T> parseJson(text: String?): T? {
        if (text == null) return null
        return try { json.decodeFromString<T>(text) } catch (e: Throwable) { null }
    }

    @Serializable
    private data class InatResponse(val results: List<InatObs> = emptyList())

    @Serializable
    private data class InatObs(
        val id: Long = 0,
        val taxon: InatTaxon? = null,
        val photos: List<InatPhoto> = emptyList(),
    )

    @Serializable
    private data class InatTaxon(
        val name: String? = null,
        val preferred_common_name: String? = null,
    )

    @Serializable
    private data class InatPhoto(
        val url: String = "",
        val attribution: String? = null,
        // Present on observation photos; absent on some older records, which is
        // why it is optional rather than assumed.
        @SerialName("original_dimensions") val dims: InatDims? = null,
    )

    @Serializable
    private data class InatDims(val width: Int = 0, val height: Int = 0)

    suspend fun searchINaturalist(q: String): List<SearitchImage> {
        val text = getJson(
            "https://api.inaturalist.org/v1/observations?taxa=" +
                q.encodeURLParameter() +
                "&photos=true&photo_license=cc0,cc-by,cc-by-sa&per_page=24&order_by=votes",
        )
        val res = parseJson<InatResponse>(text) ?: return emptyList()
        return res.results.mapNotNull { o ->
            val photo = o.photos.firstOrNull() ?: return@mapNotNull null
            val name = o.taxon?.preferred_common_name ?: o.taxon?.name ?: "Untitled"
            SearitchImage(
                id = "inat:${o.id}",
                provider = "inaturalist",
                title = name,
                // `/square.` is a 75px square crop; `/small.` is the same photo
                // at up to 500px with its real shape.
                thumb = photo.url.replace("/square.", "/small."),
                thumbFallback = photo.url.replace("/square.", "/small."),
                preview = photo.url.replace("/square.", "/large."),
                full = photo.url.replace("/square.", "/original."),
                width = photo.dims?.width ?: 0,
                height = photo.dims?.height ?: 0,
                filetype = "jpg",
                sourceSite = "iNaturalist",
                sourceUrl = "https://www.inaturalist.org/observations/${o.id}",
                creator = photo.attribution,
                license = "CC",
                tags = listOf(name.lowercase(), "nature"),
            )
        }
    }

    @Serializable
    private data class EuropeanaResponse(val items: List<EuropeanaItem> = emptyList())

    @Serializable
    private data class EuropeanaItem(
        val id: String? = null,
        val guid: String? = null,
        val title: List<String> = emptyList(),
        @SerialName("edmIsShownBy") val shownBy: List<String> = emptyList(),
        @SerialName("edmPreview") val preview: List<String> = emptyList(),
        @SerialName("dcCreator") val creator: List<String> = emptyList(),
        val rights: List<String> = emptyList(),
    )

    suspend fun searchEuropeana(q: String): List<SearitchImage> {
        // Europeana's documented demo key is public and rate-limited; deployments
        // can replace it with their own key at the server/proxy layer.
        val key = "api2demo"
        val url = "https://api.europeana.eu/record/v2/search.json?wskey=$key" +
            "&query=${q.encodeURLParameter()}&rows=24&media=true&reusability=open&profile=portal"
        val res = parseJson<EuropeanaResponse>(getJson(url)) ?: return emptyList()
        return res.items.mapNotNull { item ->
            val thumb = item.preview.firstOrNull() ?: item.shownBy.firstOrNull() ?: return@mapNotNull null
            val full = item.shownBy.firstOrNull() ?: thumb
            SearitchImage(
                id = "europeana:${item.id ?: item.guid ?: full}",
                provider = "europeana",
                title = item.title.firstOrNull() ?: "Untitled",
                thumb = thumb,
                full = full,
                width = 0,
                height = 0,
                filetype = extFromMime(null).ifEmpty { full.substringBefore('?').substringAfterLast('.', "jpg") },
                sourceSite = "Europeana",
                sourceUrl = item.guid ?: "https://www.europeana.eu/en/item/${item.id ?: ""}",
                creator = item.creator.firstOrNull(),
                license = item.rights.firstOrNull(),
                tags = listOf("heritage", "culture"),
            )
        }
    }

    @Serializable
    private data class NasaResponse(val collection: NasaCollection? = null)

    @Serializable
    private data class NasaCollection(val items: List<NasaItem> = emptyList())

    @Serializable
    private data class NasaItem(
        val data: List<NasaData> = emptyList(),
        val links: List<NasaLink> = emptyList(),
    )

    @Serializable
    private data class NasaData(
        val nasa_id: String? = null,
        val title: String? = null,
        val center: String? = null,
        val keywords: List<String> = emptyList(),
    )

    @Serializable
    private data class NasaLink(val href: String? = null, val rel: String? = null)

    suspend fun searchNasa(q: String): List<SearitchImage> {
        val text = getJson(
            "https://images-api.nasa.gov/search?q=" +
                q.encodeURLParameter() + "&media_type=image&page_size=24",
        )
        val res = parseJson<NasaResponse>(text) ?: return emptyList()
        return res.collection?.items.orEmpty().mapNotNull { item ->
            val d = item.data.firstOrNull() ?: return@mapNotNull null
            val img = item.links.firstOrNull { it.rel == "preview" }?.href
                ?: item.links.firstOrNull()?.href
                ?: return@mapNotNull null
            SearitchImage(
                id = "nasa:${d.nasa_id ?: img}",
                provider = "nasa",
                title = d.title ?: "Untitled",
                thumb = img, full = img, width = 0, height = 0, filetype = "jpg",
                sourceSite = "NASA",
                sourceUrl = "https://images.nasa.gov/details/${d.nasa_id ?: ""}",
                creator = d.center,
                license = "Public Domain",
                tags = d.keywords.take(6),
            )
        }
    }

    @Serializable
    private data class MetSearch(val objectIDs: List<Long> = emptyList())

    @Serializable
    private data class MetObject(
        val objectID: Long = 0,
        val title: String? = null,
        val artistDisplayName: String? = null,
        val primaryImage: String? = null,
        val primaryImageSmall: String? = null,
        val isPublicDomain: Boolean? = null,
        val objectURL: String? = null,
    )

    suspend fun searchMet(q: String): List<SearitchImage> {
        // Search lives on v1.1 now; object records are still served by v1.
        val ids = parseJson<MetSearch>(
            getJson(
                "https://collectionapi.metmuseum.org/public/collection/v1.1/search" +
                    "?hasImages=true&q=" + q.encodeURLParameter(),
            ),
        )?.objectIDs.orEmpty().take(10)
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id ->
            val o = parseJson<MetObject>(
                getJson("https://collectionapi.metmuseum.org/public/collection/v1/objects/$id"),
            ) ?: return@mapNotNull null
            val full = o.primaryImage ?: return@mapNotNull null
            SearitchImage(
                id = "met:${o.objectID}",
                provider = "met",
                title = o.title ?: "Untitled",
                thumb = o.primaryImageSmall ?: full, full = full,
                width = 0, height = 0, filetype = "jpg",
                sourceSite = "The Met",
                sourceUrl = o.objectURL ?: "https://www.metmuseum.org/art/collection",
                creator = o.artistDisplayName,
                license = if (o.isPublicDomain == true) "Public Domain" else null,
                tags = emptyList(),
            )
        }
    }

    @Serializable
    private data class VamResponse(val records: List<VamRecord> = emptyList())

    @Serializable
    private data class VamRecord(
        val systemNumber: String? = null,
        val accessionNumber: String? = null,
        val objectType: String? = null,
        @SerialName("_primaryTitle") val title: String? = null,
        @SerialName("_primaryMaker") val maker: VamMaker? = null,
        @SerialName("_images") val images: VamImages? = null,
    )

    @Serializable
    private data class VamMaker(val name: String? = null)

    @Serializable
    private data class VamImages(
        @SerialName("_primary_thumbnail") val thumbnail: String? = null,
        @SerialName("_primary") val primary: String? = null,
    )

    suspend fun searchVam(q: String): List<SearitchImage> {
        val res = parseJson<VamResponse>(
            getJson(
                "https://api.vam.ac.uk/v2/objects/search?q=" +
                    q.encodeURLParameter() + "&page_size=24&images_exist=1",
            ),
        ) ?: return emptyList()
        return res.records.mapNotNull { r ->
            val thumb = r.images?.thumbnail ?: return@mapNotNull null
            SearitchImage(
                id = "vam:${r.systemNumber ?: r.accessionNumber ?: thumb}",
                provider = "vam",
                title = r.title ?: "Untitled",
                thumb = thumb.replace("/!100,100/", "/!400,400/"),
                thumbFallback = thumb,
                full = r.images?.primary ?: thumb,
                width = 0, height = 0, filetype = "jpg",
                sourceSite = "V&A Museum",
                sourceUrl = "https://collections.vam.ac.uk/item/${r.systemNumber ?: ""}",
                creator = r.maker?.name,
                license = "CC0",
                tags = listOfNotNull(r.objectType?.lowercase()),
            )
        }
    }


    // ══════════════════════ Bing — the open web ═════════════════════════════
    //
    // Bing retired its keyed Image Search API, so there is no keyless JSON
    // endpoint left. What does still answer without a key is the same fragment
    // the Bing images page loads by itself (`/images/async`, `mmasync=1`): an
    // HTML list where every card carries its media URL, its thumbnail, its
    // source page and its "m" JSON blob.
    //
    // That endpoint supports real server-side filtering — `adlt=` for safe
    // search, and `qft=filterui:color2-FGcls_*`, `filterui:aspect-*`,
    // `filterui:imagesize-*` — so colour, orientation and size are answered by
    // the index itself instead of being guessed at afterwards.
    //
    // It sends no CORS headers, so like Wallhaven and Flickr this source answers
    // in the Android build and stays quiet in the browser one.

    /** Bing's own colour names, one for one with the twelve swatches in Filters. */
    private val BING_COLOR = mapOf(
        "red" to "RED", "orange" to "ORANGE", "yellow" to "YELLOW", "green" to "GREEN",
        "teal" to "TEAL", "blue" to "BLUE", "purple" to "PURPLE", "pink" to "PINK",
        "brown" to "BROWN", "black" to "BLACK", "gray" to "GRAY", "white" to "WHITE",
    )

    private fun percentDecode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(if (c == '+') ' ' else c)
            i++
        }
        return out.toString()
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    private data class WebHit(
        val media: String,
        val thumb: String?,
        val title: String,
        val page: String?,
        val width: Int,
        val height: Int,
    )

    /** Tolerant: try the card's `m` JSON first, then the media URLs in the links. */
    private fun parseBingCards(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        val cards = Regex("m=\"(\\{[^\"]{20,}?\\})\"").findAll(html).toList()
        for ((i, card) in cards.withIndex()) {
            val body = unescapeHtml(card.groupValues[1])
            val media = Regex("\"murl\":\"(.*?)\"").find(body)?.groupValues?.get(1) ?: continue
            if (!media.startsWith("http")) continue
            // The card body that follows carries the thumbnail and the printed
            // "1920 x 1080" size; it ends where the next card begins.
            val end = cards.getOrNull(i + 1)?.range?.first ?: html.length
            val region = html.substring(card.range.last + 1, end)
            // The visible thumbnail is a fixed w×h CROP (that is what the `w=`,
            // `h=` and `c=7` in its query mean). The card's own link carries
            // `cdnurl=` — the same image through Bing's cache at `pid=ImgRaw`,
            // which keeps its real shape. Prefer that; fall back to the crop.
            val cdn = Regex("cdnurl=([^\"&]+)").find(region)
                ?.groupValues?.get(1)
                ?.let { percentDecode(it).replace("&amp;", "&") }
                ?.takeIf { it.startsWith("http") }
            // Raw HTML, so the query string is still entity-escaped — leave
            // `&amp;` in place and the load fails.
            val crop = Regex("(https://th\\.bing\\.com/th/id/[^\"'\\s]+)").find(region)
                ?.groupValues?.get(1)
                ?.replace("&amp;", "&")
            val thumb = cdn ?: crop
            val dims = Regex("(\\d{2,5})\\s*[x\u00d7]\\s*(\\d{2,5})").find(region)
            out += WebHit(
                media = media,
                thumb = thumb,
                title = Regex("\"t\":\"(.*?)\"").find(body)?.groupValues?.get(1) ?: "",
                page = Regex("\"purl\":\"(.*?)\"").find(body)?.groupValues?.get(1),
                width = dims?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                height = dims?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
        }
        if (out.isNotEmpty()) return out

        // Fallback shape: no `m` blobs, but every detail link still carries
        // mediaurl=…&expw=…&exph=…
        Regex("mediaurl=([^\"&]+)").findAll(html).forEach { m ->
            val media = percentDecode(m.groupValues[1]).replace("&amp;", "&")
            if (!media.startsWith("http")) return@forEach
            val window = html.substring(m.range.last, minOf(html.length, m.range.last + 400))
            val w = Regex("expw=(\\d+)").find(window)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("exph=(\\d+)").find(window)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out += WebHit(media, null, "", null, w, h)
        }
        return out
    }

    /* ══════════════════════════ Yandex Images ══════════════════════════════

     Yandex is the second general-web engine, and the one this app was missing:
     Bing answers `/images/async` with a *trending* set for some single words —
     asking it for "android" returns "Another Round" film posters — while Yandex
     answers the query it was given, on the server, in plain HTML. It reports
     each picture's real title and dimensions, so cards from here are honest and
     the size and orientation filters work on them.

     Google was tried too and is not here: its image results are base64 data URIs
     assembled by its own JavaScript, and the old `asearch=ichunk` endpoint that
     used to hand back plain JSON now 404s. DuckDuckGo answers a datacentre with a
     `botnet_challenge`, and Baidu returns `Forbid spider access` — none of the
     three can be made to work from a plain HTTP client, so none is claimed. */

    /**
     * One result = one anchor that carries the picture's original URL, wrapping
     * the thumbnail `<img>`. The three pieces are pulled separately because the
     * attributes are not in a fixed order — the same reason this is safer than
     * one long expression.
     */
    // Written as ordinary strings rather than raw ones: these patterns are full
    // of quotes, and a raw string that ends in a quote is at best a puzzle.
    private val YANDEX_LINK = Regex(
        "<a[^>]*?img_url=([^\"&>]+)[^>]*?>(.*?)</a>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val IMG_SRC = Regex("<img[^>]*?\\ssrc=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val IMG_ALT = Regex("<img[^>]*?\\salt=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /** The "1080×720" caption Yandex prints beside a result, when it is there. */
    private val DIMENSIONS = Regex("(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})")

    private suspend fun searchYandex(q: String, f: Filters, page: Int): List<SearitchImage> {
        if (f.license != LicenseFilter.ANY) return emptyList()
        if (page > 6) return emptyList()

        val url = "https://yandex.com/images/search?text=" + q.encodeURLParameter() +
            (if (page > 1) "&p=" + (page - 1) else "")

        val html = getTextOrThrow(url)
        val out = LinkedHashMap<String, SearitchImage>()
        for (m in YANDEX_LINK.findAll(html)) {
            val original = decodeUrl(m.groupValues[1])
            if (original.isBlank()) continue
            val ext = extOfUrl(original)
            if (ext !in BITMAP_TYPES || ext == "svg") continue
            val inside = m.groupValues[2]
            // A Yandex thumbnail is one of its own cached renditions; the original
            // is the picture's real home, which is also what Download fetches.
            val thumb = IMG_SRC.find(inside)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?.takeIf { it.startsWith("http") } ?: continue
            val alt = IMG_ALT.find(inside)?.groupValues?.get(1)
                .orEmpty()
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
                .trim()
            // Dimensions sit in the same card, just after the link — so the
            // search stops at the next card rather than picking up its numbers.
            val after = html.substring(m.range.last + 1)
            val nextCard = after.indexOf("<a ")
            val tail = after.substring(0, if (nextCard in 0 until 400) nextCard else minOf(after.length, 400))
            val dims = DIMENSIONS.find(tail)
            val w = dims?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = dims?.groupValues?.get(2)?.toIntOrNull() ?: 0

            val host = original.substringAfter("://").substringBefore('/').removePrefix("www.")
            val img = SearitchImage(
                id = "yandex:" + original.hashCode().toUInt().toString(16),
                provider = "yandex",
                // Yandex truncates long alt text mid-word with an ellipsis; the
                // caption is still the page's own words, so it is used as is.
                title = alt.ifBlank { host }.take(140),
                thumb = thumb,
                preview = thumb,
                full = original,
                width = w,
                height = h,
                filetype = ext,
                sourceSite = host,
                sourceUrl = original,
                creator = null,
                license = null,
                tags = emptyList(),
            )
            out[original] = img
        }
        return out.values.toList()
    }

    /** Yandex percent-encodes the origin URL it echoes back in the link. */
    private fun decodeUrl(raw: String): String {
        var s = raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        if (!s.contains('%')) return s
        return try {
            buildString {
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '%' && i + 2 < s.length) {
                        val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                        if (code != null) {
                            append(code.toChar())
                            i += 3
                            continue
                        }
                    }
                    append(c)
                    i++
                }
            }
        } catch (e: Throwable) {
            s
        }
    }

    private suspend fun searchBing(q: String, f: Filters, page: Int): List<SearitchImage> {
        val count = 35
        val first = (page - 1) * count + 1
        val qft = buildString {
            colorName(f)?.let { BING_COLOR[it] }?.let { append("+filterui:color2-FGcls_").append(it) }
            when (f.aspect) {
                AspectFilter.WIDE -> append("+filterui:aspect-wide")
                AspectFilter.TALL -> append("+filterui:aspect-tall")
                AspectFilter.SQUARE -> append("+filterui:aspect-square")
                AspectFilter.ANY -> Unit
            }
            when (f.size) {
                SizeFilter.SMALL -> append("+filterui:imagesize-small")
                SizeFilter.MEDIUM -> append("+filterui:imagesize-medium")
                SizeFilter.LARGE -> append("+filterui:imagesize-large")
                SizeFilter.ANY -> Unit
            }
        }
        val url = "https://www.bing.com/images/async" +
            "?q=" + q.encodeURLParameter() +
            "&count=$count&first=$first&mmasync=1" +
            "&adlt=" + (if (f.safe) "strict" else "off") +
            (if (qft.isEmpty()) "" else "&qft=$qft")

        // Raised on purpose: if Bing starts refusing the request, the health
        // breaker should learn that instead of re-asking on every search.
        val html = getTextOrThrow(url)
        return parseBingCards(html).mapNotNull { hit ->
            val ext = extOfUrl(hit.media)
            // Bing indexes pages, not files: skip anything that isn't a usable
            // bitmap, and honour an explicit file-type filter for real.
            if (ext !in BITMAP_TYPES) return@mapNotNull null
            val typeOk = when (f.type) {
                TypeFilter.ANY -> true
                TypeFilter.TRANSPARENT -> ext == "png" || ext == "svg"
                else -> ext == f.type.name.lowercase()
            }
            if (!typeOk) return@mapNotNull null
            val host = hit.page?.let { p ->
                p.substringAfter("://", p).substringBefore('/').removePrefix("www.")
            }
            SearitchImage(
                id = "bing:" + hit.media.hashCode(),
                provider = "bing",
                title = hit.title.ifBlank { host ?: "Web image" }.take(140),
                thumb = hit.thumb ?: hit.media,
                // the cached raw rendition paints the hero first; the page's own
                // file is only fetched when it is genuinely bigger
                preview = hit.thumb ?: hit.media,
                full = hit.media,
                width = hit.width,
                height = hit.height,
                filetype = ext,
                sourceSite = "Bing",
                sourceUrl = hit.page ?: hit.media,
                creator = host,
                license = null,
                // The page host is a real hint for similarity: pictures of the
                // same subject cluster on the same sites.
                tags = listOfNotNull(host),
            )
        }
    }

    private val BITMAP_TYPES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg")

    // ══════════════════ art museums on open-access APIs ═════════════════════

    @Serializable
    private data class AicResponse(val data: List<AicArt> = emptyList())

    @Serializable
    private data class AicArt(
        val id: Long = 0,
        val title: String? = null,
        @SerialName("image_id") val imageId: String? = null,
        @SerialName("artist_title") val artistTitle: String? = null,
        @SerialName("date_display") val dateDisplay: String? = null,
        @SerialName("is_public_domain") val publicDomain: Boolean? = null,
        val thumbnail: AicThumb? = null,
    )

    @Serializable
    private data class AicThumb(val width: Int = 0, val height: Int = 0)

    /** IIIF rendition at a given width — the museum's own image server. */
    private fun aicUrl(imageId: String, width: Int): String =
        "https://www.artic.edu/iiif/2/$imageId/full/${width.coerceAtLeast(64)},/0/default.jpg"

    private suspend fun searchAic(q: String, f: Filters, page: Int): List<SearitchImage> {
        val url = "https://api.artic.edu/api/v1/artworks/search" +
            "?q=" + q.encodeURLParameter() +
            "&limit=20&page=$page" +
            // Only works that are genuinely free of restrictions come back.
            "&query[term][is_public_domain]=true" +
            "&fields=id,title,image_id,artist_title,date_display,is_public_domain,thumbnail"
        val res = parseJson<AicResponse>(getJson(url)) ?: return emptyList()
        return res.data.mapNotNull { a ->
            val imageId = a.imageId ?: return@mapNotNull null
            val w = a.thumbnail?.width ?: 0
            val h = a.thumbnail?.height ?: 0
            if (w <= 0 || h <= 0) return@mapNotNull null
            SearitchImage(
                id = "aic:${a.id}",
                provider = "aic",
                title = a.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                thumb = aicUrl(imageId, minOf(400, w)),
                preview = aicUrl(imageId, minOf(1000, w)),
                full = aicUrl(imageId, minOf(1686, w)),
                width = w,
                height = h,
                filetype = "jpg",
                sourceSite = "Art Institute of Chicago",
                sourceUrl = "https://www.artic.edu/artworks/${a.id}",
                creator = a.artistTitle,
                license = "Public domain",
                tags = listOfNotNull(a.artistTitle?.lowercase(), a.dateDisplay),
            )
        }
    }

    @Serializable
    private data class CmaResponse(val data: List<CmaArt> = emptyList())

    @Serializable
    private data class CmaArt(
        val id: Long = 0,
        val title: String? = null,
        val type: String? = null,
        @SerialName("creation_date") val creationDate: String? = null,
        @SerialName("share_license_status") val license: String? = null,
        @SerialName("accession_number") val accessionNumber: String? = null,
        val images: CmaImages? = null,
        val creators: List<CmaCreator> = emptyList(),
    )

    @Serializable
    private data class CmaImages(val web: CmaSize? = null, val print: CmaSize? = null)

    @Serializable
    private data class CmaSize(
        val url: String? = null,
        val width: String? = null,
        val height: String? = null,
    )

    @Serializable
    private data class CmaCreator(val description: String? = null, val role: String? = null)

    private suspend fun searchCleveland(q: String, f: Filters, page: Int): List<SearitchImage> {
        val skip = (page - 1) * 20
        val url = "https://openaccess-api.clevelandart.org/api/artworks/" +
            "?q=" + q.encodeURLParameter() +
            "&has_image=1&limit=20&skip=$skip" +
            "&fields=id,title,type,creation_date,share_license_status,accession_number,images,creators"
        val res = parseJson<CmaResponse>(getJson(url)) ?: return emptyList()
        return res.data.mapNotNull { a ->
            val print = a.images?.print ?: a.images?.web ?: return@mapNotNull null
            val full = print.url ?: return@mapNotNull null
            SearitchImage(
                id = "cleveland:${a.id}",
                provider = "cleveland",
                title = a.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                thumb = a.images?.web?.url ?: full,
                preview = full,
                full = full,
                width = print.width?.toIntOrNull() ?: 0,
                height = print.height?.toIntOrNull() ?: 0,
                filetype = extOfUrl(full).ifEmpty { "jpg" },
                sourceSite = "Cleveland Museum of Art",
                sourceUrl = "https://www.clevelandart.org/art/${a.accessionNumber ?: a.id}",
                creator = a.creators.firstOrNull()?.description,
                license = if (a.license == "CC0") "CC0" else a.license,
                tags = listOfNotNull(a.type?.lowercase(), a.creationDate),
            )
        }
    }

    @Serializable
    private data class LocResponse(val results: List<LocItem> = emptyList())

    @Serializable
    private data class LocItem(
        val id: String? = null,
        val title: String? = null,
        @SerialName("image_url") val imageUrl: List<String> = emptyList(),
        @SerialName("service_low") val serviceLow: String? = null,
        @SerialName("service_medium") val serviceMedium: String? = null,
        @SerialName("thumb_gallery") val thumbGallery: String? = null,
        @SerialName("rights_advisory") val rights: String? = null,
        val contributor: List<String> = emptyList(),
        val subject: List<String> = emptyList(),
        val item: LocItemDetail? = null,
    )

    @Serializable
    private data class LocItemDetail(
        val title: String? = null,
        val created: String? = null,
    )

    /** The LoC sizes are listed inside the URL fragment: `…07149v.jpg#h=873&w=1024`. */
    private fun locWidth(url: String): Int =
        Regex("[#&]w=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private suspend fun searchLoc(q: String, f: Filters, page: Int): List<SearitchImage> {
        val url = "https://www.loc.gov/photos/" +
            "?q=" + q.encodeURLParameter() +
            "&fo=json&c=20&sp=$page&at=results"
        val res = parseJson<LocResponse>(getJson(url)) ?: return emptyList()
        return res.results.mapNotNull { r ->
            val best = r.imageUrl.maxByOrNull { locWidth(it) } ?: return@mapNotNull null
            val full = best.substringBefore('#')
            val width = locWidth(best)
            val height = Regex("[#&]h=(\\d+)").find(best)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val itemPage = r.id ?: full
            SearitchImage(
                id = "loc:" + (r.id ?: full).hashCode(),
                provider = "loc",
                title = (r.title ?: r.item?.title)?.takeIf { it.isNotBlank() } ?: "Untitled",
                thumb = r.thumbGallery ?: r.serviceLow ?: full,
                preview = r.serviceMedium ?: full,
                full = full,
                width = width,
                height = height,
                filetype = extOfUrl(full).ifEmpty { "jpg" },
                sourceSite = "Library of Congress",
                sourceUrl = itemPage,
                creator = r.contributor.firstOrNull(),
                license = r.rights,
                tags = r.subject.take(6).map { it.substringBefore('-').lowercase() },
            )
        }
    }

    // --------------------------------------------------------- smart safety & anti-ai ----

    private val aiExact = setOf(
        "ai", "ai art", "ai_art", "ai-art", "ai generated", "ai_generated", "ai-generated",
        "midjourney", "stable diffusion", "stablediffusion", "dall-e", "dalle", "novelai",
        "flux", "civitai", "genai", "generative ai", "sdxl", "comfyui", "nightcafe",
        "leonardo.ai", "adobe firefly", "bing image creator", "created with ai", "made with ai"
    )
    private val aiRegex = Regex("(?i)\\b(ai[- ]generated|ai[- ]art|midjourney|stable[- ]diffusion|stablediffusion|dall[- ]?e|novelai|flux\\.1|genai|civitai|leonardo\\.?ai|adobe[- ]firefly|sdxl|comfyui|nightcafe|artbreeder|generative[- ]ai|deepai|bing[- ]image[- ]creator|created[- ](with|by)[- ]ai)\\b")

    private fun isAiGenerated(img: SearitchImage): Boolean {
        if (img.tags.any { t -> t.lowercase().trim() in aiExact || aiRegex.containsMatchIn(t) }) return true
        if (aiRegex.containsMatchIn(img.title)) return true
        if (img.creator != null && aiRegex.containsMatchIn(img.creator)) return true
        return false
    }

    private val nsfwExact = setOf("nsfw", "nude", "nudity", "erotic", "porn", "hentai", "ecchi", "lewd", "uncensored", "topless", "naked", "xxx")
    private val nsfwRegex = Regex("(?i)\\b(nsfw|nude|nudity|erotic|porn|pornographic|xxx|hentai|ecchi|lewd|uncensored|topless|naked|nsfw[- ]art|sex|gore|gory)\\b")

    private fun isNsfw(img: SearitchImage): Boolean {
        if (img.tags.any { t -> t.lowercase().trim() in nsfwExact || nsfwRegex.containsMatchIn(t) }) return true
        if (nsfwRegex.containsMatchIn(img.title)) return true
        return false
    }

    // --------------------------------------------------------- streaming ----

    /** First paint is gathered for this long so it can mix sources (round-robin). */
    private const val GATHER_MS = 320L

    /* ─────────────────── does this result answer the query? ─────────────────── */

    /**
     * A search box that returns a beach for "android" is worse than one that
     * returns less, and that is what was happening: Bing's `/images/async`
     * answers some single words with a *trending* set that has nothing to do
     * with the query — asking it for "android" hands back "Another Round" film
     * posters — while other sources were being mixed in without ever being
     * checked against the words that were typed.
     *
     * So every result is scored against the query, and a result that mentions
     * none of it anywhere (title, site, page address, file address, tags) is
     * dropped. Where a whole source comes back like that, the source answered
     * with something other than the query — that is a junk batch, and the whole
     * batch goes rather than filling the grid with noise.
     */
    /** The words a result must answer, for a cleaned query. */
    fun termsOf(q: String): List<String> = Query.read(q).terms

    /** How many of [terms] this result actually mentions. */
    private fun relevanceOf(img: SearitchImage, terms: List<String>): Int {
        if (terms.isEmpty()) return 1
        val hay = buildString {
            append(img.title.lowercase()).append(' ')
            append(img.sourceSite.lowercase()).append(' ')
            append(img.sourceUrl.lowercase()).append(' ')
            append(img.full.lowercase()).append(' ')
            append(img.creator?.lowercase().orEmpty()).append(' ')
            append(img.tags.joinToString(" ").lowercase())
        }.replace('-', ' ').replace('_', ' ').replace('/', ' ')
        var hits = 0
        for (t in terms) {
            // The word itself, or its stem: "cathedral" then also answers
            // "cathedrals", and "mountain" answers "mountains" — which is how a
            // person reads a search, and costs nothing.
            val stem = if (t.length >= 5) t.take(5) else t
            if (hay.contains(t) || (stem.length >= 5 && hay.contains(stem))) hits++
        }
        return hits
    }

    /**
     * Drops everything that doesn't answer [q], and returns what survived in
     * order of how much of the query it mentions. A batch where almost nothing
     * matches is discarded whole.
     */
    private fun gate(plan: Query.Plan, list: List<SearitchImage>): List<SearitchImage> {
        val terms = plan.terms
        if (terms.isEmpty() || list.isEmpty()) return list
        val scored = list.map { it to relevanceOf(it, terms) }
        val kept = scored.filter { it.second > 0 }
        // Fewer than one in eight matching means the source answered a different
        // question — a trending page, a default feed — not that the query is rare.
        if (list.size >= 8 && kept.size * 8 < list.size) return emptyList()
        return kept.sortedByDescending { it.second }.map { it.first }
    }

    private fun passesFilters(img: SearitchImage, f: Filters) =
        img.thumb.isNotEmpty() && img.full.isNotEmpty() &&
            matchesType(img, f.type) && matchesAspect(img, f.aspect) && matchesSize(img, f.size) &&
            (!f.safe || !isNsfw(img)) &&
            (!f.antiAi || !isAiGenerated(img))

    private fun interleave(lists: List<List<SearitchImage>>): List<SearitchImage> {
        val out = mutableListOf<SearitchImage>()
        val seen = mutableSetOf<String>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val img = l.getOrNull(i) ?: continue
            if (seen.add(img.full)) out += img
        }
        return out
    }

    /**
     * Streaming search: each source's results are emitted the moment that source
     * answers, so the first photos appear after the fastest source instead of the
     * slowest. The opening batch waits GATHER_MS after the first answer and is
     * interleaved across sources so the first screen is a real mix of sites.
     */
    fun searchStream(q: String, filters: Filters, page: Int): Flow<List<SearitchImage>> = channelFlow {
        val plan = Query.read(q)
        val active = providers(plan.text, filters, page).filterNot { shouldSkip(it.first) }
        if (active.isEmpty()) return@channelFlow

        val lock = Mutex()
        val seen = mutableSetOf<String>()
        val gathered = mutableListOf<List<SearitchImage>>()
        var flushed = false
        var finished = 0
        var timer: Job? = null

        suspend fun flush() {
            if (flushed) return
            flushed = true
            val self = currentCoroutineContext()[Job]
            timer?.takeIf { it !== self }?.cancel()
            // Ranked, mixed across sources, and with near-copies collapsed —
            // the same order a completed search would have produced.
            val mixed = Ranker.dedupe(Ranker.mix(gathered, plan), plan)
            if (mixed.isNotEmpty()) send(mixed)
        }

        active.forEach { (name, run) ->
            launch {
                val started = currentTimeMillis()
                val r = runCatching { withTimeout(budgetFor(name)) { run() } }
                val raw = r.getOrDefault(emptyList())
                recordResult(name, r.isSuccess, currentTimeMillis() - started, raw.size)
                lock.withLock {
                    val gated = gate(plan, raw)
                    val fresh = gated.filter { passesFilters(it, filters) && seen.add(it.full) }
                    if (fresh.isNotEmpty()) {
                        if (flushed) {
                            // After the opening flush each source's answer goes
                            // out as it arrives; it is ordered within itself so
                            // the best of that answer is on top.
                            send(Ranker.order(fresh, plan))
                        } else {
                            gathered += fresh
                            if (timer == null) timer = launch { delay(GATHER_MS); lock.withLock { flush() } }
                        }
                    }
                    finished++
                    if (finished == active.size) flush()
                }
            }
        }
    }

    // --------------------------------------------------------- related ----

    /**
     * "Similar" means "shares this picture's subject", not "matches the word the
     * user typed".
     *
     * What was here before re-ran the *search text* — so a Wallhaven wall that
     * arrived with no tags of its own was "similar" to the literal word
     * `wallpaper`, and the strip filled up with mountains, paper rolls and a
     * group photo. Three things fix that:
     *
     *  1. [hydrate] asks the source for the metadata a search response drops
     *     (Wallhaven's tags), so a photo has a subject at all.
     *  2. The subject — from the source's own tags, or from the words in its
     *     title — is searched instead of the query.
     *  3. Every candidate is scored on that subject and the ones that don't name
     *     it are dropped, then the strip is mixed across sites. The detail
     *     screen finishes the job by re-ordering what comes back by how much the
     *     pictures actually look alike (see Visual.rank).
     */
    suspend fun related(img: SearitchImage): List<SearitchImage> {
        val src = hydrate(img)
        val subject = subjectOf(src)

        // Openverse publishes an ML "more like this" index keyed on the image
        // itself. It is the only source that can answer without guessing, so it
        // is asked first and its answer still goes through the subject check.
        if (src.provider == "openverse") {
            val rawId = src.id.substringAfter(':')
            val near = runCatching {
                val res: OvResponse = http
                    .get("https://api.openverse.org/v1/images/$rawId/related/?page_size=20")
                    .body()
                res.results.map { it.toImage() }
            }.getOrDefault(emptyList())
            val ranked = rankBySubject(src, near, subject)
            if (ranked.isNotEmpty()) return ranked
        }

        if (subject.isEmpty()) return emptyList()
        // Two words carry the subject without pinning the search so tightly that
        // a source returns nothing; a wider pass is made only when it does.
        // Relatedness is not the user's search: the gate would throw away a
        // perfectly good neighbour for not containing the word, so the subject
        // is applied here instead, on the hydrated result (see rankBySubject).
        val pool = runCatching { rawSearch(subject.take(2).joinToString(" "), Filters(), 1) }
            .getOrDefault(emptyList())
        val narrow = rankBySubject(src, pool, subject)
        if (narrow.size >= 8) return narrow
        val wider = runCatching { rawSearch(subject.first(), Filters(), 1) }.getOrDefault(emptyList())
        return rankBySubject(src, pool + wider, subject)
    }

    /**
     * Keeps the results that share a subject word with [src] and puts them in
     * order of how much they share. A tag hit counts for more than a title hit
     * because tags are curated; the same site counts for a little, because the
     * same wall collection or the same museum is a real hint.
     *
     * Nothing is kept on the strength of the site alone — that is exactly how a
     * group photo ended up next to an anime wall.
     */
    private fun rankBySubject(
        src: SearitchImage,
        pool: List<SearitchImage>,
        subject: List<String>,
    ): List<SearitchImage> {
        val candidates = pool
            .filter { it.id != src.id && it.full.isNotEmpty() && it.full != src.full }
            .distinctBy { it.full }
        if (subject.isEmpty() || candidates.isEmpty()) return candidates.take(24)

        val scored = candidates.map { cand ->
            val tags = cand.tags.joinToString(" ").lowercase()
            val title = cand.title.lowercase()
            var score = 0
            for (word in subject) {
                when {
                    tags.contains(word) -> score += 3
                    title.contains(word) -> score += 2
                }
            }
            if (cand.provider == src.provider) score += 1
            cand to score
        }

        val named = scored.filter { it.second >= 2 }
        val chosen = if (named.size >= 6) named else scored
        // Mix the sites instead of letting one collection fill the strip.
        val buckets = chosen
            .sortedByDescending { it.second }
            .groupBy { it.first.provider }
            .values
            .map { group -> group.map { it.first }.take(8) }
        return interleave(buckets).take(24)
    }

    /** Words that identify a subject rather than describe a file. */
    private val NOISE = setOf(
        "the", "and", "with", "from", "for", "this", "that", "photo", "photos", "image",
        "images", "picture", "pictures", "untitled", "stock", "photography", "of", "in",
        "on", "at", "by", "a", "an", "to", "public", "domain", "collection", "museum",
        "jpg", "png", "file", "art", "print",
    ) + WALLHAVEN_GENERIC

    /**
     * The subject of a photo, as one to three phrases: the source's own tags
     * ("fox girl", "flute"), or when it has none, the naming words of its title.
     *
     * Hostnames are not subjects: Bing labels a card with the site it came from,
     * and "wallhaven.cc" as a subject is how a search turns into noise.
     */
    fun subjectOf(img: SearitchImage): List<String> {
        val tags = img.tags
            .map { it.substringBefore('-').trim().lowercase() }
            .filter { it.length in 3..28 && it !in NOISE && it.any(Char::isLetter) }
            .filterNot { it.contains('.') || it.contains('/') || it.contains(':') }
            .distinct()
        if (tags.isNotEmpty()) return tags.take(3)

        // Museum titles put the subject first: "Cat Coffin", "Moonrise", "Lion
        // (One of a Pair)" — trim the catalogue tail before splitting.
        val title = img.title
            .substringBefore('(')
            .substringBefore(',')
            .substringBefore('—')
            .substringBefore('|')
            .trim()
        return title
            .replace(Regex("[_\\-./:]"), " ")
            .split(Regex("\\s+"))
            .map { it.lowercase().trim() }
            .filter { it.length > 2 && it !in NOISE && it.any(Char::isLetter) }
            .distinct()
            .take(3)
    }

    /** What the detail screen shows under SIMILAR IMAGES: "fox girl · flute". */
    fun subjectLabel(img: SearitchImage): String = subjectOf(img).joinToString(" · ")
}
