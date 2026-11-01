package app.searitch

import coil3.Image

/**
 * Reading pixels out of a browser-decoded image means drawing it into a canvas
 * and pulling the buffer back, which is a second full decode of every picture
 * opened. The swatches are a nicety, not the page, so the web build goes without
 * them rather than paying for that.
 */
actual fun Image.palette(max: Int): List<Int> = emptyList()
