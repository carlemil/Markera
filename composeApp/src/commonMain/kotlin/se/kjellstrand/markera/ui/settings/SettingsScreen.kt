package se.kjellstrand.markera.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalUriHandler
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.ui.AccountActions
import se.kjellstrand.markera.ui.Segmented
import se.kjellstrand.markera.ui.suggestion.appVersion
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
import androidx.compose.runtime.staticCompositionLocalOf
import se.kjellstrand.markera.ui.SectionHeader

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The viewfinder guide's colour until the user picks one: 80% white. */
const val DEFAULT_GUIDE_COLOR: Long = 0xCCFFFFFF

/** The device preferences, provided by [se.kjellstrand.markera.ui.MarkeraApp]; null in previews. */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

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

    /** The live viewfinder guide's colour (circles, digits, cross) as ARGB. */
    private val _guideColor = MutableStateFlow(DEFAULT_GUIDE_COLOR)
    val guideColor: StateFlow<Long> = _guideColor.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            _theme.value = ThemeMode.entries.firstOrNull { it.name == store.readTheme() } ?: ThemeMode.SYSTEM
            _language.value = store.readLanguage()?.takeIf { code -> LANGUAGES.any { it.first == code } }
            _guideColor.value = store.readGuideColor() ?: DEFAULT_GUIDE_COLOR
            _loaded.value = true
        }
    }

    fun setGuideColor(argb: Long) {
        _guideColor.value = argb
        scope.launch { store.writeGuideColor(argb) }
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

/** The public privacy page the store listings link to as well. */
private const val PRIVACY_URL = "https://markera.duckdns.org/privacy"

@Composable
fun SettingsScreen(settings: AppSettings, seriesServices: SeriesServices, onBack: () -> Unit) {
    val theme by settings.theme.collectAsState()
    val language by settings.language.collectAsState()
    val auth by seriesServices.session.auth.collectAsState()
    val uriHandler = LocalUriHandler.current
    val system = stringResource(Res.string.settings_system_short)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            // Settings must not offer Settings: the menu holds only the suggestion box.
            CompositionLocalProvider(LocalOpenSettings provides null) {
                AppTopBar(title = stringResource(Res.string.settings), onBack = onBack)
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeader(stringResource(Res.string.settings_language))
                val languages = listOf<String?>(null) + LANGUAGES.map { it.first }
                Segmented(
                    options = listOf(system) + LANGUAGES.map { it.second },
                    selected = languages.indexOf(language).coerceAtLeast(0),
                    onSelect = { settings.setLanguage(languages[it]) },
                    modifier = Modifier.fillMaxWidth(),
                    fill = true,
                )
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(Res.string.settings_theme))
                Segmented(
                    options = listOf(
                        system,
                        stringResource(Res.string.settings_theme_light),
                        stringResource(Res.string.settings_theme_dark),
                    ),
                    selected = theme.ordinal,
                    onSelect = { settings.setTheme(ThemeMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                    fill = true,
                )
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(Res.string.settings_account))
                AccountActions(auth, seriesServices)
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(Res.string.settings_about))
                appVersion?.let {
                    Text(
                        stringResource(Res.string.settings_version, it),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = { uriHandler.openUri(PRIVACY_URL) }) {
                    Text(stringResource(Res.string.settings_privacy))
                }
            }
        }
    }
}
