package se.kjellstrand.markera.series.stats

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Shape of a group of hits in target millimetres (x right, y DOWN, centre at 0,0).
 *
 * Conventions (shared with the findings engine, read them before using a number):
 * - Covariance is the sample covariance (divide by n-1). [majorSdMm]/[minorSdMm] are the
 *   square roots of its eigenvalues.
 * - [majorAxisDeg] is in [0, 180), counter-clockwise from horizontal as the shooter sees
 *   the target (y flipped to up): 0 = horizontal, 90 = vertical, 45 = upper-right to
 *   lower-left, 135 = upper-left to lower-right. A round group reports 0 and elongation 1.
 * - [mpiBearingDeg] is clockwise from 12 o'clock, [0, 360): 90 = right, 180 = low.
 * - [seXMm]/[seYMm] are sd/sqrt(n); [offsetSeMm] is sqrt(u^T C u / n) along the MPI's own
 *   direction u (sqrt(trace(C)/(2n)) when the MPI sits exactly on the centre), so a caller
 *   tests `mpiDistanceMm > 2 * offsetSeMm` for a real offset.
 * - Fliers: holes whose distance to the coordinate-wise median point exceeds `flierFactor`
 *   times the median of those distances. The core is the non-fliers; [coreRadialSdMm] (RMS
 *   distance to the core's own mean) and [coreMeanRadiusMm] (mean distance to it) are null
 *   when the core has fewer than 3 hits.
 */
data class GroupShape(
    val hitCount: Int,
    val mpiXMm: Double,
    val mpiYMm: Double,
    val mpiDistanceMm: Double,
    val mpiBearingDeg: Double,
    val seXMm: Double,
    val seYMm: Double,
    val offsetSeMm: Double,
    val covXX: Double,
    val covXY: Double,
    val covYY: Double,
    val majorSdMm: Double,
    val minorSdMm: Double,
    val majorAxisDeg: Double,
    val elongation: Double,
    val fliers: List<PlottedHit>,
    val coreCount: Int,
    val coreRadialSdMm: Double?,
    val coreMeanRadiusMm: Double?,
)

private const val EPS = 1e-9
private const val MIN_MEDIAN_MM = 1e-6

/**
 * The [GroupShape] of these hits, or null for fewer than 3 hits and for degenerate groups
 * (all identical or collinear: minor SD below 1e-9), so nothing downstream divides by zero.
 */
fun List<PlottedHit>.groupShape(flierFactor: Double = 2.5): GroupShape? {
    val n = size
    if (n < 3) return null
    val mx = sumOf { it.xMm } / n
    val my = sumOf { it.yMm } / n
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    for (h in this) {
        val dx = h.xMm - mx
        val dy = h.yMm - my
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }
    sxx /= n - 1
    sxy /= n - 1
    syy /= n - 1

    val half = (sxx + syy) / 2
    val r = sqrt(((sxx - syy) / 2).let { it * it } + sxy * sxy)
    val major = sqrt(maxOf(half + r, 0.0))
    val minor = sqrt(maxOf(half - r, 0.0))
    if (!(minor >= EPS)) return null

    val round = major - minor < EPS
    val angle = if (round) {
        0.0
    } else {
        // y flipped to up negates the covariance's off-diagonal term.
        var deg = 0.5 * atan2(-2 * sxy, sxx - syy) * 180 / PI
        if (deg < 0) deg += 180.0
        if (deg >= 180.0) deg = 0.0
        deg
    }
    val elongation = if (round) 1.0 else major / minor

    val dist = hypot(mx, my)
    var bearing = atan2(mx, -my) * 180 / PI
    if (bearing < 0) bearing += 360.0
    if (bearing >= 360.0) bearing = 0.0
    val offsetVar = if (dist == 0.0) {
        (sxx + syy) / 2
    } else {
        val ux = mx / dist
        val uy = my / dist
        ux * ux * sxx + 2 * ux * uy * sxy + uy * uy * syy
    }
    val offsetSe = sqrt(maxOf(offsetVar, 0.0) / n)

    val medX = map { it.xMm }.median()
    val medY = map { it.yMm }.median()
    val dists = map { hypot(it.xMm - medX, it.yMm - medY) }
    val limit = flierFactor * maxOf(dists.median(), MIN_MEDIAN_MM)
    val fliers = filterIndexed { i, _ -> dists[i] > limit }
    val core = filterIndexed { i, _ -> dists[i] <= limit }

    var coreSd: Double? = null
    var coreMean: Double? = null
    if (core.size >= 3) {
        val cx = core.sumOf { it.xMm } / core.size
        val cy = core.sumOf { it.yMm } / core.size
        val radii = core.map { hypot(it.xMm - cx, it.yMm - cy) }
        coreSd = sqrt(radii.sumOf { it * it } / core.size)
        coreMean = radii.sum() / core.size
    }

    return GroupShape(
        hitCount = n,
        mpiXMm = mx,
        mpiYMm = my,
        mpiDistanceMm = dist,
        mpiBearingDeg = bearing,
        seXMm = sqrt(sxx / n),
        seYMm = sqrt(syy / n),
        offsetSeMm = offsetSe,
        covXX = sxx,
        covXY = sxy,
        covYY = syy,
        majorSdMm = major,
        minorSdMm = minor,
        majorAxisDeg = angle,
        elongation = elongation,
        fliers = fliers,
        coreCount = core.size,
        coreRadialSdMm = coreSd,
        coreMeanRadiusMm = coreMean,
    )
}

private fun List<Double>.median(): Double {
    val s = sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}
