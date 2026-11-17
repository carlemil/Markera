package se.kjellstrand.markera.series.stats

import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.total

/** Home's strip: the series of the last few days, their average total and the best one. */
data class WeekSummary(val count: Int, val average: Double, val best: Int)

/**
 * Over the series shot in the [days] days up to [now]; null when there is none, so the
 * strip is simply not there. A series whose timestamp does not parse is left out.
 */
@OptIn(ExperimentalTime::class)
fun List<SeriesDto>.weekSummary(now: Instant, days: Int = 7): WeekSummary? {
    val from = now - days.days
    val totals = filter { s ->
        val at = runCatching { Instant.parse(s.timestamp) }.getOrNull() ?: return@filter false
        at >= from && at <= now
    }.map { it.total() }
    if (totals.isEmpty()) return null
    return WeekSummary(totals.size, totals.average(), totals.max())
}

/** The mean total per series, for a day's header in Historik. */
fun List<SeriesDto>.averageTotal(): Double = if (isEmpty()) 0.0 else sumOf { it.total() }.toDouble() / size
