package app.searitch

import androidx.compose.runtime.Composable
import coil3.Image

/** The browser itself handles HDR images and display capability on Kotlin/Wasm. */
@Composable
actual fun PlatformHdrWindow(image: Image?, enabled: Boolean) = Unit
