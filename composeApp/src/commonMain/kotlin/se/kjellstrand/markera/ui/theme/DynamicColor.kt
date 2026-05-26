package se.kjellstrand.markera.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
