package se.kjellstrand.markera.series.stats

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.total

/**
 * The coaching findings engine (docs/coaching-plan.md, "The findings"): what the saved series of
 * ONE caliber (the caliber stands in for the gun) say strongly enough to be worth telling the
 * shooter. Pure `commonMain` maths, no text and no shooting hand: the advice layer words it.
 *
 * Every finding carries the numbers behind it and a [Finding.confidence] in (0, 1]. Too little
 * data, or evidence under a threshold, gives no finding at all, never a weak one. Hits are target
 * mm with y down, the [GroupShape] conventions (bearing clockwise from 12 o'clock, major axis
 * counter-clockwise from horizontal with y up).
 *
 * ## Thresholds and why ([FindingThresholds] holds them)
 *
 * No real history could be read when this was written (no session, no phone on adb, the server
 * query refused), so the values were tuned by Monte Carlo on round normal groups (30 mm SD per
 * axis, five-hole series) — the shooter with nothing to fix, who must get no finding — plus
 * synthetic groups with each fault planted. Re-check them on real series before the advice ships.
 *
 * - **Offset**: the card's 2 standard errors fire on 13.5% of perfectly centred groups (the MPI's
 *   distance in SE units is Rayleigh, tail exp(-k²/2)); 3 SE fires on about 1%. Plus at least
 *   10 mm, so a large, precise history does not report a trivial offset, and 15+ hits. A day
 *   "agrees" when it has 10+ hits and its own mean point lies within 45° of the overall bearing
 *   and at least half as far out; 3+ agreeing days that are at least two thirds of the days
 *   checked make the offset `stable` (the sights), anything else varies (technique).
 * - **Stretched**: the card's elongation 1.5 at 20 hits fires on 25% of round groups (15% at 25,
 *   5% at 40). The sample major/minor ratio of a round group shrinks like 1/sqrt(n), and its 98th
 *   percentile sits near 1 + 4.5/sqrt(n) from 20 to 200 hits, so the bar is
 *   max(1.5, 1 + 4.5/sqrt(n)): 2.0 at 20 hits, 1.71 at 40, 1.5 from about 80. Orientation:
 *   within 22.5° of vertical or horizontal, else diagonal.
 * - **Fliers**: each hole's offset from the median of the OTHER holes of its series (series of
 *   4+ holes), pooled into one cloud; [groupShape] with flier factor 2.5 picks the fliers, so the
 *   shooter's own typical spread is the yardstick. A hole measured against its own series'
 *   median instead put "fliers" in 22% of round series (five holes give a noisy median), the
 *   leave-one-out cloud in 7%. A finding needs 8+ series, and series with a flier must be at
 *   least 20% of them and at least that 7% chance rate plus 2.5 binomial SDs (3 of 8, 5 of 20,
 *   10 of 50), with mostly one flier per such series (at most 1.5 per series on average).
 * - **Wide round group**: the newest day (3+ series of 2+ holes) against every series before it
 *   (6+): each series' mean distance to its own mean point, the day's average at least 1.3 times
 *   the earlier average and a Welch t of 2.5 or more, and only when that day's pooled hits (15+)
 *   show neither an offset nor a stretch by the bars above.
 * - **Low series / low day**: the card's values as they are: 1.5 sample SDs under the mean of up
 *   to 30 earlier series with the same hole count, 10+ needed. A day (3+ series) is judged by its
 *   mean against the same per-series SD, which is deliberately strict; a low day replaces the low
 *   series it contains. The SD is floored at 1 point so an all-equal history divides by nothing.
 *   A round shooter's newest series reads low about 9% of the time: that is a plain fact about
 *   the series, not a fault claim, so the bar stays where the plan put it.
 * - **Trend**: [fit] over the per-series totals (the most common hole count only, so totals
 *   compare): 10+ series over 14+ days, the slope's t at least 2.5 and the change over the span at
 *   least half the totals' SD. Both directions; improving is said too.
 *
 * Measured with these values on 400 seeded round histories (10 days of 4 five-hole series): 8
 * group findings in all (6 fliers, 2 wide days, no offset, stretch or trend), about 2%.
 *
 * Confidence: half from the data size (reaching 1 at twice the minimum), half from the margin by
 * which the evidence clears its bar (reaching 1 at twice the bar). [findings] sorts by it.
 */
sealed interface Finding {
    /** In (0, 1]: 0.25 is a finding that only just clears its bar with the minimum data. */
    val confidence: Double

