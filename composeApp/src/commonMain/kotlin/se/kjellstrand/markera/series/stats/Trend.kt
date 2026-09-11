package se.kjellstrand.markera.series.stats

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.series.Caliber

/**
 * The measurements under the target, over time: every row that [SeriesStatistics]
 * computes for the whole selection is recomputed per series (or per time bucket)
 * so the Trend tab can chart it. Pure `commonMain` maths — the screen only draws.
 */

/** One chartable row of the Statistik measurements. */
enum class Metric {
    MEAN_DISTANCE, MEAN_PAIRWISE, GROUP_SIZE, MEAN_RADIUS, RADIAL_SD, IMPACT_X, IMPACT_Y, SCORE, TENS_SHARE,
}

/** The row's number; tens share as a percentage so it charts like the others. */
fun SeriesStatistics.value(metric: Metric): Double = when (metric) {
    Metric.MEAN_DISTANCE -> meanDistanceMm
    Metric.MEAN_PAIRWISE -> meanPairwiseMm
    Metric.GROUP_SIZE -> meanGroupSizeMm
    Metric.MEAN_RADIUS -> meanRadiusMm
    Metric.RADIAL_SD -> radialSdMm
    Metric.IMPACT_X -> impactXMm
    Metric.IMPACT_Y -> impactYMm
    Metric.SCORE -> meanScore
    Metric.TENS_SHARE -> tensShare * 100
}

/** How the x axis groups series: one point each, or one per calendar day/week/month. */
enum class Bucket { SERIES, DAY, WEEK, MONTH }

/** [at] is the series' own time, or the bucket's local start; [seriesCount] what went into it. */
data class TrendPoint(val at: Instant, val value: Double, val seriesCount: Int)

/**
 * [metric] per [bucket], oldest first. A bucket's value is the group statistic over
 * its series — the same maths as the rows under the target, just over fewer series.
 */
fun List<PlottedSeries>.trend(
    metric: Metric,
    bucket: Bucket,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<TrendPoint> =
    // plotSeries already dropped every series whose timestamp does not parse.
    groupBy { bucketStart(Instant.parse(it.series.timestamp), bucket, zone) }
        .map { (start, group) -> TrendPoint(start, group.statistics()!!.value(metric), group.size) }
        .sortedBy { it.at }

/** One [trend] per caliber, in [Caliber] order — the chart's fixed colour order. */
fun List<PlottedSeries>.trendByCaliber(
    metric: Metric,
    bucket: Bucket,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Map<Caliber, List<TrendPoint>> =
    groupBy { Caliber.fromLabel(it.series.caliber) }
        .entries
        .sortedBy { it.key.ordinal }
        .associate { (caliber, group) -> caliber to group.trend(metric, bucket, zone) }

private fun bucketStart(at: Instant, bucket: Bucket, zone: TimeZone): Instant {
    if (bucket == Bucket.SERIES) return at
    val date = at.toLocalDateTime(zone).date
    val start = when (bucket) {
        Bucket.DAY -> date
        Bucket.WEEK -> date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY) // Monday
        Bucket.MONTH -> LocalDate(date.year, date.monthNumber, 1)
        Bucket.SERIES -> at.toLocalDateTime(zone).date // unreachable
    }
    return start.atStartOfDayIn(zone)
}
