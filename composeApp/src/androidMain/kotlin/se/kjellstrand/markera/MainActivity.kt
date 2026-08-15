package se.kjellstrand.markera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import se.kjellstrand.markera.ui.AppNavHost
import se.kjellstrand.markera.ui.theme.MarkeraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkeraTheme {
                AppNavHost()
            }
        }
    }
}