    /** The mean point of impact sits off the centre. */
    data class OffsetGroup(
        val hitCount: Int,
        val mpiXMm: Double,
        val mpiYMm: Double,
        val distanceMm: Double,
        /** Clockwise from 12 o'clock, [0, 360): 90 = right, 180 = low. */
        val bearingDeg: Double,
        val standardErrorMm: Double,
        /** Days with enough hits to judge their own offset. */
        val daysChecked: Int,
        /** Of those, the days whose offset points the same way. */
        val daysAgreeing: Int,
        /** True: the same offset on 3+ separate days (the sights); false: it varies (technique). */
        val stable: Boolean,
        override val confidence: Double,
    ) : Finding

    /** The group is clearly longer than wide. */
    data class StretchedGroup(
        val hitCount: Int,
        val elongation: Double,
        val majorSdMm: Double,
        val minorSdMm: Double,
        /** [0, 180), counter-clockwise from horizontal with y up: 90 = vertical. */
        val axisDeg: Double,
        val orientation: Orientation,
        override val confidence: Double,
    ) : Finding

    /** A tight core with one hole getting away in a meaningful share of series. */
    data class Fliers(
        val seriesCount: Int,
        val seriesWithFliers: Int,
        val flierCount: Int,
        val hitCount: Int,
        /** RMS distance of the non-flier holes to the rest of their series. */
        val coreRadialSdMm: Double,
        /** Mean distance of a flier to the rest of its series. */
        val flierMeanDistanceMm: Double,
        override val confidence: Double,
    ) : Finding

    /** The newest day's groups are much wider than the shooter's earlier ones, with no direction. */
    data class WideRoundGroup(
        val date: LocalDate,
        val recentSeriesCount: Int,
        val earlierSeriesCount: Int,
        /** Mean distance of a hole to its own series' mean point, that day / before it. */
        val recentRadiusMm: Double,
        val earlierRadiusMm: Double,
        val ratio: Double,
        override val confidence: Double,
    ) : Finding

    /** The newest series scored well under the shooter's usual. */
    data class LowSeries(
        val seriesId: Long,
        val total: Int,
        val historyMeanTotal: Double,
        val historySd: Double,
        val historyCount: Int,
        /** (total - mean) / SD, negative. */
        val z: Double,
        override val confidence: Double,
    ) : Finding

    /** The newest day's series scored well under the shooter's usual on average. */
    data class LowDay(
        val date: LocalDate,
        val seriesCount: Int,
        val meanTotal: Double,
        val historyMeanTotal: Double,
        val historySd: Double,
        val historyCount: Int,
        val z: Double,
        override val confidence: Double,
    ) : Finding

    /** Totals rise or fall over the weeks. */
    data class Trend(
        val improving: Boolean,
        /** Signed: positive is better. */
        val pointsPerWeek: Double,
        /** Signed change of the fitted line from the first series to the last. */
        val changeOverSpan: Double,
        val spanDays: Double,
        val seriesCount: Int,
        val tStat: Double,
        override val confidence: Double,
    ) : Finding

    enum class Orientation { VERTICAL, HORIZONTAL, DIAGONAL }
}

/** The bars; defaults are the tuned values, see [Finding]. */
data class FindingThresholds(
    val offsetMinHits: Int = 15,
    val offsetSeFactor: Double = 3.0,
    val offsetMinMm: Double = 10.0,
    val offsetDayMinHits: Int = 10,
    val offsetDayMaxAngleDeg: Double = 45.0,
    val offsetDayMinShare: Double = 0.5,
    val offsetStableDays: Int = 3,
    val stretchMinHits: Int = 20,
    val stretchMinElongation: Double = 1.5,
    val stretchChanceCoefficient: Double = 4.5,
    val stretchAxisToleranceDeg: Double = 22.5,
    val flierFactor: Double = 2.5,
    val flierMinSeries: Int = 8,
    val flierMinShare: Double = 0.2,
    val flierChanceRate: Double = 0.07,
    val flierChanceSds: Double = 2.5,
    val flierMaxPerSeries: Double = 1.5,
    val wideMinRecentSeries: Int = 3,
    val wideMinEarlierSeries: Int = 6,
    val wideMinRatio: Double = 1.3,
    val wideMinT: Double = 2.5,
    val lowSds: Double = 1.5,
    val lowWindow: Int = 30,
    val lowMinHistory: Int = 10,
    val lowDayMinSeries: Int = 3,
    val lowMinSd: Double = 1.0,
    val trendMinSeries: Int = 10,
    val trendMinDays: Double = 14.0,
    val trendMinT: Double = 2.5,
    val trendMinChangeSds: Double = 0.5,
) {
    /** The elongation a group of [hits] must reach: round groups pass it about 2% of the time. */
    fun stretchBar(hits: Int): Double = max(stretchMinElongation, 1 + stretchChanceCoefficient / sqrt(hits.toDouble()))
}

