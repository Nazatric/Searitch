package app.searitch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * Edge-to-edge chrome that follows the chosen materials: the hide shows through
 * the bars, and the icon colour flips so it stays legible on light leather
 * (oxblood/olive stay light-on-dark, the pale tones get dark icons).
 */
@Composable
actual fun PlatformSystemBars(darkIcons: Boolean, scrim: Long) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return
    SideEffect {
        val window = activity.window
        @Suppress("DEPRECATION")
        window.navigationBarColor = scrim.toInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkIcons) bars else 0, bars)
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                if (darkIcons) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
            @Suppress("DEPRECATION")
            run { view.systemUiVisibility = flags }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    if (darkIcons) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
