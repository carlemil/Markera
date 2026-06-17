package se.kjellstrand.markera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Markera is a camera-first app: a single deliberate dark, green-accented
// scheme (no wallpaper-driven dynamic colour, no light variant) so the brand
// and the live preview read consistently on every device.
private val MarkeraColors = darkColorScheme(
    primary = MarkeraGreen,
    onPrimary = Color.Black,
    primaryContainer = MarkeraGreenContainer,
    onPrimaryContainer = MarkeraGreenOnContainer,
    secondary = MarkeraGreenDim,
    onSecondary = Color.Black,
    secondaryContainer = MarkeraSurfaceVariant,
    onSecondaryContainer = MarkeraOnSurface,
    background = MarkeraBackground,
    onBackground = MarkeraOnSurface,
    surface = MarkeraSurface,
    onSurface = MarkeraOnSurface,
    surfaceVariant = MarkeraSurfaceVariant,
    onSurfaceVariant = MarkeraOnSurfaceVariant,
)

@Composable
fun MarkeraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MarkeraColors,
        typography = Typography,
        content = content,
    )
}
