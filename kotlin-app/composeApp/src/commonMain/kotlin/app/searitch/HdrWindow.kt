package app.searitch

import androidx.compose.runtime.Composable
import coil3.Image

/**
 * Request full HDR output only while an actual gain-map image is visible.
 * Android checks the decoded Coil image and restores the previous window mode
 * automatically. Wasm/web keeps using the browser's native HDR image pipeline.
 */
@Composable
expect fun PlatformHdrWindow(image: Image?, enabled: Boolean)
