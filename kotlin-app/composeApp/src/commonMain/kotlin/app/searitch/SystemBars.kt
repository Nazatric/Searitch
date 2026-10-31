package app.searitch

import androidx.compose.runtime.Composable

/**
 * Tints the system chrome to match the chosen materials. [darkIcons] is true
 * when the leather is light enough for dark status-bar icons.
 */
@Composable
expect fun PlatformSystemBars(darkIcons: Boolean, scrim: Long)
