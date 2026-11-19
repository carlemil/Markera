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
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
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
import se.kjellstrand.markera.series.stats.SightSetup
import se.kjellstrand.markera.series.stats.decodeSightSetups
import se.kjellstrand.markera.series.stats.encodeSightSetups
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.LocalOpenSettings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import se.kjellstrand.markera.ui.SectionHeader
import se.kjellstrand.markera.ui.stats.decodeDismissedInsights
import se.kjellstrand.markera.ui.stats.dismissedWith
import se.kjellstrand.markera.ui.stats.encodeDismissedInsights

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The hand the user shoots with; direction-based coaching advice mirrors for [LEFT]. */
enum class ShootingHand { RIGHT, LEFT }

/** The viewfinder guide's colour until the user picks one: 80% white. */
const val DEFAULT_GUIDE_COLOR: Long = 0xCCFFFFFF

/** The device preferences, provided by [se.kjellstrand.markera.ui.MarkeraApp]; null in previews. */
val LocalAppSettings = staticCompositionLocalOf<AppSettings?> { null }

/**
 * The UI languages: a BCP 47 tag (its language part names the `values-xx` folder) and the
 * name in that language, so each is findable whatever the app is showing. English and
 * Swedish first, the rest by name.
 */
val LANGUAGES = listOf(
    "en" to "English",
    "sv" to "Svenska",
    "cs" to "Čeština",
    "da" to "Dansk",
    "de" to "Deutsch",
    "et" to "Eesti",
    "es" to "Español",
    "fr" to "Français",
    "hr" to "Hrvatski",
    "it" to "Italiano",
    "lv" to "Latviešu",
    "lt" to "Lietuvių",
    "hu" to "Magyar",
    "nl" to "Nederlands",
    "nb" to "Norsk bokmål",
    "pl" to "Polski",
    "pt-BR" to "Português (Brasil)",
    "ro" to "Română",
    "sk" to "Slovenčina",
    "sl" to "Slovenščina",
    "fi" to "Suomi",
    "vi" to "Tiếng Việt",
    "tr" to "Türkçe",
    "el" to "Ελληνικά",
    "be" to "Беларуская",
    "bg" to "Български",
    "kk" to "Қазақ тілі",
    "sr" to "Српски",
    "uk" to "Українська",
    "he" to "עברית",
    "ar" to "العربية",
    "fa" to "فارسی",
    "hi" to "हिन्दी",
    "th" to "ไทย",
    "ko" to "한국어",
    "zh-Hans" to "简体中文",
)

/** The languages written right to left; picking one in-app mirrors the layout too. */
private val RTL_LANGUAGES = setOf("ar", "fa", "he")

/** The shooting-hand toggle's order, left to right on screen (not the enum's order). */
private val HAND_ORDER = listOf(ShootingHand.LEFT, ShootingHand.RIGHT)

/** Right to left for a picked [RTL_LANGUAGES] tag, null (keep the system's) otherwise. */
fun layoutDirectionFor(language: String?): LayoutDirection? = when {
    language == null -> null
    language.substringBefore('-') in RTL_LANGUAGES -> LayoutDirection.Rtl
    else -> LayoutDirection.Ltr
}

