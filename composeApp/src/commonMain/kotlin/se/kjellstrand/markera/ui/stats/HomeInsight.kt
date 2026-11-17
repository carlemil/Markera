package se.kjellstrand.markera.ui.stats

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.stats.AdviceText
import se.kjellstrand.markera.series.stats.Finding
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.SightSetup
import se.kjellstrand.markera.series.stats.StatsFilter
import se.kjellstrand.markera.series.stats.advice
import se.kjellstrand.markera.series.stats.byCaliber
import se.kjellstrand.markera.series.stats.impactWords
import se.kjellstrand.markera.series.stats.insights
import se.kjellstrand.markera.series.stats.plotSeries
import se.kjellstrand.markera.series.stats.sector
import se.kjellstrand.markera.ui.settings.ShootingHand
import kotlinx.datetime.Instant as CalendarInstant

/**
 * Home's coaching card (docs/coaching-plan.md, card 6): one finding of the last [HOME_INSIGHT_DAYS]
 * days, the [day] Insights opens on when it is tapped, and the [key] a dismissal is stored under.
 */
data class HomeInsight(
    val finding: Finding,
    val caliber: Caliber,
    /** Type, caliber and the caliber's newest series: a new series there makes a new key. */
    val key: String,
    /** The caliber and period Insights finds this very finding in again. */
    val day: InsightsDay,
)

/** The card looks back over today and the six days before it. */
const val HOME_INSIGHT_DAYS = 7

/** Dismissed keys kept; room for every kind in several calibers, and each new series makes the old ones stale. */
const val MAX_DISMISSED_INSIGHTS = 50

/**
 * True for the findings judged against the shooter's history rather than the group itself: a low
 * series or day, a wide day and the trend. Any other type (a group finding, the session trend
 * over this week's days, or one added later) is judged on the week alone.
 */
fun Finding.judgedOnHistory(): Boolean =
    this is Finding.LowSeries || this is Finding.LowDay || this is Finding.WideRoundGroup || this is Finding.Trend

/**
 * Every card candidate, strongest ([Finding.confidence]) first; Home shows the first one not
 * dismissed ([firstShown]). Only calibers with a series in the last [HOME_INSIGHT_DAYS] local days
 * take part. Group findings come from Insights over those days; history-judged ones from Insights
 * over the caliber's whole history through today (they say how the newest series or day compares).
 * Both run [insights] on the very series the Statistics tab plots for the [HomeInsight.day] it opens
 * on, so the tapped finding is always there; [sights] too, so an offset the user has since corrected
 * the sights for is judged only on the series after that, as Insights judges it. Pure, so it is unit-tested.
 */
@OptIn(ExperimentalTime::class)
fun List<SeriesDto>.homeInsights(
    now: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    sights: Map<String, SightSetup> = emptyMap(),
): List<HomeInsight> {
    val today = localDate(now, zone)
    val weekStart = today.minus(HOME_INSIGHT_DAYS - 1, DateTimeUnit.DAY)
    val (_, endOfToday) = InsightsDay(today, Caliber.NONE).window(zone)
    val candidates = mutableListOf<HomeInsight>()
    plotSeries(StatsFilter(to = endOfToday)).byCaliber().forEach { (caliber, group) ->
        val newest = group.last()
        if (localDate(newest.at, zone) < weekStart) return@forEach
        val stamp = newest.at.toEpochMilliseconds()
        val week = InsightsDay(today, caliber, from = weekStart)
        val first = localDate(group.first().at, zone)
        val history = if (first < weekStart) InsightsDay(today, caliber, from = first) else week
        fun add(day: InsightsDay, keep: (Finding) -> Boolean) {
            findingsIn(day, zone, sights).filter(keep).forEach {
                candidates += HomeInsight(it, caliber, "${it.kind()}|${caliber.label}|$stamp", day)
            }
        }
        add(week) { !it.judgedOnHistory() }
        add(history) { it.judgedOnHistory() }
    }
    // Stable: a tie keeps the caliber order.
    return candidates.sortedByDescending { it.finding.confidence }
}

/** The card Home shows: the strongest candidate whose key was not dismissed, or none. */
fun List<HomeInsight>.firstShown(dismissed: Collection<String>): HomeInsight? = firstOrNull { it.key !in dismissed }

