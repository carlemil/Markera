package se.kjellstrand.markera.vision

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 25m Precision / 50m Pistol target geometry and the hit scorer. Pure
 * Kotlin — no platform deps — so the math is exercised by JVM unit tests
 * and stays identical on iOS.
 *
 * The scale reference is the black 6/7 boundary (= ring-7 outer edge),
 * which is [TARGET_BLACK_RING_RADIUS_MM] (defined in TargetCalibration.kt);
 * the detected 6/7 ellipse gives both that radius in pixels and the
 * perspective (a tilted circle). The centre always comes from the digit
 * rows ([CentreEstimate]), never the ellipse centre.
 */

/** A hit whose scoring edge is within this radius counts as an inner-X. */
const val INNER_TEN_RADIUS_MM: Double = 12.5

/** Outer radii of rings 10 → 1, in mm. Index 0 = ring 10 (25mm),
 *  index 9 = ring 1 (250mm). Beyond the last entry = miss (ring 0). */
val RING_RADII_MM: DoubleArray =
    doubleArrayOf(25.0, 50.0, 75.0, 100.0, 125.0, 150.0, 175.0, 200.0, 225.0, 250.0)

data class HitScore(
    /** Hole bbox centre in source-image pixels (for overlay labelling). */
    val centerXpx: Float,
    val centerYpx: Float,
    /** Hole bbox top edge (image px) — for placing the label above the hole. */
    val topYpx: Float,
    /** Distance from the target centre to the hole centre, in mm. */
    val distanceMm: Double,
    val ring: Int,
    val isInnerTen: Boolean,
    /** Placed by hand rather than by the detector — saved without a detected ring. */
    val manual: Boolean = false,
    /**
     * What the detector originally reported for this hole, kept when the user
     * drags it somewhere else — that pair (detected vs. corrected) is the
     * training signal. Null while the hole still sits where it was found.
     */
    val original: HitScore? = null,
)

/**
 * Offset from the digit-row [centre] to the point [x],[y] (source-image px) in
 * target mm, un-projected through the 6/7 [ring] ellipse: rotate so the major
 * axis is +x, stretch the minor-axis component by `semiMajor / semiMinor` to
 * undo the foreshortening, scale by `TARGET_BLACK_RING_RADIUS_MM / semiMajor`,
 * then rotate back by the same angle so the result keeps the photo's
 * orientation (image axes, y down) and can be plotted as-is.
 * A degenerate ellipse has no scale, so it measures (0, 0).
 */
fun targetOffsetMm(x: Float, y: Float, centre: CentreEstimate, ring: FittedEllipse): Pair<Double, Double> {
    if (ring.semiMajor <= 0f || ring.semiMinor <= 0f) return 0.0 to 0.0
    val theta = ring.rotationRad.toDouble()
    val cosT = cos(theta)
    val sinT = sin(theta)
    val dx = (x - centre.x).toDouble()
    val dy = (y - centre.y).toDouble()
    val xR = dx * cosT + dy * sinT
    val yR = -dx * sinT + dy * cosT
    val yC = yR * (ring.semiMajor / ring.semiMinor).toDouble()
    val mmPerPx = TARGET_BLACK_RING_RADIUS_MM / ring.semiMajor
    return (xR * cosT - yC * sinT) * mmPerPx to (xR * sinT + yC * cosT) * mmPerPx
}

/** Length of [targetOffsetMm] — the distance from the centre to the hole, in mm. */
fun distanceMm(x: Float, y: Float, centre: CentreEstimate, ring: FittedEllipse): Double {
    val (ox, oy) = targetOffsetMm(x, y, centre, ring)
    return sqrt(ox * ox + oy * oy)
}

/**
 * Score each detected hole in the frontal target plane recovered from the
 * 6/7 [ring] ellipse. Hole offsets are taken from the digit-row [centre],
 * rotated so the ellipse major axis aligns with +x, then the minor-axis
 * component is stretched by `semiMajor / semiMinor` so the ellipse becomes
 * the original circle; distances convert to mm via `mmPerPx` derived from
 * `semiMajor`. Edge gauge: the shot counts the higher ring if the hole's
 * edge reaches the line, so the ring is decided on the distance to the
 * hole's inner edge (centre distance minus the hole radius).
 *
 * Returned inner-X first, then highest ring, then nearest — so the first
 * five map straight onto the score pickers.
 */
fun scoreHits(
    detections: List<Detection>,
    centre: CentreEstimate,
    ring: FittedEllipse,
): List<HitScore> {
    if (detections.isEmpty() || ring.semiMajor <= 0f || ring.semiMinor <= 0f) return emptyList()
    val mmPerPx = TARGET_BLACK_RING_RADIUS_MM / ring.semiMajor
    return detections.map { d ->
        val cx = (d.left + d.right) / 2f
        val cy = (d.top + d.bottom) / 2f
        val distMm = distanceMm(cx, cy, centre, ring)
        // Edge gauge: score on the distance to the hole's inner edge.
        val holeRadiusMm = ((d.right - d.left) + (d.bottom - d.top)) / 4.0 * mmPerPx
        val edgeMm = (distMm - holeRadiusMm).coerceAtLeast(0.0)
        HitScore(
            centerXpx = cx,
            centerYpx = cy,
            topYpx = d.top,
            distanceMm = distMm,
            ring = ringForDistance(edgeMm),
            isInnerTen = edgeMm <= INNER_TEN_RADIUS_MM,
        )
    }.sortedWith(
        compareByDescending<HitScore> { it.isInnerTen }
            .thenByDescending { it.ring }
            .thenBy { it.distanceMm },
    )
}

/** Ring for a scoring distance in mm; 0 = outside the target (a miss). */
fun ringForDistance(distMm: Double): Int {
    // RING_RADII_MM[0] is ring 10, [9] is ring 1. First index whose radius
    // contains the hit wins; nothing matched = miss (ring 0).
    for (i in RING_RADII_MM.indices) {
        if (distMm <= RING_RADII_MM[i]) return 10 - i
    }
    return 0
}