/** The Settings row that opens the language list. */
const val LANGUAGE_ROW_TAG = "settings_language_row"

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

    private val _shootingHand = MutableStateFlow(ShootingHand.RIGHT)
    val shootingHand: StateFlow<ShootingHand> = _shootingHand.asStateFlow()

    /** Off hides every coaching surface. */
    private val _insightsEnabled = MutableStateFlow(true)
    val insightsEnabled: StateFlow<Boolean> = _insightsEnabled.asStateFlow()

    /** The sight helper's setups by caliber label; see [SightSetup]. */
    private val _sightSetups = MutableStateFlow<Map<String, SightSetup>>(emptyMap())
    val sightSetups: StateFlow<Map<String, SightSetup>> = _sightSetups.asStateFlow()
    /** The Home insight cards dismissed, oldest first (see [dismissInsight]). */
    private val _dismissedInsights = MutableStateFlow<List<String>>(emptyList())
    val dismissedInsights: StateFlow<List<String>> = _dismissedInsights.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            _theme.value = ThemeMode.entries.firstOrNull { it.name == store.readTheme() } ?: ThemeMode.SYSTEM
            _language.value = store.readLanguage()?.takeIf { code -> LANGUAGES.any { it.first == code } }
            _guideColor.value = store.readGuideColor() ?: DEFAULT_GUIDE_COLOR
            _shootingHand.value =
                ShootingHand.entries.firstOrNull { it.name == store.readShootingHand() } ?: ShootingHand.RIGHT
            _insightsEnabled.value = store.readInsightsEnabled() ?: true
            _sightSetups.value = decodeSightSetups(store.readSightSetups())
            _dismissedInsights.value = decodeDismissedInsights(store.readDismissedInsights())
            _loaded.value = true
        }
    }

    fun setGuideColor(argb: Long) {
        _guideColor.value = argb
        scope.launch { store.writeGuideColor(argb) }
    }

    fun setShootingHand(hand: ShootingHand) {
        _shootingHand.value = hand
        scope.launch { store.writeShootingHand(hand.name) }
    }

    fun setInsightsEnabled(enabled: Boolean) {
        _insightsEnabled.value = enabled
        scope.launch { store.writeInsightsEnabled(enabled) }
    }

    /** The click value for [caliber] (label): a null [mmPerClick] clears it, the adjustment date stays. */
    fun setSightClicks(caliber: String, distanceM: Double, mmPerClick: Double?) =
        updateSight(caliber) { it.copy(distanceM = distanceM, mmPerClick = mmPerClick) }

    /** "I adjusted the sights" for [caliber] at [atMs] (now). */
    fun markSightsAdjusted(caliber: String, atMs: Long) = updateSight(caliber) { it.adjusted(atMs) }

    /** Undoes the latest adjustment: back to the one before it, or to none. */
    fun undoSightsAdjusted(caliber: String) = updateSight(caliber) { it.undoAdjusted() }

    private fun updateSight(caliber: String, change: (SightSetup) -> SightSetup) {
        val next = _sightSetups.value.toMutableMap()
        val setup = change(next[caliber] ?: SightSetup())
        if (setup == SightSetup()) next.remove(caliber) else next[caliber] = setup
        _sightSetups.value = next
        val encoded = encodeSightSetups(next)
        scope.launch { store.writeSightSetups(encoded) }
    }

    /** Hides the Home insight card with [key] until its data changes (a new key). */
    fun dismissInsight(key: String) {
        val keys = dismissedWith(_dismissedInsights.value, key)
        _dismissedInsights.value = keys
        scope.launch { store.writeDismissedInsights(encodeDismissedInsights(keys)) }
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
    val shootingHand by settings.shootingHand.collectAsState()
    val insightsEnabled by settings.insightsEnabled.collectAsState()
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
                LanguagePicker(
                    language = language,
                    systemLabel = stringResource(Res.string.settings_system),
                    onPick = settings::setLanguage,
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
                SectionHeader(stringResource(Res.string.settings_shooting_hand))
                // Left on the left, right on the right: physical sides, so not mirrored for
                // right-to-left languages either.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Segmented(
                        options = HAND_ORDER.map { hand ->
                            when (hand) {
                                ShootingHand.RIGHT -> stringResource(Res.string.settings_hand_right)
                                ShootingHand.LEFT -> stringResource(Res.string.settings_hand_left)
                            }
                        },
                        selected = HAND_ORDER.indexOf(shootingHand),
                        onSelect = { settings.setShootingHand(HAND_ORDER[it]) },
                        modifier = Modifier.fillMaxWidth(),
                        fill = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SectionHeader(stringResource(Res.string.settings_insights))
                Segmented(
                    options = listOf(
                        stringResource(Res.string.settings_insights_on),
                        stringResource(Res.string.settings_insights_off),
                    ),
                    selected = if (insightsEnabled) 0 else 1,
                    onSelect = { settings.setInsightsEnabled(it == 0) },
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
