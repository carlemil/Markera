package se.kjellstrand.markera.series.stats

import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Drift tab's points: the mean and the median point of impact, either one per
 * calendar day over every hit of that day's series together ([dailyImpact]) or one per
 * series ([seriesImpact]). Pure `commonMain` maths — the screen only draws.
 */

/**
 * Target mm from the centre, image axes (y down); [date] is the day (or the series' day),
 * [age] 0 = the oldest point shown, 1 = the newest.
 */
data class DriftPoint(
    val date: LocalDate,
    val meanXMm: Double,
    val meanYMm: Double,
    val medianXMm: Double,
    val medianYMm: Double,
    val seriesCount: Int,
    val hitCount: Int,
    val age: Float,
)

/**
 * One [DriftPoint] per local day, oldest first. The impact is the same maths as the
 * Tavla tab's + and × marks ([statistics]), just over one day's hits; a day whose
 * series hold no hit at all has no point and is left out.
 */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.dailyImpact(zone: TimeZone = TimeZone.currentSystemDefault()): List<DriftPoint> =
    groupBy { it.localDate(zone) }
        .mapNotNull { (date, group) -> group.point(date) }
        .sortedBy { it.date }
        .withAges()

/** One [DriftPoint] per series with at least one hit, oldest first. */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.seriesImpact(zone: TimeZone = TimeZone.currentSystemDefault()): List<DriftPoint> =
    sortedBy { it.at }
        .mapNotNull { listOf(it).point(it.localDate(zone)) }
        .withAges()

/**
 * Each point averaged with the [window] - 1 before it (fewer at the start): one series is a
 * handful of shots, so its point of impact jumps around more than the shooter's aim does.
 * Dates, counts and ages stay each point's own.
 */
fun List<DriftPoint>.smoothed(window: Int = 3): List<DriftPoint> = mapIndexed { i, p ->
    val w = subList(maxOf(0, i - window + 1), i + 1)
    p.copy(
        meanXMm = w.sumOf { it.meanXMm } / w.size,
        meanYMm = w.sumOf { it.meanYMm } / w.size,
        medianXMm = w.sumOf { it.medianXMm } / w.size,
        medianYMm = w.sumOf { it.medianYMm } / w.size,
    )
}

@OptIn(ExperimentalTime::class)
private fun PlottedSeries.localDate(zone: TimeZone): LocalDate =
    // kotlinx-datetime 0.6 has no conversion from kotlin.time.Instant.
    Instant.fromEpochMilliseconds(at.toEpochMilliseconds()).toLocalDateTime(zone).date

private fun List<PlottedSeries>.point(date: LocalDate): DriftPoint? {
    val stats = statistics()?.takeIf { it.hitCount > 0 } ?: return null
    return DriftPoint(date, stats.impactXMm, stats.impactYMm, stats.medianXMm, stats.medianYMm, size, stats.hitCount, age = 0f)
}

private fun List<DriftPoint>.withAges(): List<DriftPoint> {
    val last = lastIndex
    return mapIndexed { i, p -> p.copy(age = if (last <= 0) 0f else i.toFloat() / last) }
}
