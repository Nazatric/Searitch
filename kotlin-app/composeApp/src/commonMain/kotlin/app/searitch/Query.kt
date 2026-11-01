package app.searitch

/**
 * What the words in the search box actually mean.
 *
 * Until now the box was a pipe: whatever was typed went to fourteen sources
 * verbatim, and the only intelligence sat in the gate, which threw away results
 * that didn't mention the query. That catches a bad answer, but it never helps a
 * good question — "red car" and "RED   car" and "transparent png of a car" all
 * arrived at Bing and Wallhaven as the same undifferentiated string.
 *
 * This reads the query once, before any source is called, and produces a plan:
 * the text that should be sent, and what the words themselves already imply.
 *
 * It is deliberately timid about acting. A word like "red" describes a colour
 * the user may or may not mean as a filter — "red panda" is not a red thing —
 * so a hint is *offered* on screen ("colour: red") and applied only when the
 * filter already says so or the user taps it. The one thing applied outright is
 * a file-format word, because "png" and "transparent" describe the file itself
 * and there is no other reading of them.
 */
object Query {

    /** What kind of picture the words ask for, when they say so. */
    enum class Intent(val label: String) {
        GENERAL(""),
        WALLPAPER("wallpaper"),
        ICON("icon"),
        ARTWORK("artwork"),
        PATTERN("pattern"),
        PHOTOGRAPH("photograph"),
    }

    /**
     * @param text   the query as it should be sent — cleaned, with filler that
     *               only confuses a source ("hd", "high resolution") removed
     * @param terms  the words a result must answer, for the gate
     * @param colour named colour key found in the words, if any
     * @param type   a file-format word (png / svg / transparent …)
     * @param intent what sort of picture was asked for
     * @param size   a size word ("4k", "high resolution")
     */
    data class Plan(
        val text: String,
        val terms: List<String>,
        val colour: String? = null,
        val type: TypeFilter? = null,
        val intent: Intent = Intent.GENERAL,
        val size: SizeFilter? = null,
    ) {
        /** The parts of the plan the user should see, in their own words. */
        fun understood(): List<String> = buildList {
            // `size` on its own would resolve to the list's own size inside
            // buildList, so the plan's hint is named explicitly.
            val sizeHint = this@Plan.size
            colour?.let { add("colour: ${labelOf(it)}") }
            type?.let { if (it != TypeFilter.ANY) add(it.label.lowercase()) }
            sizeHint?.let { add(it.label.lowercase()) }
            if (intent != Intent.GENERAL) add(intent.label)
        }

        /**
         * The filters this plan would apply if the user asks for them. A hint is
         * never stronger than an explicit choice: anything already set wins.
         */
        fun applyTo(f: Filters): Filters {
            var next = f
            if (next.type == TypeFilter.ANY && type != null) next = next.copy(type = type)
            if (next.size == SizeFilter.ANY && size != null) next = next.copy(size = size)
            if (next.color.isEmpty() && next.colorHex.isEmpty() && colour != null) {
                next = next.copy(color = colour)
            }
            return next
        }
    }

    /**
     * Words that describe the file rather than the subject. They still reach the
     * sources — a query of nothing but "wallpaper" has to search something — they
     * are only kept out of the gate, which matches against a picture's title and
     * tags where "hd" would be noise.
     */
    private val FORMAT_WORDS = mapOf(
        "transparent" to TypeFilter.TRANSPARENT,
        "transparency" to TypeFilter.TRANSPARENT,
        "png" to TypeFilter.PNG,
        "svg" to TypeFilter.SVG,
        "gif" to TypeFilter.GIF,
        "jpg" to TypeFilter.JPG,
        "jpeg" to TypeFilter.JPG,
        "vector" to TypeFilter.SVG,
    )

    /** Filler a source reads past, and the gate should too. */
    private val FILLER = setOf(
        "the", "a", "an", "and", "or", "of", "for", "in", "on", "at", "to", "with",
        "my", "me", "image", "images", "photo", "photos", "picture", "pictures",
        "pic", "pics", "wallpaper", "wallpapers", "hd", "4k", "8k", "uhd", "free",
        "best", "good", "nice", "cool", "please", "high", "resolution", "quality",
        "download", "online", "search",
    )

