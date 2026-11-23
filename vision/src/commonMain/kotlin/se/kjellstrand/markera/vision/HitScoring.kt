package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 25m Precision / 50m Pistol target geometry and the hit scorer. Pure
 * Kotlin — no platform deps — so the math is exercised by JVM unit tests
 * and stays identical on iOS.
 *
 * The scale reference is the black 6/7 boundary (= ring-7 outer edge),
 * which is [TARGET_BLACK_RING_RADIUS_MM];
 * the detected 6/7 ellipse gives both that radius in pixels and the
 * perspective (a tilted circle). The centre always comes from the digit
 * rows ([CentreEstimate]), never the ellipse centre.
 */

/** Black 7-ring outer radius in mm — used to convert pixels to mm. */
const val TARGET_BLACK_RING_RADIUS_MM: Double = 100.0

/** A hit whose scoring edge is within this radius counts as an inner-X. */
const val INNER_TEN_RADIUS_MM: Double = 12.5

/** Outer radii of rings 10 → 1, in mm. Index 0 = ring 10 (25mm),
 *  index 9 = ring 1 (250mm). Beyond the last entry = miss (ring 0). */
val RING_RADII_MM: DoubleArray =
    doubleArrayOf(25.0, 50.0, 75.0, 100.0, 125.0, 150.0, 175.0, 200.0, 225.0, 250.0)

/** Ring width over the 6/7 radius, 25 mm / 100 mm. */
val RING_WIDTH_FRACTION: Double = (RING_RADII_MM[1] - RING_RADII_MM[0]) / TARGET_BLACK_RING_RADIUS_MM

/** The ring lines inside the black 6/7 edge (9/10, 8/9, 7/8), mm. */
val INNER_RING_RADII_MM: List<Double> = RING_RADII_MM.filter { it < TARGET_BLACK_RING_RADIUS_MM }

/** Where ring [ring]'s digit (1..9) is printed: mid-band, mm from the centre. */
fun ringDigitRadiusMm(ring: Int): Double = (RING_RADII_MM[10 - ring] + RING_RADII_MM[9 - ring]) / 2.0

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
    /**
     * [ring]/[isInnerTen] were set by tapping the score box, not derived from
     * where the hole sits ([distanceMm] still is). It survives a drag: a score
     * the user selected is never overwritten.
     */
    val typed: Boolean = false,
    /** The detector's box (image px) and confidence, kept so a false positive can be exported as a box. */
    val boxW: Float? = null,
    val boxH: Float? = null,
    val conf: Float? = null,
)

/**
 * Not the detector's untouched hole: placed by hand, or dragged or retyped
 * (both keep the detector's version in [original]). Drawn orange everywhere.
 */
val HitScore.byHand: Boolean get() = manual || original != null

/**
 * Offset from the digit-row [centre] to the point [x],[y] (source-image px) in
 * target mm, un-projected through the 6/7 [ring] under full perspective
 * ([TargetPlane]), in the photo's orientation (image axes, y down) so it can be
 * plotted as-is. Without a usable plane (centre outside the ring) it falls back
 * to [affineOffsetMm]. A degenerate ellipse has no scale, so it measures (0, 0).
 */
fun targetOffsetMm(x: Float, y: Float, centre: CentreEstimate, ring: FittedEllipse): Pair<Double, Double> {
    if (ring.semiMajor <= 0f || ring.semiMinor <= 0f) return 0.0 to 0.0
    return TargetPlane.of(centre, ring)?.toMm(x.toDouble(), y.toDouble()) ?: affineOffsetMm(x, y, centre, ring)
}

/**
 * The old weak-perspective model: rotate so the major axis is +x, stretch the
 * minor-axis component by `semiMajor / semiMinor`, scale by
 * `TARGET_BLACK_RING_RADIUS_MM / semiMajor`, rotate back. It ignores how
 * perspective slides each ring's centre, so it is only the fallback.
 */
internal fun affineOffsetMm(x: Float, y: Float, centre: CentreEstimate, ring: FittedEllipse): Pair<Double, Double> =
    affineOffsetMm(x.toDouble(), y.toDouble(), centre, ring)

