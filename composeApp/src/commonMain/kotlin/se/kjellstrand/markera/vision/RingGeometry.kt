package se.kjellstrand.markera.vision

import kotlin.math.hypot

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
    val cx = predicted.cx.toDouble()
    val cy = predicted.cy.toDouble()
    val r0 = predicted.semiMajor.toDouble()

    // Pass 1: wide circular band around the seed radius, so a tilted (elliptical)
    // rim still falls inside the search window.
    val p1 = radialEdgePoints(gray, width, height, cx, cy, r0, threshold, bandFrac = 0.35)
    if (p1.size < 24) return predicted
    var e = fitEllipseRobust(p1)?.first ?: return predicted
    // Pass 2: re-anchor each ray's band on the first ellipse's radius at that angle.
    val p2 = radialEdgePointsAround(gray, width, height, e, threshold, bandFrac = 0.15)
    if (p2.size >= 24) {
        fitEllipseRobust(p2)?.first?.let { e = it }
    }

    // Sanity gate: centred near the seed, sane size, not a degenerate sliver.
    val dc = hypot(e.cx - predicted.cx, e.cy - predicted.cy)
    val sizeOk = e.semiMajor in 0.5f * predicted.semiMajor..1.7f * predicted.semiMajor
    val eccOk = e.semiMinor / e.semiMajor >= 0.3f
    return if (dc < 0.25f * predicted.semiMajor && sizeOk && eccOk) e else predicted
}
