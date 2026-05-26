package se.kjellstrand.markera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import se.kjellstrand.markera.ui.theme.MarkeraTheme

@Composable
fun App() {
    MarkeraTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Markera — iOS UI not yet implemented",
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}
