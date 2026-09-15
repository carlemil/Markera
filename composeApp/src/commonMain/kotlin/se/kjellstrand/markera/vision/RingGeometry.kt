package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Seed the 6/7 boundary as a circle from the recognised ring digits; the real
 * shape is recovered from the black rim in [refine67ToEdge].
 *
 * Centre comes from the digit-row intersection ([centre]) — never the ellipse.
 * Radius: each digit of value v sits at `rho_v = R67 - (v - 6.5)*w`, so scaling
 * its distance from the centre by `R67/rho_v = 1 / (1 - (v-6.5)*q)` with the
 * spec-fixed ring-width fraction `q = w/R67 = 0.25` maps it to ~the 6/7 radius;
 * the **median** of those is a robust R67 estimate (OCR misreads get amplified
 * to wildly different radii, so the median ignores them).
 *
 * We deliberately do NOT fit the ellipse *shape* from these points: there are
 * few of them, often one-sided at high tilt, and a 5-DOF conic fit on them
 * degenerated (near-collinear -> slivers) and amplified misreads (2x blow-ups).
 * The black->white rim is a far better, well-distributed shape signal.
 */
fun fit67RingFromDigits(
    digits: List<DigitDetection>,
    centre: CentreEstimate,
    minConf: Float = 0.3f,
): FittedEllipse? {
    if (centre.method == CentreMethod.NONE) return null
    val usable = digits.filter { it.value in 6..9 && it.conf >= minConf }
    if (usable.size < 5) return null
    val cx = centre.x.toDouble()
    val cy = centre.y.toDouble()

    // Ring-width fraction from the known target spec: w / R67 (25 mm / 100 mm).
    val q = (RING_RADII_MM[1] - RING_RADII_MM[0]) / TARGET_BLACK_RING_RADIUS_MM
    val radii = ArrayList<Double>(usable.size)
    for (d in usable) {
        val denom = 1.0 - (d.value - 6.5) * q
        if (denom <= 0.05) continue
        radii.add(hypot(d.cx - cx, d.cy - cy) * (1.0 / denom))
    }
    if (radii.size < 5) return null
    radii.sort()
    val r0 = radii[radii.size / 2] // median 6/7 radius, robust to misreads
    if (r0 <= 1.0) return null
    // Circle seed at the digit centre; refine67ToEdge gives it the real shape.
    return FittedEllipse(cx.toFloat(), cy.toFloat(), r0.toFloat(), r0.toFloat(), 0f)
}

/**
 * Fit the 6/7 ellipse to the real black->white rim, seeded by [predicted] (a
 * circle at the digit centre with the estimated 6/7 radius). Two passes: a wide
 * circular band catches an eccentric/tilted rim, then each ray re-anchors on the
 * fitted ellipse's own radius for a tight follow. Falls back to the seed if the
 * rim isn't found or the refit is implausible (off-centre, wrong size, or a
 * sliver) — so a bad edge can't make the result worse than the digit seed.
 */
fun refine67ToEdge(
    gray: ByteArray,
    width: Int,
    height: Int,
    predicted: FittedEllipse,
): FittedEllipse {
    val threshold = otsuThreshold(gray, width, height)
    if (threshold <= 0) return predicted
    val e = twoPassRimFit(
        gray, width, height,
        predicted.cx.toDouble(), predicted.cy.toDouble(), predicted.semiMajor.toDouble(),
        threshold, bandFrac = 0.35,
    ) ?: return predicted

    // Sanity gate: centred near the seed, sane size, not a degenerate sliver.
    val dc = hypot(e.cx - predicted.cx, e.cy - predicted.cy)
    val sizeOk = e.semiMajor in 0.5f * predicted.semiMajor..1.7f * predicted.semiMajor
    val eccOk = e.semiMinor / e.semiMajor >= 0.3f
    return if (dc < 0.25f * predicted.semiMajor && sizeOk && eccOk) e else predicted
}

/**
 * Pass 1: a wide circular band ([bandFrac]) around radius [r0] about
 * ([cx],[cy]), so a tilted (elliptical) rim still falls inside the search
 * window. Pass 2: re-anchor each ray's band on the first ellipse's radius at
 * that angle. Null when pass 1 finds too few edge points or no ellipse.
 */
