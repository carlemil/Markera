package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Predict the 6/7 black/white boundary ellipse from the recognised ring digits.
 *
 * The scoring rings are concentric, equally-spaced circles; under mild
 * perspective they project to homothetic ellipses that share the [centre],
 * rotation and aspect ratio (scaled copies — not confocal). A digit of value v
 * sits in band v at target radius `rho_v = R67 - (v - 6.5)*w`, so scaling that
 * digit's position about the centre by `R67/rho_v = 1 / (1 - (v-6.5)*q)`, with
 * `q = w/R67`, maps every digit onto the 6/7 boundary ellipse. The ring-width
 * fraction q is unknown but self-calibrates: we sweep q and keep the value whose
 * mapped points fit a single ellipse best. Uses the digit *labels*, so the
 * result is guaranteed to be the 6/7 ring, not some other concentric ring.
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

    var best: FittedEllipse? = null
    var bestRms = Double.MAX_VALUE
    var q = 0.04
    while (q <= 0.30) {
        val pts = ArrayList<DoubleArray>(usable.size)
        var ok = true
        for (d in usable) {
            val denom = 1.0 - (d.value - 6.5) * q
            if (denom <= 0.05) {
                ok = false
                break
            }
            val scale = 1.0 / denom
            pts.add(doubleArrayOf(cx + (d.cx - cx) * scale, cy + (d.cy - cy) * scale))
        }
        if (ok) {
            val e = fitEllipseDirect(pts)
            if (e != null) {
                var s2 = 0.0
                for (p in pts) {
                    val r = ringResidual(e, p[0], p[1])
                    s2 += r * r
                }
                val rms = sqrt(s2 / pts.size)
                if (rms < bestRms) {
                    bestRms = rms
                    best = e
                }
            }
        }
        q += 0.005
    }
    return best
}

/**
 * Snap a digit-predicted 6/7 ellipse [predicted] to the real black->white edge:
 * sample the strongest dark->light gradient in a tight band around it and refit.
 * Falls back to [predicted] if the edge isn't found or the refit drifts too far
 * (occluded / cut-off rim), so the digit prediction is never made worse.
 */
fun refine67ToEdge(
    gray: ByteArray,
    width: Int,
    height: Int,
    predicted: FittedEllipse,
): FittedEllipse {
    val threshold = otsuThreshold(gray, width, height)
    if (threshold <= 0) return predicted
    val pts = radialEdgePointsAround(gray, width, height, predicted, threshold, bandFrac = 0.12)
    if (pts.size < 24) return predicted
    val refit = fitEllipseRobust(pts)?.first ?: return predicted
    val dc = hypot(refit.cx - predicted.cx, refit.cy - predicted.cy)
    val dr = abs(refit.semiMajor - predicted.semiMajor)
    return if (dc < 0.15 * predicted.semiMajor && dr < 0.2 * predicted.semiMajor) refit else predicted
}

private fun ringResidual(e: FittedEllipse, px: Double, py: Double): Double {
    val a = e.semiMajor.toDouble()
    val b = e.semiMinor.toDouble()
    if (a <= 0.0 || b <= 0.0) return Double.MAX_VALUE
    val dx = px - e.cx
    val dy = py - e.cy
    val ct = cos(-e.rotationRad.toDouble())
    val st = sin(-e.rotationRad.toDouble())
    val xr = dx * ct - dy * st
    val yr = dx * st + dy * ct
    val q = sqrt((xr / a) * (xr / a) + (yr / b) * (yr / b))
    return abs(q - 1.0) * ((a + b) / 2.0)
}