    /** Size words, mapped to the filter that means the same thing. */
    private val SIZE_WORDS = setOf("4k", "8k", "uhd", "hd", "1080p", "1440p", "2160p")

    private val INTENT_WORDS = mapOf(
        "wallpaper" to Intent.WALLPAPER,
        "background" to Intent.WALLPAPER,
        "icon" to Intent.ICON,
        "icons" to Intent.ICON,
        "logo" to Intent.ICON,
        "clipart" to Intent.ICON,
        "illustration" to Intent.ARTWORK,
        "painting" to Intent.ARTWORK,
        "drawing" to Intent.ARTWORK,
        "sketch" to Intent.ARTWORK,
        "pattern" to Intent.PATTERN,
        "texture" to Intent.PATTERN,
        "seamless" to Intent.PATTERN,
        "photograph" to Intent.PHOTOGRAPH,
        "photo" to Intent.PHOTOGRAPH,
    )

    /**
     * Reads a query. [text] is what the sources are asked for: the cleaned query
     * with size shorthands dropped ("cat 4k wallpaper" → "cat"), because that is
     * a description of the file, not of the subject, and every source treats
     * those words as a literal requirement.
     */
    fun read(raw: String): Plan {
        val clean = Api.cleanQuery(raw)
        val words = clean.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return Plan(clean, emptyList())

        val colour = COLOR_OPTIONS.firstOrNull { it.key in words }?.key
        val type = words.firstNotNullOfOrNull { FORMAT_WORDS[it] }
        val intent = words.firstNotNullOfOrNull { INTENT_WORDS[it] } ?: Intent.GENERAL
        val size = if (words.any { it in SIZE_WORDS }) SizeFilter.LARGE else null

        // Strip size shorthands and bare format words from what is sent, but keep
        // descriptive intent words: "cat illustration" and "cat" should find
        // different pictures.
        val sent = words.filterNot { it in SIZE_WORDS }
        val text = if (sent.isEmpty()) clean else sent.joinToString(" ")

        // The gate matches against titles and tags, where the word "png" never
        // appears — but a query of nothing else has to keep its only word.
        val subjects = words.filterNot { it in FORMAT_WORDS }
        val terms = (subjects.ifEmpty { words })
            .filterNot { it in FILLER }
            .distinct()
            .ifEmpty { words }

        return Plan(
            text = text,
            terms = terms,
            colour = colour,
            type = type,
            intent = intent,
            size = size,
        )
    }

    private fun labelOf(key: String): String =
        COLOR_OPTIONS.firstOrNull { it.key == key }?.label ?: key

    /* ─────────────────────── refinements from results ─────────────────────── */

    /**
     * Words that would narrow this search, taken from what actually came back.
     *
     * If a search for "cat" returns pictures tagged *bengal*, *kitten* and
     * *tabby*, those are real, indexed words in the catalogue that answered —
     * unlike a generated "related search" list, which is a guess. Only tags are
     * used (they are curated by the source), only words the query doesn't
     * already contain, and only words that several different pictures share, so
     * one unusual caption can't become a recommendation.
     */
    fun refinements(
        results: List<SearitchImage>,
        plan: Plan,
        limit: Int = 6,
    ): List<String> {
        if (results.size < 6) return emptyList()
        val typed = plan.text.lowercase().split(' ').toSet()
        val counts = HashMap<String, Int>()
        val owners = HashMap<String, MutableSet<String>>()

        for (img in results) {
            for (tag in img.tags) {
                val word = tag.substringBefore('-').trim().lowercase()
                if (word.length !in 3..22) continue
                if (word in FILLER) continue
                if (word.any { it in "./:@#" }) continue
                if (word.any { ch -> ch.code > 0x2000 }) continue
                if (word in typed || typed.any { it.isNotEmpty() && word.contains(it) && it.length > 3 }) continue
                counts[word] = (counts[word] ?: 0) + 1
                // Count distinct pictures, not tags: a picture that repeats a tag
                // in three spellings is still one picture.
                owners.getOrPut(word) { mutableSetOf() }.add(img.id)
            }
        }

        val need = (results.size / 12).coerceAtLeast(2)
        return counts.entries
            .filter { (word, _) -> (owners[word]?.size ?: 0) >= need }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { owners[it.key]?.size ?: 0 }
                    .thenByDescending { it.value }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { it.key }
    }
}
