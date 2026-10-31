package app.searitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No haptic engine in the browser; keep the same no-op shape. */
@Composable
actual fun rememberHaptics(): Haptics = remember {
    object : Haptics {
        override fun tap() = Unit
        override fun press() = Unit
    }
}
