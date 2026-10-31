package app.searitch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // settings + feed cache need a place to live before the first composition
        PlatformStore.attach(applicationContext)
        enableEdgeToEdge()
        setContent { App() }
    }
}
