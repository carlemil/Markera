package se.kjellstrand.markera.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.ui.stats.InsightsDay

/**
 * One [Screen] as saved state. Flat, so the JSON stays small: only the fields the
 * screen needs are set. [SeriesDto.local] is transient and does not travel; the
 * Serie page reads the cached row by id anyway.
 */
@Serializable
private data class SavedScreen(
    val name: String,
    val series: SeriesDto? = null,
    val ids: List<Long> = emptyList(),
    val date: String? = null,
    val from: String? = null,
    val caliber: String? = null,
    val caliberMm: Float? = null,
)

private val backStackJson = Json { ignoreUnknownKeys = true }

/** Null for a screen that cannot be saved: [Screen.Import] holds picked images, not data. */
private fun Screen.toSaved(): SavedScreen? = when (this) {
    Screen.Home -> SavedScreen("home")
    Screen.FreeMarking -> SavedScreen("freeMarking")
    Screen.History -> SavedScreen("history")
    Screen.Statistics -> SavedScreen("statistics")
    Screen.Settings -> SavedScreen("settings")
    Screen.Suggestion -> SavedScreen("suggestion")
    is Screen.StatisticsDay -> SavedScreen(
        "statisticsDay",
        date = day.date.toString(),
        from = day.from.toString(),
        caliber = day.caliber.label,
        caliberMm = day.caliber.diameterMm,
    )
    is Screen.SeriesDetail -> SavedScreen("seriesDetail", series = series, ids = ids)
    is Screen.Import -> null
}

private fun SavedScreen.toScreen(): Screen? = when (name) {
    "home" -> Screen.Home
    "freeMarking" -> Screen.FreeMarking
    "history" -> Screen.History
    "statistics" -> Screen.Statistics
    "settings" -> Screen.Settings
    "suggestion" -> Screen.Suggestion
    "statisticsDay" -> {
        val day = LocalDate.parse(date ?: return null)
        InsightsDay(
            date = day,
            caliber = Caliber(caliber ?: return null, caliberMm ?: return null),
            from = from?.let(LocalDate::parse) ?: day,
        ).let(Screen::StatisticsDay)
    }
    "seriesDetail" -> Screen.SeriesDetail(series ?: return null, ids)
    else -> null
}

/**
 * The back stack as text, for saved state. Cut at the first screen that cannot be
 * saved (an import), together with everything above it: those were opened from it.
 */
internal fun encodeBackStack(stack: List<Screen>): String =
    backStackJson.encodeToString(stack.map { it.toSaved() }.takeWhile { it != null }.filterNotNull())

/** Never throws, never empty: unreadable text, or a stack not starting at Home, is just Home. */
internal fun decodeBackStack(text: String): List<Screen> {
    val screens = try {
        backStackJson.decodeFromString<List<SavedScreen>>(text).map { it.toScreen() }.takeWhile { it != null }.filterNotNull()
    } catch (_: Exception) {
        emptyList()
    }
    return screens.takeIf { it.firstOrNull() == Screen.Home } ?: listOf(Screen.Home)
}

/**
 * Saves the back stack across activity recreation and process death. [survivor] is the
 * stack that outlived a configuration change in [AppViewModel] (it keeps an import,
 * which text cannot); only after process death is the stack rebuilt from the text.
 */
internal fun backStackSaver(survivor: () -> MutableState<List<Screen>>?): Saver<MutableState<List<Screen>>, String> =
    Saver(
        save = { encodeBackStack(it.value) },
        restore = { survivor() ?: mutableStateOf(decodeBackStack(it)) },
    )

/**
 * The key a back-stack entry's saved UI state (History's scroll, say) is kept under:
 * its depth plus what it shows, so a replaced or re-pushed entry starts fresh.
 */
internal fun backStackKey(index: Int, screen: Screen): String = "$index:" + when (screen) {
    Screen.Home -> "home"
    Screen.FreeMarking -> "freeMarking"
    Screen.History -> "history"
    Screen.Statistics -> "statistics"
    Screen.Settings -> "settings"
    Screen.Suggestion -> "suggestion"
    is Screen.StatisticsDay -> "statisticsDay:${screen.day.caliber.label}:${screen.day.from}:${screen.day.date}"
    is Screen.SeriesDetail -> "seriesDetail:${screen.series.id}"
    is Screen.Import -> "import:${screen.hashCode()}"
}