private fun affineOffsetMm(x: Double, y: Double, centre: CentreEstimate, ring: FittedEllipse): Pair<Double, Double> {
    val theta = ring.rotationRad.toDouble()
    val cosT = cos(theta)
    val sinT = sin(theta)
    val dx = x - centre.x
    val dy = y - centre.y
    val xR = dx * cosT + dy * sinT
    val yR = -dx * sinT + dy * cosT
    val yC = yR * (ring.semiMajor / ring.semiMinor).toDouble()
    val mmPerPx = TARGET_BLACK_RING_RADIUS_MM / ring.semiMajor
    return (xR * cosT - yC * sinT) * mmPerPx to (xR * sinT + yC * cosT) * mmPerPx
}

/**
 * The image-space outline of a target circle of [radiusMm], for drawing over
 * the photo: the inverse of [targetOffsetMm], so what is drawn and what is
 * scored can't disagree. Under perspective it is generally not centred on
 * [centre]; the fallback is the 6/7 ellipse scaled about [centre].
 */
fun ringOutline(ring: FittedEllipse, centre: CentreEstimate, radiusMm: Double): FittedEllipse {
    TargetPlane.of(centre, ring)?.circleOutline(radiusMm)?.let { return it }
    val k = (radiusMm / TARGET_BLACK_RING_RADIUS_MM).toFloat()
    return FittedEllipse(
        cx = centre.x,
        cy = centre.y,
        semiMajor = ring.semiMajor * k,
        semiMinor = ring.semiMinor * k,
        rotationRad = ring.rotationRad,
    )
}

/** Length of [targetOffsetMm] — the distance from the centre to the hole, in mm. */
fun distanceMm(x: Float, y: Float, centre: CentreEstimate, ring: FittedEllipse): Double {
    val (ox, oy) = targetOffsetMm(x, y, centre, ring)
    return sqrt(ox * ox + oy * oy)
}

/**
 * The photo's local scale at one point: how a small round hole in the target
 * plane shows up in the image. Built once per scan ([TargetPlane] when there is
 * one, else the affine fallback) so every hole is measured through the same map
 * as its centre distance.
 */
private class HoleGauge(private val centre: CentreEstimate, private val ring: FittedEllipse) {
    private val plane = TargetPlane.of(centre, ring)

    private fun toMm(x: Double, y: Double): Pair<Double, Double> =
        plane?.toMm(x, y) ?: affineOffsetMm(x, y, centre, ring)

    /**
     * The image half-extents per mm of hole radius at [x],[y]: (half box width,
     * half box height) of the image of a 1 mm circle there. A target-plane circle
     * of radius r maps, locally, through the Jacobian A = d(image)/d(mm) to an
     * ellipse whose axis-aligned box has half-width r·|row 1 of A| and
     * half-height r·|row 2 of A|. A is the inverse of d(mm)/d(image), taken by
     * central differences over [stepPx] (the map is smooth on a hole's scale).
     */
    fun halfExtentPerMm(x: Double, y: Double, stepPx: Double): Pair<Double, Double>? {
        val h = stepPx.coerceAtLeast(1.0)
        val (xr, yr) = toMm(x + h, y)
        val (xl, yl) = toMm(x - h, y)
        val (xd, yd) = toMm(x, y + h)
        val (xu, yu) = toMm(x, y - h)
        // J = d(mm)/d(image px).
        val j11 = (xr - xl) / (2 * h)
        val j21 = (yr - yl) / (2 * h)
        val j12 = (xd - xu) / (2 * h)
        val j22 = (yd - yu) / (2 * h)
        val det = j11 * j22 - j12 * j21
        if (!det.isFinite() || det == 0.0) return null
        // A = J⁻¹ = [[j22, -j12], [-j21, j11]] / det.
        val perMmX = hypot(j22, j12) / abs(det)
        val perMmY = hypot(j21, j11) / abs(det)
        return perMmX to perMmY
    }
}

/**
 * The radius in target mm of the round hole whose image box is [d], gauged
 * where it sits ([HoleGauge.halfExtentPerMm]): the box width and height each
 * give one estimate (half-width over the px-per-mm across, half-height over
 * the px-per-mm down), and their mean is the radius. On a frontal photo that is
 * the old `(w + h) / 4 · mmPerPx`; on a tilted one the foreshortened side is no
 * longer read at the major axis' scale, and perspective's change of scale
 * across the target is included.
 */
private fun HoleGauge.radiusMm(d: Detection): Double {
    val halfW = (d.right - d.left) / 2.0
    val halfH = (d.bottom - d.top) / 2.0
    if (halfW <= 0.0 && halfH <= 0.0) return 0.0
    val (perX, perY) = halfExtentPerMm(
        (d.left + d.right) / 2.0, (d.top + d.bottom) / 2.0, maxOf(halfW, halfH),
    ) ?: return 0.0
    return (halfW / perX + halfH / perY) / 2.0
}

