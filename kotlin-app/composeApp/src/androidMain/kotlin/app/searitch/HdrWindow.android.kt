package app.searitch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.Image

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

/**
 * Ultra HDR JPEG is backwards compatible: SDR remains SDR, while Android 14+
 * displays its embedded gain map in HDR on capable displays. We request HDR
 * window mode only for a decoded image that actually contains a gain map, and
 * restore the exact previous window color mode when it leaves the composition.
 */
@Composable
actual fun PlatformHdrWindow(image: Image?, enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val originalMode = remember(activity) {
        activity?.window?.colorMode ?: ActivityInfo.COLOR_MODE_DEFAULT
    }
    val hasGainMap = remember(image) {
        Build.VERSION.SDK_INT >= 34 &&
            (image as? BitmapImage)?.bitmap?.hasGainmap() == true
    }
    val hdr = enabled && hasGainMap && Build.VERSION.SDK_INT >= 34

    SideEffect {
        if (Build.VERSION.SDK_INT >= 26 && activity != null) {
            activity.window.colorMode =
                if (hdr) ActivityInfo.COLOR_MODE_HDR else originalMode
        }
    }

    DisposableEffect(activity) {
        onDispose {
            if (Build.VERSION.SDK_INT >= 26 && activity != null) {
                activity.window.colorMode = originalMode
            }
        }
    }
}
