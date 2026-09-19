package se.kjellstrand.markera.ui.settings

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.util.Locale

actual object LocalAppLocale {
    // Object init = first use, before any override.
    private val system: Locale = Locale.getDefault()

    private fun locale(value: String?) = value?.let(Locale::forLanguageTag) ?: system

    actual fun apply(value: String?) = Locale.setDefault(locale(value))

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val base = LocalConfiguration.current
        return LocalConfiguration provides remember(base, value) {
            Configuration(base).apply { setLocale(locale(value)) }
        }
    }
}

@Composable
actual fun SystemBarsForTheme(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
