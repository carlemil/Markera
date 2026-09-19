package se.kjellstrand.markera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Markera's own green-accented schemes, dark and light (chosen in Settings);
// no wallpaper-driven dynamic colour, so the brand reads the same on every device.
// The photo overlays keep their fixed colours in both: they sit on the target, not the theme.
private val MarkeraDarkColors = darkColorScheme(
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
    outlineVariant = MarkeraOutlineVariant,
)

private val MarkeraLightColors = lightColorScheme(
    primary = MarkeraGreenLight,
    onPrimary = Color.White,
    primaryContainer = MarkeraGreenLightContainer,
    onPrimaryContainer = MarkeraGreenLightOnContainer,
    secondary = MarkeraGreenLight,
    onSecondary = Color.White,
    secondaryContainer = MarkeraLightSurfaceVariant,
    onSecondaryContainer = MarkeraLightOnSurface,
    background = MarkeraLightBackground,
    onBackground = MarkeraLightOnSurface,
    surface = MarkeraLightSurface,
    onSurface = MarkeraLightOnSurface,
    surfaceVariant = MarkeraLightSurfaceVariant,
    onSurfaceVariant = MarkeraLightOnSurfaceVariant,
    outlineVariant = MarkeraLightOutlineVariant,
)

// One corner scale: chips/boxes small or medium, cards and dialogs large.
// extraLarge = large because AlertDialog and DatePickerDialog read extraLarge.
private val MarkeraShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun MarkeraTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) MarkeraDarkColors else MarkeraLightColors,
        typography = Typography,
        shapes = MarkeraShapes,
        content = content,
    )
}
