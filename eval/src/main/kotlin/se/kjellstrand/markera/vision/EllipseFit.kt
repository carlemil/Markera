package se.kjellstrand.markera.vision

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ellipse recovered from the principal second-order moments of a blob's
 * pixel distribution. For a uniformly-filled ellipse, the central moments
 * `μ20, μ02, μ11` satisfy
 *
 *   μ20 + μ02 = (a² + b²) / 4
 *   (a² − b²) / 4 = √((μ20 − μ02)² + 4·μ11²)
 *   2·θ           = atan2(2·μ11, μ20 − μ02)
 *
 * so axes + rotation come straight from sums over the blob's pixels.
 *
 * Robust to single-pixel edge noise (since *every* foreground pixel
 * contributes to the moment sums, not just the perimeter).
 */
data class FittedEllipse(
    val cx: Float,
    val cy: Float,
    val semiMajor: Float,
    val semiMinor: Float,
    /** Angle of the major axis from +x, in radians. */
    val rotationRad: Float,
)

fun fitEllipseFromBlob(
    labels: IntArray,
    width: Int,
    labelTarget: Int,
    bboxMinX: Int,
    bboxMaxX: Int,
    bboxMinY: Int,
    bboxMaxY: Int,
): FittedEllipse? {
    var n = 0L
    var sumX = 0.0
    var sumY = 0.0
    var sumX2 = 0.0
    var sumY2 = 0.0
    var sumXY = 0.0
    for (y in bboxMinY..bboxMaxY) {
        val rowBase = y * width
        for (x in bboxMinX..bboxMaxX) {
            if (labels[rowBase + x] != labelTarget) continue
            n++
            sumX += x
            sumY += y
            sumX2 += x.toDouble() * x
            sumY2 += y.toDouble() * y
            sumXY += x.toDouble() * y
        }
    }
    if (n < 8) return null
    val cx = sumX / n
    val cy = sumY / n
    val mu20 = sumX2 / n - cx * cx
    val mu02 = sumY2 / n - cy * cy
    val mu11 = sumXY / n - cx * cy

    val sum = mu20 + mu02
    val diff = mu20 - mu02
    val delta = sqrt(diff * diff + 4.0 * mu11 * mu11)
    val aSq = 2.0 * (sum + delta)
    val bSq = 2.0 * (sum - delta)
    if (aSq <= 0.0 || bSq <= 0.0) return null

    val a = sqrt(aSq)
    val b = sqrt(bSq)
    val theta = 0.5 * atan2(2.0 * mu11, diff)
    return FittedEllipse(
        cx = cx.toFloat(),
        cy = cy.toFloat(),
        semiMajor = a.toFloat(),
        semiMinor = b.toFloat(),
        rotationRad = theta.toFloat(),
    )
}
