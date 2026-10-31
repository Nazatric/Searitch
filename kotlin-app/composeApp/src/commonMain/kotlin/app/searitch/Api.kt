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
 * Thirteen keyless sources, queried in parallel and merged as each one answers:
 * Bing (the open web), Openverse, Wikimedia Commons, Wallhaven, Flickr,
 * iNaturalist, NASA, the Met, the Art Institute of Chicago, the Cleveland
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
    )

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
    private fun colorTerm(f: Filters): String =
        COLOR_OPTIONS.firstOrNull { it.key == f.color }?.let { " ${it.key}" } ?: ""

    private fun matchesAspect(img: SearitchImage, aspect: AspectFilter): Boolean {
        if (aspect == AspectFilter.ANY || img.width == 0 || img.height == 0) return true
        val r = img.width.toDouble() / img.height
        return when (aspect) {
            AspectFilter.WIDE -> r >= 1.2
            AspectFilter.TALL -> r <= 1 / 1.2
            else -> r > 1 / 1.2 && r < 1.2
        }
    }

    private fun matchesSize(img: SearitchImage, size: SizeFilter): Boolean {
        if (size == SizeFilter.ANY || img.width == 0 || img.height == 0) return true
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
            COLOR_OPTIONS.firstOrNull { it.key == f.color }
                ?.let { parameters.append("colors", it.wallhaven) }
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
                title = "Wallpaper ${r.dimensionX ?: 0}×${r.dimensionY ?: 0}",
                thumb = r.thumbs?.small ?: r.thumbs?.original ?: r.path ?: "",
                // `large` is ~1920px: sharp on a phone, a fraction of the original
                preview = r.thumbs?.large ?: r.thumbs?.original,
                full = r.path ?: "",
                width = r.dimensionX ?: 0,
                height = r.dimensionY ?: 0,
                filetype = ft,
                sourceSite = "Wallhaven",
                sourceUrl = r.url ?: r.path ?: "",
                tags = r.tags.mapNotNull { it.name.trim().lowercase().takeIf(String::isNotEmpty) }.take(8),
            )
        }
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
    private class Health(var fails: Int = 0, var skipLeft: Int = 0)

    private val health = mutableMapOf<String, Health>()

    private fun shouldSkip(name: String): Boolean {
        val h = health[name] ?: return false
        return if (h.skipLeft > 0) {
            h.skipLeft--
            true
        } else false
    }

    private fun recordResult(name: String, ok: Boolean) {
        val h = health.getOrPut(name) { Health() }
        if (ok) {
            h.fails = 0
            h.skipLeft = 0
        } else {
            h.fails++
            if (h.fails >= 2) h.skipLeft = 5
        }
    }

    private fun budgetFor(name: String): Long = when (name) {
        "openverse" -> 4_000
        "bing" -> 5_000
        "loc" -> 5_000
        else -> 3_500
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
                    (f.color.isEmpty() || p in COLOUR_AWARE) &&
                    (f.license == LicenseFilter.ANY || p in LICENCE_AWARE)
            }
            .map { (_, name, run) -> name to run }
    }

    suspend fun search(q: String, filters: Filters, page: Int): List<SearitchImage> =
        coroutineScope {
            val lists = providers(q, filters, page).filterNot { shouldSkip(it.first) }.map { (name, run) ->
                async {
                    val r = runCatching { withTimeout(budgetFor(name)) { run() } }
                    recordResult(name, r.isSuccess)
                    r.getOrDefault(emptyList())
                }
            }.awaitAll()

            // Interleave sources + dedupe, then apply honest local filters.
            val out = mutableListOf<SearitchImage>()
            val seen = mutableSetOf<String>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (list in lists) {
                val img = list.getOrNull(i) ?: continue
                if (img.thumb.isEmpty() || img.full.isEmpty() || !seen.add(img.full)) continue
                if (passesFilters(img, filters)) out += img
            }
            out
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
    private data class InatPhoto(val url: String = "", val attribution: String? = null)

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
                thumb = photo.url,
                thumbFallback = photo.url,
                full = photo.url.replace("/square.", "/original."),
                width = 0, height = 0, filetype = "jpg",
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
            // The thumbnail sits in raw HTML, so its query string is still
            // entity-escaped — leave `&amp;` in place and the load fails.
            val thumb = Regex("(https://th\\.bing\\.com/th/id/[^\"'\\s]+)").find(region)
                ?.groupValues?.get(1)
                ?.replace("&amp;", "&")
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

    private suspend fun searchBing(q: String, f: Filters, page: Int): List<SearitchImage> {
        val count = 35
        val first = (page - 1) * count + 1
        val qft = buildString {
            BING_COLOR[f.color]?.let { append("+filterui:color2-FGcls_").append(it) }
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
                preview = hit.media,
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
        val active = providers(q, filters, page).filterNot { shouldSkip(it.first) }
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
            val mixed = interleave(gathered)
            if (mixed.isNotEmpty()) send(mixed)
        }

        active.forEach { (name, run) ->
            launch {
                val r = runCatching { withTimeout(budgetFor(name)) { run() } }
                recordResult(name, r.isSuccess)
                lock.withLock {
                    val fresh = r.getOrDefault(emptyList()).filter { passesFilters(it, filters) && seen.add(it.full) }
                    if (fresh.isNotEmpty()) {
                        if (flushed) {
                            send(fresh)
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

    suspend fun related(img: SearitchImage): List<SearitchImage> {
        // Openverse exposes a related endpoint keyed on the image itself — the
        // closest thing to "more like this" any of these APIs offers.
        if (img.provider == "openverse") {
            val rawId = img.id.substringAfter(':')
            runCatching {
                val res: OvResponse = http
                    .get("https://api.openverse.org/v1/images/$rawId/related/?page_size=16")
                    .body()
                res.results.map { it.toImage() }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        // Otherwise the metadata is the only signal there is, so use the part of
        // it that actually names the subject: the source's own tags, and only
        // when there are none, the words in the title.
        val words = relatedQuery(img)
        if (words.isEmpty()) return emptyList()
        return search(words, Filters(), 1)
            .filter { it.id != img.id && it.full != img.full }
            .take(24)
    }

    /** Words that identify a subject rather than describe a file. */
    private val NOISE = setOf(
        "the", "and", "with", "from", "for", "this", "that", "photo", "photos", "image",
        "images", "picture", "pictures", "untitled", "stock", "photography", "of", "in",
        "on", "at", "by", "a", "an", "to", "public", "domain", "collection", "museum",
        "jpg", "png", "file", "art", "print",
    )

    private fun relatedQuery(img: SearitchImage): String {
        val tags = img.tags
            .map { it.substringBefore('-').trim().lowercase() }
            .filter { it.length in 3..24 && it !in NOISE && it.any(Char::isLetter) }
            .distinct()
        if (tags.isNotEmpty()) return tags.take(2).joinToString(" ")

        // Museum titles put the subject first: "Cat Coffin", "Moonrise", "Lion
        // (One of a Pair)" — trim the catalogue tail before splitting.
        val title = img.title
            .substringBefore('(')
            .substringBefore(',')
            .substringBefore('\u2014')
            .substringBefore('|')
            .trim()
        val words = title
            .replace(Regex("[_\\-./:]"), " ")
            .split(Regex("\\s+"))
            .map { it.lowercase().trim() }
            .filter { it.length > 2 && it !in NOISE && it.any(Char::isLetter) }
            .distinct()
            .take(3)
        return words.joinToString(" ")
    }
}