/**
 * Every finding these series (one caliber, any order) support, strongest first. Days are local
 * days in [zone]; "newest" means the series or day with the latest timestamp.
 */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.findings(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    thresholds: FindingThresholds = FindingThresholds(),
): List<Finding> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.at }
    val low = sorted.lowDay(zone, thresholds) ?: sorted.lowSeries(thresholds)
    return listOfNotNull(
        sorted.offsetGroup(zone, thresholds),
        sorted.stretchedGroup(thresholds),
        sorted.fliers(thresholds),
        sorted.wideRoundGroup(zone, thresholds),
        low,
        sorted.scoreTrend(thresholds),
    ).sortedByDescending { it.confidence }
}

/** [findings] per caliber label, in [Caliber] order; the caller never mixes guns. */
fun List<PlottedSeries>.findingsByCaliber(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    thresholds: FindingThresholds = FindingThresholds(),
): Map<Caliber, List<Finding>> =
    groupBy { it.series.caliber }
        .map { (label, group) -> Caliber.fromLabel(label) to group }
        .sortedBy { it.first.ordinal }
        .associate { (caliber, group) -> caliber to group.findings(zone, thresholds) }

/** Half from the data ([n] against [minN]), half from the margin ([ratio] = evidence / bar, >= 1). */
private fun confidence(n: Int, minN: Int, ratio: Double): Double {
    val data = min(1.0, n / (2.0 * max(minN, 1)))
    val margin = if (ratio.isNaN()) 0.0 else min(1.0, max(0.0, ratio - 1))
    return max(0.01, 0.5 * data + 0.5 * margin)
}

private fun List<PlottedSeries>.allHits(): List<PlottedHit> = flatMap { it.hits }

/** The offset bar for this shape: true when its mean point is really off centre. */
private fun GroupShape.isOffset(t: FindingThresholds): Boolean =
    mpiDistanceMm > t.offsetSeFactor * offsetSeMm && mpiDistanceMm >= t.offsetMinMm

private fun List<PlottedSeries>.offsetGroup(zone: TimeZone, t: FindingThresholds): Finding.OffsetGroup? {
    val hits = allHits()
    if (hits.size < t.offsetMinHits) return null
    val g = hits.groupShape() ?: return null
    if (!g.isOffset(t)) return null
    var checked = 0
    var agreeing = 0
    for ((_, day) in groupBy { dayOf(it, zone) }) {
        val dayHits = day.allHits()
        if (dayHits.size < t.offsetDayMinHits) continue
        checked++
        val mx = dayHits.sumOf { it.xMm } / dayHits.size
        val my = dayHits.sumOf { it.yMm } / dayHits.size
        val dist = hypot(mx, my)
        if (dist == 0.0 || dist < t.offsetDayMinShare * g.mpiDistanceMm) continue
        val bearing = bearingDeg(mx, my)
        if (angleBetween(bearing, g.mpiBearingDeg) <= t.offsetDayMaxAngleDeg) agreeing++
    }
    val stable = agreeing >= t.offsetStableDays && agreeing * 3 >= checked * 2
    val ratio = min(
        g.mpiDistanceMm / max(t.offsetSeFactor * g.offsetSeMm, 1e-9),
        g.mpiDistanceMm / t.offsetMinMm,
    )
    return Finding.OffsetGroup(
        hitCount = g.hitCount,
        mpiXMm = g.mpiXMm,
        mpiYMm = g.mpiYMm,
        distanceMm = g.mpiDistanceMm,
        bearingDeg = g.mpiBearingDeg,
        standardErrorMm = g.offsetSeMm,
        daysChecked = checked,
        daysAgreeing = agreeing,
        stable = stable,
        confidence = confidence(g.hitCount, t.offsetMinHits, ratio),
    )
}

private fun List<PlottedSeries>.stretchedGroup(t: FindingThresholds): Finding.StretchedGroup? {
    val hits = allHits()
    if (hits.size < t.stretchMinHits) return null
    val g = hits.groupShape() ?: return null
    val bar = t.stretchBar(g.hitCount)
    if (g.elongation < bar) return null
    val a = g.majorAxisDeg
    val orientation = when {
        abs(a - 90) <= t.stretchAxisToleranceDeg -> Finding.Orientation.VERTICAL
        a <= t.stretchAxisToleranceDeg || a >= 180 - t.stretchAxisToleranceDeg -> Finding.Orientation.HORIZONTAL
        else -> Finding.Orientation.DIAGONAL
    }
    return Finding.StretchedGroup(
        hitCount = g.hitCount,
        elongation = g.elongation,
        majorSdMm = g.majorSdMm,
        minorSdMm = g.minorSdMm,
        axisDeg = a,
        orientation = orientation,
        confidence = confidence(g.hitCount, t.stretchMinHits, g.elongation / bar),
    )
}

