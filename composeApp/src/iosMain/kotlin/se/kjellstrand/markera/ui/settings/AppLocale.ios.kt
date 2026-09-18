package se.kjellstrand.markera.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

actual object LocalAppLocale {
    private const val LANG_KEY = "AppleLanguages"

    // Dropping the override first, so a language chosen last run is not taken for the system's.
    private val default: String by lazy {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(LANG_KEY)
        NSLocale.preferredLanguages.first() as String
    }
    private val LocalLocale = staticCompositionLocalOf { default }

    actual val current: String
        @Composable get() = LocalLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val new = value ?: default
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(LANG_KEY)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(listOf(new), LANG_KEY)
        }
        return LocalLocale.provides(new)
    }
}

// The status bar follows the system appearance on iOS; the in-app theme leaves it be.
@Composable
actual fun SystemBarsForTheme(dark: Boolean) = Unit
