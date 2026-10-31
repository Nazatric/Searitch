package app.searitch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which of these pictures actually look like that one.
 *
 * There is no keyless reverse-image API left to call. Every engine was tried and
 * refused for a different reason: SauceNAO answers "the anonymous account type
 * does not permit API usage", trace.moe refuses to fetch a URL at all, Bing's
 * `searchbyimage?imgurl=` GET redirects to its trending page, and Yandex only
 * builds its similar/sizes/sites blocks in JavaScript — the raw HTML a phone
 * client receives has none of it. So the comparison is made here instead, on
 * pixels the app can actually reach.
 *
 * A 64-bit difference hash: the image is sampled on a 9x8 grid of luminance and
 * every bit records whether one sample is brighter than the one to its right.
 * That survives re-encoding, resizing and a change of crop, and costs 64
 * comparisons per picture. Candidates are then ordered by how few bits differ
 * (Hamming distance; 0 = identical, 64 = unrelated) and anything more than half
 * the bits apart is dropped rather than shown as "similar".
 *
 * Everything fails soft: if a picture can't be read, its candidate keeps the
 * order [Api.related] gave it.
 */
object Visual {

    private const val GRID_W = 9
    private const val GRID_H = 8

    /** Half of 64 — past this two pictures have stopped resembling each other. */
    private const val MAX_DISTANCE = 32

    /** Below this the picture is flat (a gradient, a blank plate): no structure. */
    private const val MIN_CONTRAST = 0.03f

    data class Refined(val images: List<SearitchImage>, val byLook: Boolean)

    /**
     * Re-orders [candidates] so the pictures nearest to [source] in look come
     * first. Nothing is invented: if fewer than three candidates can be read,
     * the list comes back untouched and `byLook` is false.
     */
    suspend fun rank(
        source: SearitchImage,
        candidates: List<SearitchImage>,
        limit: Int = 18,
    ): Refined {
        if (candidates.size < 4) return Refined(candidates.take(limit), false)
        // A hard ceiling on the whole comparison: similar images are a footnote
        // to the picture, and a slow reader must not hold the page hostage.
        val scored = withTimeoutOrNull(8_000) { compare(source, candidates) }
            ?: return Refined(candidates.take(limit), false)
        return scored.takeIf { it.byLook }?.let { Refined(it.images.take(limit), true) }
            ?: Refined(candidates.take(limit), false)
    }

    private suspend fun compare(source: SearitchImage, candidates: List<SearitchImage>): Refined {
        val srcHash = hash(source.cardPicture()) ?: return Refined(candidates, false)

        // Only the strongest candidates are opened; the rest keep their metadata
        // order at the end of the strip. Every one is fetched at the same time —
        // twenty small pictures in parallel take about as long as one.
        val pool = candidates.take(20)
        val hashed = coroutineScope {
            pool.map { cand -> async { hash(cand.cardPicture())?.let { cand to it } } }.awaitAll()
        }.filterNotNull()
        if (hashed.size < 3) return Refined(candidates, false)

        val byDistance = hashed
            .map { (cand, h) -> cand to (srcHash xor h).countOneBits() }
            .sortedBy { it.second }

        val near = byDistance.filter { it.second <= MAX_DISTANCE }
        val kept = (if (near.size >= 6) near else byDistance).map { it.first }

        // Whatever couldn't be read (a source that refused, an SVG) is not thrown
        // away — it just goes after the ones that could be looked at.
        val rest = candidates.filterNot { c -> kept.any { it.id == c.id } }
        return Refined(kept + rest, true)
    }

    /** The 64-bit difference hash of a picture, or null if it can't be read. */
    private suspend fun hash(url: String): Long? {
        if (url.isBlank()) return null
        val lum = probeLuminance(url, GRID_W, GRID_H) ?: return null

        var lo = 1f
        var hi = 0f
        for (v in lum) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        // A flat picture would hash to all zeroes and then "match" every other
        // flat picture. Say so instead.
        if (hi - lo < MIN_CONTRAST) return null

        var hash = 0L
        var bit = 0
        for (y in 0 until GRID_H) {
            for (x in 0 until GRID_W - 1) {
                if (lum[y * GRID_W + x] > lum[y * GRID_W + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }
}

/**
 * Reads [url] and returns a [cols] x [rows] grid of relative luminance (0..1),
 * row by row, or null if the picture can't be read.
 *
 * Deliberately not routed through the image loader: that hands back an
 * ImageBitmap, and the conversion is declared per platform rather than in common
 * code. Decoding a small picture straight from its bytes is one place, no
 * dependency, and easy to reason about.
 */
expect suspend fun probeLuminance(url: String, cols: Int, rows: Int): FloatArray?
