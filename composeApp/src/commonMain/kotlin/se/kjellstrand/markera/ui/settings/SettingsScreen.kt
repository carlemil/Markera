package se.kjellstrand.markera.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.LocalOpenSettings
import androidx.compose.runtime.CompositionLocalProvider
import se.kjellstrand.markera.ui.stats.SectionHeader

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The UI languages; the names stay in their own language so they are findable from either. */
val LANGUAGES = listOf("en" to "English", "sv" to "Svenska")

/**
 * Device preferences, kept in the same store as the caliber and surviving sign-out.
 * [loaded] gates the first frame, so the saved language and theme apply from it.
 */
class AppSettings(private val store: BackendTokenStore, private val scope: CoroutineScope) {
    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    /** A [LANGUAGES] code; null follows the system. */
    private val _language = MutableStateFlow<String?>(null)
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            _theme.value = ThemeMode.entries.firstOrNull { it.name == store.readTheme() } ?: ThemeMode.SYSTEM
            _language.value = store.readLanguage()?.takeIf { code -> LANGUAGES.any { it.first == code } }
            _loaded.value = true
        }
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        scope.launch { store.writeTheme(mode.name) }
    }

    fun setLanguage(code: String?) {
        _language.value = code
        scope.launch { store.writeLanguage(code) }
    }
}

/**
 * Overrides the locale Compose resources resolve against (the pattern from the
 * Compose Multiplatform localization docs). [apply] sets the process-wide locale
 * (null = the system's) and must run outside composition; then provide it with a
 * `key(locale)` around the content, which is what makes the strings re-resolve.
 */
expect object LocalAppLocale {
    fun apply(value: String?)

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/** Status/navigation bar icons follow the in-app theme, not the system one. */
@Composable
expect fun SystemBarsForTheme(dark: Boolean)

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val theme by settings.theme.collectAsState()
    val language by settings.language.collectAsState()
    val system = stringResource(Res.string.settings_system)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            // Settings must not offer Settings: the menu holds only Back.
            CompositionLocalProvider(LocalOpenSettings provides null) {
                AppTopBar(title = stringResource(Res.string.settings), onBack = onBack)
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader(stringResource(Res.string.settings_language))
                Choice(system, language == null) { settings.setLanguage(null) }
                LANGUAGES.forEach { (code, name) ->
                    Choice(name, language == code) { settings.setLanguage(code) }
                }
                SectionHeader(stringResource(Res.string.settings_theme))
                Choice(system, theme == ThemeMode.SYSTEM) { settings.setTheme(ThemeMode.SYSTEM) }
                Choice(stringResource(Res.string.settings_theme_light), theme == ThemeMode.LIGHT) {
                    settings.setTheme(ThemeMode.LIGHT)
                }
                Choice(stringResource(Res.string.settings_theme_dark), theme == ThemeMode.DARK) {
                    settings.setTheme(ThemeMode.DARK)
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
