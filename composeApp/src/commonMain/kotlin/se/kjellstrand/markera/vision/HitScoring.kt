package se.kjellstrand.markera.vision

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 25m Precision / 50m Pistol target geometry, plus a centre-distance
 * scoring helper. Pure Kotlin — no platform deps — so the math is
 * exercised by JVM unit tests and remains identical on iOS once it
 * gets a host app.
 */

/** Minimum visible width of the target card (mm). Used as the default
 *  pixel-to-mm scale assuming the card fills the frame width. */
const val TARGET_CARD_WIDTH_MM: Double = 550.0

/** A hit whose centre is within this radius counts as an inner-X. */
const val INNER_TEN_RADIUS_MM: Double = 12.5

/** Outer radii of rings 10 → 1, in mm. Index 0 = ring 10 (25mm),
 *  index 9 = ring 1 (250mm). Beyond the last entry = miss (ring 0). */
val RING_RADII_MM: DoubleArray =
    doubleArrayOf(25.0, 50.0, 75.0, 100.0, 125.0, 150.0, 175.0, 200.0, 225.0, 250.0)

data class HitScore(
    val centerXpx: Float,
    val centerYpx: Float,
    val distancePx: Float,
    val distanceMm: Double,
    val ring: Int,
    val isInnerTen: Boolean,
)

/**
 * Score each detection by the distance from its bounding-box centre to
 * the centre of the source image, converted to millimetres via
 * [mmPerPx]. Returned in highest-score-first order so logs are readable.
 */
fun computeHitScores(
    detections: List<Detection>,
    centerX: Float,
    centerY: Float,
    mmPerPx: Double,
): List<HitScore> {
    if (detections.isEmpty()) return emptyList()
    return detections.map { d ->
        val cx = (d.left + d.right) / 2f
        val cy = (d.top + d.bottom) / 2f
        val dx = cx - centerX
        val dy = cy - centerY
        val distPx = sqrt(dx * dx + dy * dy)
        val distMm = distPx.toDouble() * mmPerPx
        HitScore(
            centerXpx = cx,
            centerYpx = cy,
            distancePx = distPx,
            distanceMm = distMm,
            ring = ringForDistance(distMm),
            isInnerTen = distMm <= INNER_TEN_RADIUS_MM,
        )
    }.sortedWith(
        compareByDescending<HitScore> { it.isInnerTen }
            .thenByDescending { it.ring }
            .thenBy { it.distanceMm }
    )
}

/**
 * Score each detection in the frontal target plane recovered from the
 * fitted ellipse. The projected 7-ring is an ellipse `(centerX, centerY,
 * semiMajor, semiMinor, rotationRad)`; we rotate hole offsets so the
 * major axis aligns with +x, then stretch the minor-axis component back
 * by `semiMajor / semiMinor` so the ellipse becomes the original 7-ring
 * circle. Distances are then converted to mm via `mmPerPx`, which is
 * derived from `semiMajor`.
 */
fun computeHitScores(
    detections: List<Detection>,
    calibration: TargetCalibration,
): List<HitScore> {
    if (detections.isEmpty()) return emptyList()
    val theta = calibration.rotationRad.toDouble()
    val cosT = cos(theta)
    val sinT = sin(theta)
    val stretch = if (calibration.semiMinorPx > 0f)
        (calibration.semiMajorPx / calibration.semiMinorPx).toDouble()
    else 1.0
    return detections.map { d ->
        val cx = (d.left + d.right) / 2f
        val cy = (d.top + d.bottom) / 2f
        val dx = (cx - calibration.centerX).toDouble()
        val dy = (cy - calibration.centerY).toDouble()
        // Rotate by -theta so the major axis aligns with +x.
        val xR = dx * cosT + dy * sinT
        val yR = -dx * sinT + dy * cosT
        // Stretch minor-axis component to undo foreshortening.
        val yC = yR * stretch
        val distPx = sqrt(xR * xR + yC * yC)
        val distMm = distPx * calibration.mmPerPx
        HitScore(
            centerXpx = cx,
            centerYpx = cy,
            distancePx = distPx.toFloat(),
            distanceMm = distMm,
            ring = ringForDistance(distMm),
            isInnerTen = distMm <= INNER_TEN_RADIUS_MM,
        )
    }.sortedWith(
        compareByDescending<HitScore> { it.isInnerTen }
            .thenByDescending { it.ring }
            .thenBy { it.distanceMm }
    )
}

private fun ringForDistance(distMm: Double): Int {
    // RING_RADII_MM[0] is ring 10, [9] is ring 1. First index whose radius
    // contains the hit wins; nothing matched = miss (ring 0).
    for (i in RING_RADII_MM.indices) {
        if (distMm <= RING_RADII_MM[i]) return 10 - i
    }
    return 0
}
