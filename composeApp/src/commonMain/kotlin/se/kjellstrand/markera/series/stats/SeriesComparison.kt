package se.kjellstrand.markera.series.stats

import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.total

/** How one saved series stands against the shooter's own earlier series (docs/coaching-plan.md, Serie page). */
sealed interface SeriesComparison {
    /** [points] under the average of the earlier series, [average] rounded. */
    data class Under(val points: Int, val average: Int) : SeriesComparison
    data class Over(val points: Int, val average: Int) : SeriesComparison
    data class OnAverage(val average: Int) : SeriesComparison

    /** The best total of the last [ComparisonThresholds.bestDays] days. */
    data class Best(val days: Int) : SeriesComparison
}

data class ComparisonThresholds(
    /** Earlier series of the caliber (same hole count) needed before any line is shown. */
    val minHistory: Int = 10,
    /** The average is taken over this many of the latest earlier series, as the low-series finding does. */
    val window: Int = 30,
    val bestDays: Int = 30,
    /** Earlier series inside [bestDays] needed before "best" means anything. */
    val bestMinRecent: Int = 3,
)

@OptIn(ExperimentalTime::class)
private fun SeriesDto.instant(): Instant? = runCatching { Instant.parse(timestamp) }.getOrNull()

/** Earlier series of the same caliber and hole count, oldest first; null when the series cannot be compared. */
@OptIn(ExperimentalTime::class)
private fun earlierOf(series: SeriesDto, all: List<SeriesDto>): List<SeriesDto>? {
    if (series.caliber.isBlank() || series.caliber == Caliber.NONE.label) return null
    val at = series.instant() ?: return null
    return all.filter {
        it.id != series.id && !it.deleted && it.caliber == series.caliber &&
            it.holes.size == series.holes.size && (it.instant()?.let { t -> t < at } == true)
    }.sortedBy { it.instant() }
}

/**
 * Pure: [series] against [all] (any caliber, any order). Null with fewer than
 * [ComparisonThresholds.minHistory] earlier series of the caliber, or for caliber "-" (those may
 * come from any gun). Says nothing about the shape of five holes, only the total.
 */
@OptIn(ExperimentalTime::class)
fun compareWithAverage(
    series: SeriesDto,
    all: List<SeriesDto>,
    thresholds: ComparisonThresholds = ComparisonThresholds(),
): SeriesComparison? {
    val earlier = earlierOf(series, all) ?: return null
    if (earlier.size < thresholds.minHistory) return null
    val at = series.instant() ?: return null
    val total = series.total()
    val recent = earlier.filter { it.instant()!! >= at - thresholds.bestDays.days }
    if (recent.size >= thresholds.bestMinRecent && total > recent.maxOf { it.total() }) return SeriesComparison.Best(thresholds.bestDays)
    val average = earlier.takeLast(thresholds.window).map { it.total() }.average().roundToInt()
    val diff = total - average
    return when {
        diff < 0 -> SeriesComparison.Under(-diff, average)
        diff > 0 -> SeriesComparison.Over(diff, average)
        else -> SeriesComparison.OnAverage(average)
    }
}

/**
 * The strongest finding that is about [series]' own day: the day has 3+ series of the caliber and
 * a low day or a wide group dated that day (judged against the history up to it), or an offset or
 * stretched group in that day's own holes. Null otherwise. The finding carries its numbers; the
 * words come from [advice]. [sights] are the per-caliber sight setups, as [insights] takes them:
 * once the sights were adjusted on or before that day, the offset is judged only on the series
 * taken after the adjustment, so the page agrees with the Insights tab its day link opens.
 */
@OptIn(ExperimentalTime::class)
fun dayFinding(
    series: SeriesDto,
    all: List<SeriesDto>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    thresholds: FindingThresholds = FindingThresholds(),
    sights: Map<String, SightSetup> = emptyMap(),
): Pair<LocalDate, Finding>? {
    val at = series.instant() ?: return null
    if (series.caliber.isBlank() || series.caliber == Caliber.NONE.label) return null
    fun day(t: Instant) = t.toLocalDateTime(zone).date
    val date = day(at)
    val wholeDay = all.filter {
        !it.deleted && it.caliber == series.caliber && it.holes.size == series.holes.size &&
            it.instant()?.let { t -> day(t) == date } == true
    }
    if (wholeDay.size < thresholds.lowDayMinSeries) return null
    val upToDay = (all.filter {
        !it.deleted && it.caliber == series.caliber && it.holes.size == series.holes.size &&
            it.instant()?.let { t -> day(t) <= date } == true
    } + series).distinctBy { it.id }
    val plotted = upToDay.plotSeries(StatsFilter(calibers = setOf(Caliber.fromLabel(series.caliber))))
    // Judged on the series that could be plotted, so the 3+ gate counts those too.
    if (plotted.count { day(it.at) == date } < thresholds.lowDayMinSeries) return null
    val dated = plotted.findings(zone, thresholds).filter {
        (it is Finding.LowDay && it.date == date) || (it is Finding.WideRoundGroup && it.date == date)
    }
    // As Insights over a window ending that day: an adjustment after the day leaves it as it was.
    val cutOff = sights[Caliber.fromLabel(series.caliber).label].offsetCutOff()?.takeIf { day(it) <= date }
    val own = plotted.filter { day(it.at) == date }.findings(zone, thresholds, cutOff).filter {
        it is Finding.OffsetGroup || it is Finding.StretchedGroup
    }
    return (dated + own).maxByOrNull { it.confidence }?.let { date to it }
}
