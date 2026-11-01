package app.searitch

import coil3.Image

/**
 * The colours a picture is actually made of.
 *
 * The detail page offers them as swatches, and tapping one searches that colour
 * — which turns "I like this painting" into "show me things in that blue", using
 * the exact hex on the sources that understand a hex and the nearest name on the
 * ones that only understand names.
 *
 * Sampling is coarse on purpose: a few hundred pixels say everything about a
 * palette, and the alternative — walking four megapixels on a phone — is exactly
 * the kind of quiet cost that makes a page feel slow.
 */
expect fun Image.palette(max: Int = 5): List<Int>

/**
 * Turns sampled pixels into a small, distinct palette.
 *
 * Bucketing in 5 bits per channel groups near-identical shades; the returned
 * colours are the average of their bucket, so a photograph of a sunset gives a
 * real orange rather than the nearest arbitrary quantised value. Colours that
 * are too close to one already chosen are dropped, so the row never shows five
 * shades of the same sky.
 */
internal fun paletteFrom(pixels: IntArray, max: Int): List<Int> {
    if (pixels.isEmpty() || max <= 0) return emptyList()
    val buckets = HashMap<Int, IntArray>()   // key -> [count, rSum, gSum, bSum]
    for (px in pixels) {
        val a = (px ushr 24) and 0xFF
        if (a < 200) continue                    // transparent pixels say nothing
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val acc = buckets.getOrPut(key) { IntArray(4) }
        acc[0]++
        acc[1] += r
        acc[2] += g
        acc[3] += b
    }
    if (buckets.isEmpty()) return emptyList()

    val chosen = mutableListOf<Int>()
    for ((_, acc) in buckets.entries.sortedByDescending { it.value[0] }) {
        val n = acc[0]
        val r = acc[1] / n
        val g = acc[2] / n
        val b = acc[3] / n
        if (chosen.any { near(it, r, g, b) }) continue
        chosen += (r shl 16) or (g shl 8) or b
        if (chosen.size >= max) break
    }
    return chosen
}

/** Close enough that two swatches would look like a mistake. */
private fun near(existing: Int, r: Int, g: Int, b: Int): Boolean {
    val dr = ((existing shr 16) and 0xFF) - r
    val dg = ((existing shr 8) and 0xFF) - g
    val db = (existing and 0xFF) - b
    return dr * dr + dg * dg + db * db < 48 * 48
}
