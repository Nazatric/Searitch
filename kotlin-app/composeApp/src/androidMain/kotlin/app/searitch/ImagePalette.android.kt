package app.searitch

import coil3.BitmapImage
import coil3.Image

/**
 * Samples the decoded bitmap on a grid, at most a few hundred pixels.
 *
 * `getPixels` on one row at a time keeps this to a handful of JNI crossings: the
 * grid rows are contiguous strips, so nothing is read twice.
 */
actual fun Image.palette(max: Int): List<Int> {
    val bitmap = (this as? BitmapImage)?.bitmap ?: return emptyList()
    val w = bitmap.width
    val h = bitmap.height
    if (w < 4 || h < 4) return emptyList()

    val cols = 24
    val rows = 24
    val out = IntArray(cols * rows)
    var k = 0
    val row = IntArray(w)
    for (y in 0 until rows) {
        val py = (y * (h - 1)) / (rows - 1)
        bitmap.getPixels(row, 0, w, 0, py, w, 1)
        for (x in 0 until cols) {
            val px = (x * (w - 1)) / (cols - 1)
            out[k++] = row[px]
        }
    }
    return paletteFrom(out, max)
}
