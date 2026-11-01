package app.searitch

/**
 * Which results go first.
 *
 * The old pipeline mixed sources round-robin and stopped there, which is fair
 * but dumb: a 120 px stock thumbnail could sit above a 6000 px museum scan, a
 * picture whose title is exactly what was typed lost to one that merely shares a
 * tag, and the same wallpaper arriving from Bing and from Wallhaven took two
 * slots because the dedupe only compared the file URL.
 *
 * This scores a result on what a person would actually use to choose:
 *
 *  • **does it answer the query** — whole-word hits in the title weigh most,
 *    then tags, then the site, then the page and file address (which only ever
 *    carry a hint);
 *  • **is it the shape and size asked for** — a wallpaper query with a 300 px
 *    thumb is a broken promise;
 *  • **is the source the right kind of source** — a museum scan has a catalogue
 *    behind it, a search-engine thumbnail is a repost of unknown provenance;
 *  • **is it a duplicate** — near-copies are collapsed onto the best copy.
 *
 * Nothing here overrides a filter. Filters decide what is allowed; this decides
 * the order of what is left.
 */
object Ranker {

    /** How much a source's own claiming of the subject is worth. */
    private fun trust(provider: String): Int = when (provider) {
        // Curated catalogues: the title and tags describe the actual object.
        "met", "aic", "cleveland", "vam", "europeana", "loc", "nasa" -> 6
        "openverse", "wikimedia", "inaturalist", "wallhaven" -> 4
        "flickr", "yandex" -> 2
        else -> 1
    }

    /** A rough sense of how big a picture is, without pretending to know better. */
    private fun pixels(img: SearitchImage): Long =
        if (img.width > 0 && img.height > 0) img.width.toLong() * img.height else 0L

    private fun sizeBonus(img: SearitchImage): Int {
        val px = pixels(img)
        return when {
            px <= 0 -> 0            // unknown: neither rewarded nor punished
            px >= 8_000_000 -> 5
            px >= 2_000_000 -> 4
            px >= 800_000 -> 3
            px >= 300_000 -> 1
            else -> -3              // a stamp in a grid of photographs
        }
    }

    /** How well the shape suits what was asked for. */
    private fun intentBonus(img: SearitchImage, plan: Query.Plan): Int {
        if (img.width <= 0 || img.height <= 0) return 0
        val ratio = img.width.toFloat() / img.height
        return when (plan.intent) {
            Query.Intent.WALLPAPER -> when {
                ratio in 1.2f..2.4f -> 3
                ratio < 1f -> -2
                else -> 0
            }
            Query.Intent.ICON -> when {
                ratio in 0.8f..1.25f -> 3
                else -> 0
            }
            Query.Intent.PATTERN -> when {
                // A pattern needs to look like it could repeat.
                ratio in 0.8f..1.4f -> 2
                else -> 0
            }
            else -> 0
        }
    }

    /** Whole words count; a substring buried inside a longer word counts less. */
    private fun termScore(img: SearitchImage, terms: List<String>): Int {
        if (terms.isEmpty()) return 1
        val title = img.title.lowercase().replace('-', ' ').replace('_', ' ')
        val tags = img.tags.joinToString(" ").lowercase()
        val site = img.sourceSite.lowercase()
        val urls = (img.sourceUrl + ' ' + img.full).lowercase().replace('-', ' ').replace('_', ' ')
        var score = 0
        for (t in terms) {
            val stem = if (t.length >= 5) t.take(5) else t
            fun hit(hay: String): Boolean =
                hay.contains(t) || (stem.length >= 5 && hay.contains(stem))
            score += when {
                hit(title) -> 4
                hit(tags) -> 3
                hit(site) -> 2
                hit(urls) -> 1
                else -> 0
            }
        }
        return score
    }

    /** A title is the strongest signal there is; a missing one is a weak sign. */
    private fun titleQuality(img: SearitchImage): Int {
        val t = img.title.trim()
        return when {
            t.isEmpty() -> -2
            t.length < 4 -> -1
            t.lowercase() in setOf("untitled", "image", "photo", "picture", "wallpaper") -> -2
            // A filename pretending to be a title ("IMG_2041", "dsc01234").
            Regex("(?i)^(img|dsc|dcim|photo|image|untitled|screenshot)[ _-]?\\d{2,}$").matches(t) -> -2
            else -> 1
        }
    }

    /** Total order for the results of one search. */
    /** Total score for one result against one query. Higher is better. */
    fun score(img: SearitchImage, plan: Query.Plan): Int {
        var score = termScore(img, plan.terms) + trust(img.provider) +
            sizeBonus(img) + titleQuality(img) + intentBonus(img, plan)

        // A hint the user asked for as a filter is already enforced upstream; a
        // hint that is only *understood* is a preference, and prefers.
        val wantedColour = plan.colour
        if (wantedColour != null && img.tags.any { it.contains(wantedColour, ignoreCase = true) }) {
            score += 2
        }
        return score
    }

