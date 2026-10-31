package app.searitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    val enabled = LocalSettings.current.value.haptics
    return remember(view, feedback, enabled) {
        object : Haptics {
            override fun tap() {
                if (!enabled) return
                runCatching { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
            }

            override fun press() {
                if (!enabled) return
                runCatching { feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
        }
    }
}
