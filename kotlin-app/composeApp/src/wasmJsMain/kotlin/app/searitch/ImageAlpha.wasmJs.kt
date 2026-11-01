package app.searitch

import coil3.Image

/** The browser keeps the alpha channel in the canvas; there is nothing to ask. */
actual fun Image.decodedAlpha(): Boolean? = null