private fun List<PlottedSeries>.fliers(t: FindingThresholds): Finding.Fliers? {
    val used = filter { it.hits.size >= 4 }
    if (used.size < t.flierMinSeries) return null
    // Each hole relative to the median of the rest of its series; owner[i] is its series.
    val cloud = mutableListOf<PlottedHit>()
    val owner = mutableListOf<Int>()
    used.forEachIndexed { s, series ->
        series.hits.forEachIndexed { j, h ->
            val others = series.hits.filterIndexed { k, _ -> k != j }
            val mx = others.map { it.xMm }.medianOf()
            val my = others.map { it.yMm }.medianOf()
            cloud += PlottedHit(h.xMm - mx, h.yMm - my, h.ring, h.innerTen)
            owner += s
        }
    }
    val g = cloud.groupShape(t.flierFactor) ?: return null
    val coreSd = g.coreRadialSdMm ?: return null
    // groupShape hands back the very cloud objects it judged fliers, so identity finds their series.
    val flierIdx = cloud.indices.filter { i -> g.fliers.any { it === cloud[i] } }
    val withFliers = flierIdx.map { owner[it] }.toSet().size
    val n = used.size
    val p = t.flierChanceRate
    val needed = max(
        ceil(t.flierMinShare * n).toInt(),
        ceil(p * n + t.flierChanceSds * sqrt(n * p * (1 - p))).toInt(),
    )
    if (withFliers < needed) return null
    if (flierIdx.size > t.flierMaxPerSeries * withFliers) return null
    val flierMean = flierIdx.sumOf { hypot(cloud[it].xMm, cloud[it].yMm) } / flierIdx.size
    return Finding.Fliers(
        seriesCount = n,
        seriesWithFliers = withFliers,
        flierCount = flierIdx.size,
        hitCount = cloud.size,
        coreRadialSdMm = coreSd,
        flierMeanDistanceMm = flierMean,
        confidence = confidence(n, t.flierMinSeries, withFliers.toDouble() / needed),
    )
}

@OptIn(ExperimentalTime::class)
private fun List<PlottedSeries>.wideRoundGroup(zone: TimeZone, t: FindingThresholds): Finding.WideRoundGroup? {
    val spread = filter { it.hits.size >= 2 }
    if (spread.isEmpty()) return null
    val newestDay = dayOf(spread.last(), zone)
    val recent = spread.filter { dayOf(it, zone) == newestDay }
    val earlier = spread.filter { dayOf(it, zone) < newestDay }
    if (recent.size < t.wideMinRecentSeries || earlier.size < t.wideMinEarlierSeries) return null
    val dayHits = recent.allHits()
    if (dayHits.size < t.offsetMinHits) return null
    val day = dayHits.groupShape() ?: return null
    if (day.isOffset(t) || day.elongation >= t.stretchBar(day.hitCount)) return null
    val r = recent.map { it.hits.ownRadius() }
    val e = earlier.map { it.hits.ownRadius() }
    val rm = r.average()
    val em = e.average()
    if (em <= 0.0) return null
    val ratio = rm / em
    if (ratio < t.wideMinRatio) return null
    val se = sqrt(r.sampleVariance() / r.size + e.sampleVariance() / e.size)
    val tStat = if (se > 0) (rm - em) / se else Double.POSITIVE_INFINITY
    if (tStat < t.wideMinT) return null
    return Finding.WideRoundGroup(
        date = newestDay,
        recentSeriesCount = recent.size,
        earlierSeriesCount = earlier.size,
        recentRadiusMm = rm,
        earlierRadiusMm = em,
        ratio = ratio,
        confidence = confidence(recent.size, t.wideMinRecentSeries, min(ratio / t.wideMinRatio, tStat / t.wideMinT)),
    )
}

/** Mean, SD (floored) and count of up to [FindingThresholds.lowWindow] totals; null under the minimum. */
private fun List<PlottedSeries>.history(t: FindingThresholds): Triple<Double, Double, Int>? {
    val window = takeLast(t.lowWindow).map { it.series.total().toDouble() }
    if (window.size < t.lowMinHistory) return null
    return Triple(window.average(), max(sqrt(window.sampleVariance()), t.lowMinSd), window.size)
}