/**
 * The image box (width, height, px) of a round hole of [radiusMm] centred at
 * [x],[y] (image px): the inverse of the radius [scoreHits] reads from a box,
 * so a hand-placed hole of a caliber's size scores that caliber's radius on any
 * photo. Null for a degenerate ring.
 */
fun holeBoxSizePx(x: Float, y: Float, radiusMm: Double, centre: CentreEstimate, ring: FittedEllipse): Pair<Float, Float>? {
    if (ring.semiMajor <= 0f || ring.semiMinor <= 0f) return null
    val gauge = HoleGauge(centre, ring)
    // Step about the size of the hole, as scoring will use for this box.
    val step = radiusMm * ring.semiMajor / TARGET_BLACK_RING_RADIUS_MM
    val (perX, perY) = gauge.halfExtentPerMm(x.toDouble(), y.toDouble(), step) ?: return null
    return (2 * radiusMm * perX).toFloat() to (2 * radiusMm * perY).toFloat()
}

/**
 * Score each detected hole in the frontal target plane recovered from the
 * 6/7 [ring] ellipse and the digit-row [centre] ([targetOffsetMm], full
 * perspective through [TargetPlane]). Each hole is edge-gauged through
 * [scoreAt] by its radius in target mm, read from its box with the photo's
 * local scale at that hole ([HoleGauge]), so a tilted photo's foreshortened
 * side does not shrink it.
 *
 * Returned inner-X first, then highest ring, then nearest — so hit `i` maps
 * straight onto score picker `i`.
 */
fun scoreHits(
    detections: List<Detection>,
    centre: CentreEstimate,
    ring: FittedEllipse,
): List<HitScore> {
    if (detections.isEmpty() || ring.semiMajor <= 0f || ring.semiMinor <= 0f) return emptyList()
    val gauge = HoleGauge(centre, ring)
    return detections.map { d ->
        val cx = (d.left + d.right) / 2f
        val cy = (d.top + d.bottom) / 2f
        val distMm = distanceMm(cx, cy, centre, ring)
        val holeRadiusMm = gauge.radiusMm(d)
        val (ring, innerTen) = scoreAt(distMm, holeRadiusMm)
        HitScore(
            centerXpx = cx,
            centerYpx = cy,
            topYpx = d.top,
            distanceMm = distMm,
            ring = ring,
            isInnerTen = innerTen,
            boxW = d.right - d.left,
            boxH = d.bottom - d.top,
            conf = d.conf,
        )
    }
}

/**
 * The one scoring rule, detected or hand-placed: (ring, inner-X) for a hole
 * whose centre is [distMm] from the target centre. Edge gauge — the shot counts
 * the higher ring if the hole's edge reaches the line, so the ring is decided on
 * the distance to the hole's inner edge (centre distance minus [holeRadiusMm]).
 */
fun scoreAt(distMm: Double, holeRadiusMm: Double): Pair<Int, Boolean> {
    val edgeMm = (distMm - holeRadiusMm).coerceAtLeast(0.0)
    return ringForDistance(edgeMm) to (edgeMm <= INNER_TEN_RADIUS_MM)
}

/**
 * The order hits are always shown (and stored) in: inner-X first, then highest
 * ring, then nearest — so hit `i` maps straight onto score picker `i`.
 */
val HIT_SCORE_ORDER: Comparator<HitScore> = scoreOrder({ it.isInnerTen }, { it.ring }, { it.distanceMm })

/** Inner-X first, then highest ring, then nearest; no distance sorts last. */
fun <T> scoreOrder(innerTen: (T) -> Boolean, ring: (T) -> Int, distanceMm: (T) -> Double?): Comparator<T> =
    compareByDescending(innerTen).thenByDescending(ring).thenBy { distanceMm(it) ?: Double.MAX_VALUE }

/**
 * Index of the element nearest [x],[y] within [maxDist], or -1 when nothing is
 * in reach. [at] gives an element's position; null means it can't be hit.
 */
fun <T> List<T>.nearestIndex(x: Double, y: Double, maxDist: Double, at: (T) -> Pair<Double, Double>?): Int {
    var best = -1
    var bestDist = maxDist
    forEachIndexed { i, e ->
        val (ex, ey) = at(e) ?: return@forEachIndexed
        val dist = hypot(x - ex, y - ey)
        if (dist < bestDist) {
            best = i
            bestDist = dist
        }
    }
    return best
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
