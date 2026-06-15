package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A gradient edge pixel kept as a rim candidate. */
class EdgePix(val x: Double, val y: Double, val mag: Double)

class RansacRingResult(
    val ellipse: FittedEllipse?,
    val inliers: List<EdgePix>,
    val candidates: List<EdgePix>,
    val seedCx: Double,
    val seedCy: Double,
    val seedR: Double,
    val coverage: Double,
)

/** Normalised radial residual (~geometric distance) of a point to ellipse [e]. */
private fun ellipseResidual(e: FittedEllipse, px: Double, py: Double): Double {
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

/**
 * Detect the black/white 6/7 rim as an ellipse using gradient edges + RANSAC.
 *
 * 1. Seed a rough centre (largest central dark blob) and radius (angle-averaged
 *    luminance profile).
 * 2. Sobel gradient; keep edge pixels in a broad radius band whose gradient
 *    points **outward and radially** (the rim is dark inside / light outside) —
 *    this discards digits (non-radial), shadows and most ring-line/paster edges.
 * 3. RANSAC: sample 6 candidates, fit a conic, gate by centre/size/aspect prior,
 *    score by magnitude-weighted inliers. The rim is the longest, strongest,
 *    roundest consensus, so it wins.
 * 4. Refit on inliers; reject if angular coverage is too low (a partial arc).
 */
fun detectBlackRingRansac(
    gray: ByteArray,
    width: Int,
    height: Int,
    guideRadiusPx: Float,
    seed: Long = 12345L,
): RansacRingResult {
    val minAxis = min(width, height)
    val imgCx = width / 2.0
    val imgCy = height / 2.0
    val threshold = otsuThreshold(gray, width, height)
    val minArea = (PI * (0.12 * minAxis) * (0.12 * minAxis)).toInt().coerceAtLeast(16)
    val blobs = if (threshold > 0) findDarkBlobs(gray, width, height, threshold, minArea).blobs else emptyList()
    val seedBlob = blobs.maxByOrNull { b ->
        val dx = b.centroidX - imgCx
        val dy = b.centroidY - imgCy
        // big and central
        b.pixelCount.toDouble() - 8.0 * sqrt(dx * dx + dy * dy)
    }
    val seedCx = seedBlob?.centroidX?.toDouble() ?: imgCx
    val seedCy = seedBlob?.centroidY?.toDouble() ?: imgCy
    val profR = estimateDiskRadius(gray, width, height, seedCx, seedCy, 0.12 * minAxis, 0.49 * minAxis)
    val seedR = when {
        profR > 0 -> profR
        seedBlob != null -> sqrt(seedBlob.pixelCount.toDouble() / PI)
        else -> guideRadiusPx.toDouble()
    }

    // --- gradient edge candidates in the band, radial & outward ---
    val rLo = 0.55 * seedR
    val rHi = min(1.4 * seedR, 0.5 * minAxis - 2.0)
    fun g(xx: Int, yy: Int) = gray[yy * width + xx].toInt() and 0xFF
    val x0 = max(1, (seedCx - rHi).toInt())
    val x1 = min(width - 2, (seedCx + rHi).toInt())
    val y0 = max(1, (seedCy - rHi).toInt())
    val y1 = min(height - 2, (seedCy + rHi).toInt())
    val cand = ArrayList<EdgePix>()
    var yy = y0
    while (yy <= y1) {
        var xx = x0
        while (xx <= x1) {
            val px = xx - seedCx
            val py = yy - seedCy
            val rr = hypot(px, py)
            if (rr in rLo..rHi) {
                val gx = (g(xx + 1, yy - 1) + 2 * g(xx + 1, yy) + g(xx + 1, yy + 1)) -
                    (g(xx - 1, yy - 1) + 2 * g(xx - 1, yy) + g(xx - 1, yy + 1))
                val gy = (g(xx - 1, yy + 1) + 2 * g(xx, yy + 1) + g(xx + 1, yy + 1)) -
                    (g(xx - 1, yy - 1) + 2 * g(xx, yy - 1) + g(xx + 1, yy - 1))
                val mag = hypot(gx.toDouble(), gy.toDouble())
                if (mag >= 40.0) {
                    val dot = gx * px + gy * py // gradient . outward
                    if (dot > 0.0) {
                        val cosang = dot / (mag * rr + 1e-9)
                        if (cosang >= 0.80) cand.add(EdgePix(xx.toDouble(), yy.toDouble(), mag))
                    }
                }
            }
            xx++
        }
        yy++
    }

    val rnd = Random(seed)
    val pts = if (cand.size > 7000) cand.shuffled(rnd).take(7000) else cand
    if (pts.size < 30) return RansacRingResult(null, emptyList(), pts, seedCx, seedCy, seedR, 0.0)

    // --- RANSAC ---
    val eps = 0.012 * seedR + 2.0
    var best: FittedEllipse? = null
    var bestScore = 0.0
    repeat(2500) {
        val idxs = HashSet<Int>()
        while (idxs.size < 6) idxs.add(rnd.nextInt(pts.size))
        val sample = idxs.map { doubleArrayOf(pts[it].x, pts[it].y) }
        val e = fitEllipseDirect(sample) ?: return@repeat
        if (hypot(e.cx - seedCx, e.cy - seedCy) > 0.35 * seedR) return@repeat
        if (e.semiMajor < 0.6 * seedR || e.semiMajor > 1.4 * seedR) return@repeat
        if (e.semiMajor / e.semiMinor > 2.5) return@repeat
        var score = 0.0
        for (p in pts) if (ellipseResidual(e, p.x, p.y) < eps) score += p.mag
        if (score > bestScore) {
            bestScore = score
            best = e
        }
    }
    var ell = best ?: return RansacRingResult(null, emptyList(), pts, seedCx, seedCy, seedR, 0.0)

    // --- refine: refit on inliers ---
    var inliers: List<EdgePix> = emptyList()
    repeat(3) {
        inliers = pts.filter { ellipseResidual(ell, it.x, it.y) < eps }
        if (inliers.size >= 6) {
            fitEllipseDirect(inliers.map { doubleArrayOf(it.x, it.y) })?.let { ell = it }
        }
    }
    inliers = pts.filter { ellipseResidual(ell, it.x, it.y) < eps }

    // --- angular coverage gate ---
    val bins = BooleanArray(36)
    for (p in inliers) {
        var bi = (((atan2(p.y - ell.cy, p.x - ell.cx) + PI) / (2 * PI)) * 36).toInt()
        if (bi < 0) bi = 0
        if (bi > 35) bi = 35
        bins[bi] = true
    }
    val coverage = bins.count { it } / 36.0
    val ok = coverage >= 0.6 && ell.semiMajor in (0.1 * minAxis)..(0.5 * minAxis)
    return RansacRingResult(if (ok) ell else null, inliers, pts, seedCx, seedCy, seedR, coverage)
}