    fun order(list: List<SearitchImage>, plan: Query.Plan): List<SearitchImage> {
        if (list.size < 2) return list
        return list
            .mapIndexed { index, img -> Triple(img, score(img, plan), index) }
            // Ties keep the order the sources gave, which keeps paging stable.
            .sortedWith(
                compareByDescending<Triple<SearitchImage, Int, Int>> { it.second }
                    .thenBy { it.third },
            )
            .map { it.first }
    }

    /* ───────────────────────────── duplicates ───────────────────────────── */

    /**
     * A stable identity for "the same picture, from somewhere else".
     *
     * Search engines repost the host's file under their own CDN name, so the URL
     * can't be compared. What survives a repost is the combination of the last
     * path segment reduced to its stem (`sunset-1600x900.jpg` → `sunset`) and the
     * pixel size. Two different pictures called `sunset` at 1600×900 are rare
     * enough to accept, and the collapse keeps whichever copy scores higher.
     */
    /**
     * The key paging uses to keep one copy of a picture that arrives from two
     * sources on two different pages. Public because the feed merge needs the
     * same idea of "same picture" the ranker uses.
     */
    fun pictureKey(img: SearitchImage): String = identity(img)

    private fun identity(img: SearitchImage): String {
        val stem = img.full
            .substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase()
            .replace(Regex("-\\d{2,4}x\\d{2,4}$"), "")
            .replace(Regex("[-_](small|thumb|thumbnail|medium|large|original|preview|scaled|\\d{2,5})$"), "")
        val dims = if (img.width > 0 && img.height > 0) "${img.width}x${img.height}" else "?"
        return "$stem|$dims"
    }

    /**
     * Drops near-copies, keeping the best-scoring one. A stem has to be long
     * enough to be a name rather than a coincidence, and a picture with no
     * dimensions is never matched on the stem alone.
     */
    fun dedupe(list: List<SearitchImage>, plan: Query.Plan): List<SearitchImage> {
        fun comparable(img: SearitchImage): String? {
            val id = identity(img)
            val stem = id.substringBefore('|')
            val hasDims = !id.endsWith("|?")
            return if (stem.length >= 8 && hasDims) id else null
        }

        val best = HashMap<String, SearitchImage>()
        for (img in list) {
            val id = comparable(img) ?: continue
            val existing = best[id]
            if (existing == null || score(img, plan) > score(existing, plan)) best[id] = img
        }
        // Keep the order the list arrived in, minus the copies that lost.
        return list.filter { img ->
            val id = comparable(img) ?: return@filter true
            best[id] === img
        }
    }

    /* ─────────────────────────────── mixing ─────────────────────────────── */

    /**
     * Interleaves sources so the top of the grid isn't one collection, while
     * letting the best-scoring picture in each round go first.
     *
     * [cap] keeps any one source from owning the page: with fourteen sources and
     * a cap of six, a page always shows at least four sites, and a source that
     * returned fifty results can't bury the rest.
     */
    fun mix(
        lists: List<List<SearitchImage>>,
        plan: Query.Plan,
        cap: Int = 6,
    ): List<SearitchImage> = mixWithCap(lists, plan, cap)

    /**
     * The cap is a share of the page, not a fixed number: with one source
     * selected the user asked for that source, so all of it may come through;
     * with fourteen, no single one may own more than its share.
     */
    private fun mixWithCap(
        lists: List<List<SearitchImage>>,
        plan: Query.Plan,
        requestedCap: Int,
    ): List<SearitchImage> {
        val cap = if (lists.size <= 1) Int.MAX_VALUE else requestedCap.coerceAtLeast(60 / lists.size)
        val queues = lists
            .map { order(it, plan) }
            .filter { it.isNotEmpty() }
        if (queues.isEmpty()) return emptyList()
        val out = mutableListOf<SearitchImage>()
        val seen = HashSet<String>()
        val used = HashMap<Int, Int>()
        val cursor = IntArray(queues.size)

        fun take(i: Int): Boolean {
            val q = queues[i]
            while (cursor[i] < q.size) {
                val img = q[cursor[i]++]
                if (!seen.add(img.full)) continue
                out += img
                used[i] = (used[i] ?: 0) + 1
                return true
            }
            return false
        }

        var progressed = true
        while (progressed) {
            progressed = false
            for (i in queues.indices) {
                if ((used[i] ?: 0) >= cap) continue
                if (take(i)) progressed = true
                if (out.size >= 400) return out
            }
        }
        return out
    }
}