private fun List<PlottedSeries>.lowSeries(t: FindingThresholds): Finding.LowSeries? {
    val newest = lastOrNull() ?: return null
    val holes = newest.series.holes.size
    val (mean, sd, count) = dropLast(1).filter { it.series.holes.size == holes }.history(t) ?: return null
    val total = newest.series.total()
    val z = (total - mean) / sd
    if (z > -t.lowSds) return null
    return Finding.LowSeries(
        seriesId = newest.series.id,
        total = total,
        historyMeanTotal = mean,
        historySd = sd,
        historyCount = count,
        z = z,
        confidence = confidence(count, t.lowMinHistory, -z / t.lowSds),
    )
}

@OptIn(ExperimentalTime::class)
private fun List<PlottedSeries>.lowDay(zone: TimeZone, t: FindingThresholds): Finding.LowDay? {
    val newest = lastOrNull() ?: return null
    val holes = newest.series.holes.size
    val date = dayOf(newest, zone)
    val same = filter { it.series.holes.size == holes }
    val day = same.filter { dayOf(it, zone) == date }
    if (day.size < t.lowDayMinSeries) return null
    val (mean, sd, count) = same.filter { dayOf(it, zone) < date }.history(t) ?: return null
    val dayMean = day.map { it.series.total().toDouble() }.average()
    val z = (dayMean - mean) / sd
    if (z > -t.lowSds) return null
    return Finding.LowDay(
        date = date,
        seriesCount = day.size,
        meanTotal = dayMean,
        historyMeanTotal = mean,
        historySd = sd,
        historyCount = count,
        z = z,
        confidence = confidence(count, t.lowMinHistory, -z / t.lowSds),
    )
}

private fun List<PlottedSeries>.scoreTrend(t: FindingThresholds): Finding.Trend? {
    val holes = groupingBy { it.series.holes.size }.eachCount().maxByOrNull { it.value }?.key ?: return null
    val points = filter { it.series.holes.size == holes }.trend(Metric.SCORE, Bucket.SERIES)
    if (points.size < t.trendMinSeries) return null
    val spanMs = (points.last().at.toEpochMilliseconds() - points.first().at.toEpochMilliseconds()).toDouble()
    if (spanMs < t.trendMinDays * DAY_MS) return null
    val fit = points.fit() ?: return null
    val xs = points.map { (it.at.toEpochMilliseconds() - fit.origin.toEpochMilliseconds()).toDouble() }
    val xMean = xs.average()
    val sxx = xs.sumOf { (it - xMean) * (it - xMean) }
    val ssr = points.sumOf { (it.value - fit.at(it.at)).let { r -> r * r } }
    val slopeSe = sqrt(ssr / (points.size - 2) / sxx)
    val tStat = if (slopeSe > 0) fit.slope / slopeSe else if (fit.slope == 0.0) 0.0 else Double.POSITIVE_INFINITY * fit.slope
    val change = fit.slope * spanMs
    if (abs(tStat) < t.trendMinT || abs(change) < t.trendMinChangeSds * fit.sd) return null
    return Finding.Trend(
        improving = fit.slope > 0,
        pointsPerWeek = fit.slope * 7 * DAY_MS,
        changeOverSpan = change,
        spanDays = spanMs / DAY_MS,
        seriesCount = points.size,
        tStat = tStat,
        confidence = confidence(points.size, t.trendMinSeries, abs(tStat) / t.trendMinT),
    )
}

@OptIn(ExperimentalTime::class)
private fun dayOf(series: PlottedSeries, zone: TimeZone): LocalDate =
    // kotlinx-datetime 0.6 has no conversion from kotlin.time.Instant.
    Instant.fromEpochMilliseconds(series.at.toEpochMilliseconds()).toLocalDateTime(zone).date

/** Clockwise from 12 o'clock, [0, 360), the [GroupShape.mpiBearingDeg] convention (y down). */
private fun bearingDeg(x: Double, y: Double): Double {
    val deg = kotlin.math.atan2(x, -y) * 180 / kotlin.math.PI
    return if (deg < 0) deg + 360 else deg
}

/** The smaller angle between two bearings, [0, 180]. */
private fun angleBetween(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

/** Mean distance of the holes to their own mean point. */
private fun List<PlottedHit>.ownRadius(): Double {
    val cx = sumOf { it.xMm } / size
    val cy = sumOf { it.yMm } / size
    return sumOf { hypot(it.xMm - cx, it.yMm - cy) } / size
}

private fun List<Double>.sampleVariance(): Double {
    if (size < 2) return 0.0
    val m = average()
    return sumOf { (it - m) * (it - m) } / (size - 1)
}

private fun List<Double>.medianOf(): Double {
    val s = sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}
