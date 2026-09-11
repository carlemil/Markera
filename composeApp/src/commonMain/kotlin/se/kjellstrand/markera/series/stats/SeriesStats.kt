package se.kjellstrand.markera.series.stats

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.centre
import se.kjellstrand.markera.series.ring
import se.kjellstrand.markera.series.total
import se.kjellstrand.markera.vision.targetOffsetMm

/**
 * Turns saved series into a plottable hit cloud in target millimetres plus the
 * group statistics under it. Pure `commonMain` maths — the screen only draws.
 *
 * Only series that carry [SeriesDto.geometry] can be plotted at all, so
 * everything scanned before the geometry was recorded is simply invisible here.
 */

/** Quick date ranges for the filter row; [ALL] and [CUSTOM] set no bounds themselves. */
@OptIn(ExperimentalTime::class)
enum class DatePreset(private val days: Int?) {
    WEEK(7),
    MONTH(30),
    YEAR(365),
    ALL(null),
    CUSTOM(null);

    /** `from to to` for this preset — open-ended above, and both null for [ALL]/[CUSTOM]. */
    fun range(now: Instant): Pair<Instant?, Instant?> =
        days?.let { (now - it.days) to null } ?: (null to null)
}

@OptIn(ExperimentalTime::class)
data class StatsFilter(
    /** null = every caliber. */
    val caliber: Caliber? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    /** Exactly this many holes; null = any count. The usual series is five shots. */
    val hits: Int? = null,
)

/** One hole placed in the target plane, mm from the centre, image axes (y down). */
data class PlottedHit(val xMm: Double, val yMm: Double, val ring: Int, val innerTen: Boolean)

/** [age] 0 = the oldest series in the selection, 1 = the newest. */
data class PlottedSeries(val series: SeriesDto, val hits: List<PlottedHit>, val age: Float)

/**
 * The series [filter] keeps, oldest first, each un-projected to target mm.
 * Dropped: no geometry, an unpositioned hole, the wrong hole count, another
 * caliber, a timestamp outside the window — or one that won't parse at all.
 */
@OptIn(ExperimentalTime::class)
fun List<SeriesDto>.plotSeries(filter: StatsFilter): List<PlottedSeries> {
    val kept = mapNotNull { series ->
        val geometry = series.geometry ?: return@mapNotNull null
        if (filter.hits != null && series.holes.size != filter.hits) return@mapNotNull null
        if (filter.caliber != null && Caliber.fromLabel(series.caliber) != filter.caliber) {
            return@mapNotNull null
        }
        val at = runCatching { Instant.parse(series.timestamp) }.getOrNull() ?: return@mapNotNull null
        if (filter.from?.let { at < it } == true || filter.to?.let { at > it } == true) {
            return@mapNotNull null
        }
        val centre = geometry.centre()
        val ring = geometry.ring()
        val hits = series.holes.map { hole ->
            val x = hole.x ?: return@mapNotNull null
            val y = hole.y ?: return@mapNotNull null
            val (xMm, yMm) = targetOffsetMm(x.toFloat(), y.toFloat(), centre, ring)
            PlottedHit(xMm, yMm, hole.ring, hole.innerTen)
        }
        at to PlottedSeries(series, hits, 0f)
    }.sortedBy { it.first }
    val last = kept.size - 1
    return kept.mapIndexed { i, (_, plotted) ->
        plotted.copy(age = if (last <= 0) 0f else i.toFloat() / last)
    }
}

/** All distances in mm. */
data class SeriesStatistics(
    val seriesCount: Int,
    val hitCount: Int,
    val meanDistanceMm: Double,
    val meanPairwiseMm: Double,
    val meanScore: Double,
    val meanGroupSizeMm: Double,
    val meanRadiusMm: Double,
    val radialSdMm: Double,
    val impactXMm: Double,
    val impactYMm: Double,
    val medianXMm: Double,
    val medianYMm: Double,
    val tensShare: Double,
)

/**
 * Group measurements over the plotted selection, or null when it is empty.
 * Per-hit means (distance, impact, tens share) run over every hit; the
 * per-series means (pairwise spread, group size, mean radius, score) average the
 * series, and a one-hole series has no pair so it sits out those three.
 * [SeriesStatistics.meanRadiusMm] and [SeriesStatistics.radialSdMm] measure the
 * spread around the hits' own mean point, so they ignore the target centre entirely.
 */
fun List<PlottedSeries>.statistics(): SeriesStatistics? {
    if (isEmpty()) return null
    val hits = flatMap { it.hits }
    val withPairs = filter { it.hits.size >= 2 }
    val pairwise = withPairs.map { it.hits.pairDistances() }
    val impactX = hits.map { it.xMm }.mean()
    val impactY = hits.map { it.yMm }.mean()
    return SeriesStatistics(
        seriesCount = size,
        hitCount = hits.size,
        meanDistanceMm = hits.map { hypot(it.xMm, it.yMm) }.mean(),
        meanPairwiseMm = pairwise.map { it.mean() }.mean(),
        meanScore = map { it.series.total().toDouble() }.mean(),
        meanGroupSizeMm = pairwise.map { it.max() }.mean(),
        meanRadiusMm = withPairs.map { it.hits.radiiFromOwnMean().mean() }.mean(),
        radialSdMm = sqrt(hits.map { hypot(it.xMm - impactX, it.yMm - impactY).let { d -> d * d } }.mean()),
        impactXMm = impactX,
        impactYMm = impactY,
        medianXMm = hits.map { it.xMm }.median(),
        medianYMm = hits.map { it.yMm }.median(),
        tensShare = if (hits.isEmpty()) 0.0 else {
            hits.count { it.ring == 10 || it.innerTen }.toDouble() / hits.size
        },
        // Sorted oldest first and max/minByOrNull keep the first extreme, so ties go to the earliest.
    )
}

/** Every hole-to-hole distance within one series; empty for a single hole. */
private fun List<PlottedHit>.pairDistances(): List<Double> {
    val out = mutableListOf<Double>()
    for (i in indices) {
        for (j in i + 1 until size) {
            out += hypot(this[i].xMm - this[j].xMm, this[i].yMm - this[j].yMm)
        }
    }
    return out
}

/** Each hole's distance to the group's own mean point — no target centre involved. */
private fun List<PlottedHit>.radiiFromOwnMean(): List<Double> {
    val cx = map { it.xMm }.mean()
    val cy = map { it.yMm }.mean()
    return map { hypot(it.xMm - cx, it.yMm - cy) }
}

/** Mean, with an empty selection reading 0 rather than NaN. */
private fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

/** Median (even count → the mean of the two middle values), 0 when empty. */
private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val s = sorted()
    return if (size % 2 == 1) s[size / 2] else (s[size / 2 - 1] + s[size / 2]) / 2.0
}