/** [dismissed] with [key] added as the newest, the oldest dropped past [MAX_DISMISSED_INSIGHTS]. */
fun dismissedWith(dismissed: List<String>, key: String): List<String> =
    (dismissed.filter { it != key } + key).takeLast(MAX_DISMISSED_INSIGHTS)

/** The dismissed keys as stored: one per line (a key never holds a line break). */
fun encodeDismissedInsights(keys: List<String>): String = keys.joinToString("\n")

fun decodeDismissedInsights(text: String?): List<String> =
    text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

/** The card's one line: what the finding says, named by caliber. A type without a line of its own uses its advice summary. */
fun HomeInsight.headline(hand: ShootingHand): AdviceText {
    val c = caliber.label
    return when (val f = finding) {
        is Finding.OffsetGroup -> AdviceText(
            Res.string.home_insight_offset,
            listOf(c, f.distanceMm.roundToInt(), impactWords[sector(f.bearingDeg)]),
        )
        is Finding.StretchedGroup -> AdviceText(
            when (f.orientation) {
                Finding.Orientation.VERTICAL -> Res.string.home_insight_stretch_vertical
                Finding.Orientation.HORIZONTAL -> Res.string.home_insight_stretch_horizontal
                Finding.Orientation.DIAGONAL -> Res.string.home_insight_stretch_diagonal
            },
            listOf(c, ((f.elongation - 1) * 100).roundToInt()),
        )
        is Finding.Fliers -> AdviceText(Res.string.home_insight_fliers, listOf(c, f.seriesWithFliers, f.seriesCount))
        is Finding.WideRoundGroup -> AdviceText(
            Res.string.home_insight_wide,
            listOf(c, f.date, ((f.ratio - 1) * 100).roundToInt()),
        )
        is Finding.LowSeries -> AdviceText(
            Res.string.home_insight_low_series,
            listOf(c, f.total, f.historyMeanTotal.roundToInt()),
        )
        is Finding.LowDay -> AdviceText(
            Res.string.home_insight_low_day,
            listOf(c, f.date, f.meanTotal.roundToInt(), f.historyMeanTotal.roundToInt()),
        )
        is Finding.Trend -> AdviceText(
            if (f.improving) Res.string.home_insight_trend_up else Res.string.home_insight_trend_down,
            listOf(c, abs(f.changeOverSpan).roundToInt().coerceAtLeast(1), (f.spanDays / 7).roundToInt().coerceAtLeast(1)),
        )
        is Finding.SessionTrend -> AdviceText(
            if (f.falling) Res.string.home_insight_session_down else Res.string.home_insight_session_up,
            listOf(c, f.daysCounted, f.daysChecked),
        )
        else -> advice(f, hand).summary
    }
}

/** A stable name for the key: class names do not survive R8 from one build to the next. */
private fun Finding.kind(): String = when (this) {
    is Finding.OffsetGroup -> "offset"
    is Finding.StretchedGroup -> "stretch"
    is Finding.Fliers -> "fliers"
    is Finding.WideRoundGroup -> "wide"
    is Finding.LowSeries -> "low-series"
    is Finding.LowDay -> "low-day"
    is Finding.Trend -> "trend"
    is Finding.SessionTrend -> "session"
    // Not the class name: R8 renames it from one build to the next.
    else -> "finding"
}

/** The findings the Insights tab shows for [day]: its window and caliber, through [insights]. */
@OptIn(ExperimentalTime::class)
private fun List<SeriesDto>.findingsIn(day: InsightsDay, zone: TimeZone, sights: Map<String, SightSetup>): List<Finding> {
    val (from, to) = day.window(zone)
    val plotted: List<PlottedSeries> = plotSeries(StatsFilter(calibers = setOf(day.caliber), from = from, to = to))
    return plotted.insights(zone, sights = sights, viewEnd = to).sections.firstOrNull()?.findings ?: emptyList()
}

@OptIn(ExperimentalTime::class)
private fun localDate(at: Instant, zone: TimeZone): LocalDate =
    // kotlinx-datetime 0.6 has no conversion from kotlin.time.Instant.
    CalendarInstant.fromEpochMilliseconds(at.toEpochMilliseconds()).toLocalDateTime(zone).date
