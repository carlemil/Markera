package se.kjellstrand.markera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

// Start from the Material 3 defaults, then strengthen the few styles the app
// leans on: the big score total (displayMedium/Small) and section titles.
private val base = Typography()

val Typography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)
