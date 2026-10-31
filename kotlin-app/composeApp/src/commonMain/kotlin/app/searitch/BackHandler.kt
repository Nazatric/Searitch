package app.searitch

import androidx.compose.runtime.Composable

/**
 * System back (Android back button / gesture). Without this, Back would close
 * the app from any screen instead of popping the stack.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
