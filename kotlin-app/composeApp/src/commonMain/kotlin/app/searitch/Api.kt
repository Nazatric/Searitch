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
 * Searitch aggregation layer (common code — runs the same on Android & Wasm).
 * Queries three real, keyless image APIs in parallel:
 *  - Openverse (index of 700M+ images: Flickr, Wikimedia, NASA, museums, …)
 *  - Wikimedia Commons (100M+ media files)
 *  - Wallhaven (community wallpapers with native color search)
 *
 * NOTE for the Wasm/browser target: Openverse and Wikimedia send CORS headers
 * and work in-browser. Wallhaven has no CORS headers, so it only returns
 * results in the native Android build — failures are swallowed gracefully.
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
    private data class WhThumbs(val small: String? = null, val original: String? = null)

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
        var search = q + colorTerm(f)
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

        val tags = (q.trim().replace(Regex("\\s+"), ",") + colorTerm(f).trim()
            .let { if (it.isEmpty()) "" else ",$it" })
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

    private fun budgetFor(name: String): Long = if (name == "openverse") 4_000 else 3_500

    suspend fun search(q: String, filters: Filters, page: Int): List<SearitchImage> =
        coroutineScope {
            fun want(p: ProviderFilter) =
                filters.provider == ProviderFilter.ALL || filters.provider == p

            val defs = mutableListOf<Pair<String, suspend () -> List<SearitchImage>>>()
            if (want(ProviderFilter.OPENVERSE))
                defs += "openverse" to { searchOpenverse(q, filters, page) }
            if (want(ProviderFilter.WIKIMEDIA))
                defs += "wikimedia" to { searchWikimedia(q, filters, page) }
            if (want(ProviderFilter.WALLHAVEN))
                defs += "wallhaven" to { searchWallhaven(q, filters, page) }
            if (want(ProviderFilter.FLICKR))
                defs += "flickr" to { searchFlickr(q, filters, page) }
            if (want(ProviderFilter.INATURALIST)) defs += "inaturalist" to { searchINaturalist(q) }
            if (want(ProviderFilter.NASA)) defs += "nasa" to { searchNasa(q) }
            if (want(ProviderFilter.MET)) defs += "met" to { searchMet(q) }
            if (want(ProviderFilter.EUROPEANA)) defs += "europeana" to { searchEuropeana(q) }
            if (want(ProviderFilter.VAM)) defs += "vam" to { searchVam(q) }

            val lists = defs.filterNot { shouldSkip(it.first) }.map { (name, run) ->
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

    private suspend fun getJson(url: String, ms: Long = 6000): String? = try {
        // Do not set User-Agent here: browsers forbid that header for Wasm Fetch.
        val res = http.get(url)
        if (res.status.value in 200..299) res.bodyAsText() else null
    } catch (e: Throwable) {
        null
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
        fun want(p: ProviderFilter) = filters.provider == ProviderFilter.ALL || filters.provider == p
        val defs = mutableListOf<Pair<String, suspend () -> List<SearitchImage>>>()
        if (want(ProviderFilter.OPENVERSE)) defs += "openverse" to { searchOpenverse(q, filters, page) }
        if (want(ProviderFilter.WIKIMEDIA)) defs += "wikimedia" to { searchWikimedia(q, filters, page) }
        if (want(ProviderFilter.WALLHAVEN)) defs += "wallhaven" to { searchWallhaven(q, filters, page) }
        if (want(ProviderFilter.FLICKR)) defs += "flickr" to { searchFlickr(q, filters, page) }
        // The direct sources (billion-image catalogue) stream in individually.
        if (want(ProviderFilter.INATURALIST)) defs += "inaturalist" to { searchINaturalist(q) }
        if (want(ProviderFilter.NASA)) defs += "nasa" to { searchNasa(q) }
        if (want(ProviderFilter.MET)) defs += "met" to { searchMet(q) }
        if (want(ProviderFilter.EUROPEANA)) defs += "europeana" to { searchEuropeana(q) }
        if (want(ProviderFilter.VAM)) defs += "vam" to { searchVam(q) }
        val active = defs.filterNot { shouldSkip(it.first) }
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
        if (img.provider == "openverse") {
            val rawId = img.id.substringAfter(':')
            runCatching {
                val res: OvResponse = http
                    .get("https://api.openverse.org/v1/images/$rawId/related/?page_size=16")
                    .body()
                res.results.map { it.toImage() }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Smart fallback: query the image's real semantic tags (provider
        // metadata), and only fall back to title words when it has none.
        val words = if (img.tags.isNotEmpty()) img.tags.take(3).joinToString(" ") else img.title
            .replace(Regex("[_\\-.]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && !it.all(Char::isDigit) }
            .take(4)
            .joinToString(" ")
        if (words.isEmpty()) return emptyList()
        return search(words, Filters(), 1).filter { it.id != img.id }.take(18)
    }
}