private fun twoPassRimFit(
    gray: ByteArray,
    width: Int,
    height: Int,
    cx: Double,
    cy: Double,
    r0: Double,
    threshold: Int,
    bandFrac: Double,
): FittedEllipse? {
    val p1 = radialEdgePoints(gray, width, height, cx, cy, r0, threshold, bandFrac = bandFrac)
    if (p1.size < 24) return null
    var e = fitEllipseRobust(p1)?.first ?: return null
    val p2 = radialEdgePointsAround(gray, width, height, e, threshold, bandFrac = 0.15)
    if (p2.size >= 24) {
        fitEllipseRobust(p2)?.first?.let { e = it }
    }
    return e
}

/**
 * How much [e] looks like the black->white rim: over 180 angles, sample just
 * inside (0.94 x its radius there) and just outside (1.06 x). Score = fraction
 * of angles where inside < [threshold] < outside, times the mean outside-inside
 * difference. A ring printed inside the disk or a paper/shadow edge scores low.
 */
fun rimContrast(gray: ByteArray, width: Int, height: Int, e: FittedEllipse, threshold: Int): Double {
    val n = 180
    var straddles = 0
    var diff = 0.0
    for (i in 0 until n) {
        val ang = 2.0 * PI * i / n
        val r = ellipseRadiusAt(e, ang)
        val dx = cos(ang)
        val dy = sin(ang)
        val inside = sampleLum(gray, width, height, e.cx + dx * 0.94 * r, e.cy + dy * 0.94 * r)
        val outside = sampleLum(gray, width, height, e.cx + dx * 1.06 * r, e.cy + dy * 1.06 * r)
        if (inside < threshold && outside > threshold) straddles++
        diff += outside - inside
    }
    return straddles.toDouble() / n * (diff / n)
}

/** Centre offset and both semi-axes within [tol] of [b]'s (rotation ignored: near-circles have none). */
fun sameRing(a: FittedEllipse, b: FittedEllipse, tol: Float = 0.02f): Boolean =
    hypot(a.cx - b.cx, a.cy - b.cy) <= tol * b.semiMajor &&
        abs(a.semiMajor - b.semiMajor) <= tol * b.semiMajor &&
        abs(a.semiMinor - b.semiMinor) <= tol * b.semiMinor

/**
 * Alternative 6/7 rims for a retry, best first (at most 5, empty when none is
 * plausible). Every search is centred on the digit [centre]; radius priors are
 * the [digitSeed] radius, the angle-averaged disk-profile radius and the
 * viewfinder guide (0.35 x width), each tried at x0.85/1/1.15 with two pass-1
 * bands and three thresholds around Otsu. Survivors of a sanity gate are ranked
 * by [rimContrast] and near-duplicates dropped.
 *
 * ponytail: brute force, 3 priors x 18 two-pass fits; prune the grid if it is too slow on a phone.
 */
fun ringCandidates(
    gray: ByteArray,
    width: Int,
    height: Int,
    centre: CentreEstimate,
    digitSeed: FittedEllipse?,
): List<FittedEllipse> {
    if (centre.method == CentreMethod.NONE) return emptyList()
    val otsu = otsuThreshold(gray, width, height)
    if (otsu <= 0) return emptyList()
    val cx = centre.x.toDouble()
    val cy = centre.y.toDouble()
    val side = min(width, height).toDouble()
    val priors = buildList {
        digitSeed?.let { add(it.semiMajor.toDouble()) }
        estimateDiskRadius(gray, width, height, cx, cy, 0.12 * side, 0.49 * side).let { if (it > 0) add(it) }
        add(0.35 * width)
    }
    val found = ArrayList<FittedEllipse>()
    for (prior in priors) for (scale in doubleArrayOf(0.85, 1.0, 1.15)) for (band in doubleArrayOf(0.25, 0.45)) {
        for (dt in intArrayOf(-10, 0, 10)) {
            val e = twoPassRimFit(gray, width, height, cx, cy, prior * scale, otsu + dt, band) ?: continue
            val plausible = hypot(e.cx - cx, e.cy - cy) <= 0.25 * e.semiMajor &&
                e.semiMinor / e.semiMajor >= 0.3f &&
                e.semiMajor.toDouble() in 0.12 * side..0.49 * side
            if (plausible) found.add(e)
        }
    }
    val kept = ArrayList<FittedEllipse>()
    for (e in found.sortedByDescending { rimContrast(gray, width, height, it, otsu) }) {
        if (kept.none { sameRing(e, it) }) kept.add(e)
        if (kept.size == 5) break
    }
    return kept
}
