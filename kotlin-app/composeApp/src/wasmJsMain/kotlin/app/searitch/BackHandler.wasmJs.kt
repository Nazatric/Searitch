package app.searitch

import androidx.compose.runtime.Composable

// The browser owns its own Back button; the in-app ‹ button pops the stack.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
