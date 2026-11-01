package app.searitch

import coil3.BitmapImage
import coil3.Image

/**
 * Android's decoder records whether the bitmap it produced has an alpha channel:
 * false for a JPEG, true for a PNG that actually uses transparency. An opaque PNG
 * reports false, which is the answer we want — it covers whatever is behind it.
 */
actual fun Image.decodedAlpha(): Boolean? =
    (this as? BitmapImage)?.bitmap?.hasAlpha()
