package se.kjellstrand.markera.series.stats

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.series.Caliber

/**
 * What the Statistics Insights tab shows for a filtered selection (docs/coaching-plan.md, card 5):
 * one [InsightSection] per caliber, in [Caliber] order, each with its findings strongest first.
 * Caliber "-" is left out, as [findingsByCaliber] does (those series may come from any gun);
 * [uncaliberedSeries] counts them so the tab can say so. Pure, so it is unit-tested.
 */
data class Insights(
    val sections: List<InsightSection>,
    val uncaliberedSeries: Int,
    /** Shots a caliber needs before Insights judges it: the offset finding's minimum. */
    val neededHits: Int,
)

@OptIn(ExperimentalTime::class)
data class InsightSection(
    val caliber: Caliber,
    val series: List<PlottedSeries>,
    val hitCount: Int,
    /** Strongest first; empty when there are too few shots or nothing stands out. */
    val findings: List<Finding>,
    /** The whole group's shape, null under 3 hits or for a degenerate group. */
    val shape: GroupShape?,
    /** The holes a [Finding.Fliers] counts as getting away; empty without one. */
    val fliers: List<PlottedHit>,
    /** The drawn covariance ellipse; null without a [shape]. */
    val ellipse: InsightEllipse?,
    val stats: SeriesStatistics?,
    val enoughData: Boolean,
    /** This caliber's sight setup (click value, adjustment); null when nothing was entered. */
    val sight: SightSetup? = null,
    /** When the sights were adjusted: the offset finding and [arrow] use only later series. */
    val adjustedAt: Instant? = null,
    /** Shots the offset finding judged: all of them, or those after [adjustedAt]. */
    val offsetHitCount: Int = hitCount,
    /** The mean point of impact the offset arrow points at, of the same shots; null under 3 of them (under the offset minimum after an adjustment). */
    val arrow: ImpactPoint? = shape?.let { ImpactPoint(it.mpiXMm, it.mpiYMm) },
)

/** A point on the target in mm, y down. */
data class ImpactPoint(val xMm: Double, val yMm: Double)

/**
 * The ellipse drawn on the target, in target mm (y down), centred on the mean point of impact.
 * The semi-axes are [ELLIPSE_SDS] standard deviations, where about 86% of a normal group lands.
 * [axisDeg] keeps the [GroupShape.majorAxisDeg] convention: counter-clockwise from horizontal
 * with y UP, so a canvas (y down) rotates by minus it.
 */
data class InsightEllipse(
    val cxMm: Double,
    val cyMm: Double,
    val semiMajorMm: Double,
    val semiMinorMm: Double,
    val axisDeg: Double,
)

/** Two SDs: 1 - exp(-2), about 86% of a round normal group falls inside. */
const val ELLIPSE_SDS = 2.0

/**
 * What on the target a finding refers to, highlighted when the finding is tapped. A wide day or a session trend has
 * no mark: the drawn ellipse pools every day, not the one day the finding is about.
 */
enum class InsightMark { ARROW, ELLIPSE, FLIERS, NONE }

fun Finding.mark(): InsightMark = when (this) {
    is Finding.OffsetGroup -> InsightMark.ARROW
    is Finding.StretchedGroup -> InsightMark.ELLIPSE
    is Finding.Fliers -> InsightMark.FLIERS
    is Finding.WideRoundGroup, is Finding.LowSeries, is Finding.LowDay, is Finding.Trend, is Finding.SessionTrend -> InsightMark.NONE
}

/**
 * [sights] are the per-caliber sight setups by caliber label: a caliber whose sights were
 * adjusted judges its offset, and points its arrow, only on the series taken after that. [viewEnd]
 * is the end of the period on show, if it has one: a period whose last local day is before the
 * adjustment's day (a past day opened from the Serie page) is judged as it was then, with no
 * cut-off. By day, so a window ending "now" that was computed a moment before the tap still cuts.
 */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.insights(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    thresholds: FindingThresholds = FindingThresholds(),
    sights: Map<String, SightSetup> = emptyMap(),
    viewEnd: Instant? = null,
): Insights {
    val needed = thresholds.offsetMinHits
    val sections = byCaliber().map { (caliber, group) ->
        val hits = group.flatMap { it.hits }
        val enough = hits.size >= needed
        val sight = sights[caliber.label]
        val cutOff = sight.offsetCutOff()?.takeIf { viewEnd == null || localDay(viewEnd, zone) >= localDay(it, zone) }
        val offsetHits = group.sinceAdjustment(cutOff).flatMap { it.hits }
        val findings = if (enough) group.findings(zone, thresholds, cutOff) else emptyList()
        val shape = hits.groupShape()
        val stretched = findings.filterIsInstance<Finding.StretchedGroup>().firstOrNull()
        val ellipse = shape?.let { g ->
            // A stretch is judged within series: draw that very shape, so picture and sentence agree.
            InsightEllipse(
                cxMm = g.mpiXMm,
                cyMm = g.mpiYMm,
                semiMajorMm = ELLIPSE_SDS * (stretched?.majorSdMm ?: g.majorSdMm),
                semiMinorMm = ELLIPSE_SDS * (stretched?.minorSdMm ?: g.minorSdMm),
                axisDeg = stretched?.axisDeg ?: g.majorAxisDeg,
            )
        }
        InsightSection(
            caliber = caliber,
            series = group,
            hitCount = hits.size,
            findings = findings,
            shape = shape,
            fliers = if (findings.any { it is Finding.Fliers }) group.sortedBy { it.at }.flierHits(thresholds) else emptyList(),
            ellipse = ellipse,
            stats = group.statistics(),
            enoughData = enough,
            sight = sight,
            adjustedAt = cutOff,
            offsetHitCount = offsetHits.size,
            // After an adjustment, no arrow from a handful of new holes: the offset finding's own minimum.
            arrow = (if (cutOff == null) shape else offsetHits.takeIf { it.size >= needed }?.groupShape())
                ?.let { ImpactPoint(it.mpiXMm, it.mpiYMm) },
        )
    }
    return Insights(sections, count { Caliber.fromLabel(it.series.caliber) == Caliber.NONE }, needed)
}

@OptIn(ExperimentalTime::class)
private fun localDay(at: Instant, zone: TimeZone) =
    // kotlinx-datetime 0.6 has no conversion from kotlin.time.Instant.
    kotlinx.datetime.Instant.fromEpochMilliseconds(at.toEpochMilliseconds()).toLocalDateTime(zone).date
