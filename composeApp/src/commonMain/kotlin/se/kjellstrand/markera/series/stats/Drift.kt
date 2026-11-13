package se.kjellstrand.markera.series.stats

import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The Drift tab's points: one per calendar day with series, the mean and the median
 * point of impact over every hit of that day's series together. Pure `commonMain`
 * maths — the screen only draws.
 */

/** Target mm from the centre, image axes (y down); [age] 0 = the oldest day shown, 1 = the newest. */
data class DayImpact(
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
 * [DayImpact] per local day, oldest first. The impact is the same maths as the
 * Tavla tab's + and × marks ([statistics]), just over one day's hits; a day whose
 * series hold no hit at all has no point and is left out.
 */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.dailyImpact(zone: TimeZone = TimeZone.currentSystemDefault()): List<DayImpact> {
    val days = groupBy {
        // kotlinx-datetime 0.6 has no conversion from kotlin.time.Instant.
        Instant.fromEpochMilliseconds(it.at.toEpochMilliseconds()).toLocalDateTime(zone).date
    }.mapNotNull { (date, group) ->
        val stats = group.statistics()?.takeIf { it.hitCount > 0 } ?: return@mapNotNull null
        DayImpact(
            date, stats.impactXMm, stats.impactYMm, stats.medianXMm, stats.medianYMm,
            group.size, stats.hitCount, age = 0f,
        )
    }.sortedBy { it.date }
    val last = days.lastIndex
    return days.mapIndexed { i, d -> d.copy(age = if (last <= 0) 0f else i.toFloat() / last) }
}
