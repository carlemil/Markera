package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fits an ellipse to the black->white rim of the 6/7 ring by sampling the edge
 * directly (radial scan) and fitting a conic to those points, rather than to
 * the filled blob's moments. Edge sampling ignores interior holes/pasters and
 * the conic fit follows the perspective-distorted rim.
 */

/** Result of [calibrateBlackRingEdge]: the fit (may be null) plus debug data. */
class EdgeFitResult(
    val calib: TargetCalibration?,
    val points: List<DoubleArray>,
    val seedCx: Double,
    val seedCy: Double,
    val seedR: Double,
    val rms: Double,
)

/** Bilinear luminance sample, clamped to the image. */
private fun sampleLum(gray: ByteArray, width: Int, height: Int, x: Double, y: Double): Double {
    val xi = x.coerceIn(0.0, (width - 1).toDouble())
    val yi = y.coerceIn(0.0, (height - 1).toDouble())
    val x0 = xi.toInt()
    val y0 = yi.toInt()
    val x1 = min(x0 + 1, width - 1)
    val y1 = min(y0 + 1, height - 1)
    val fx = xi - x0
    val fy = yi - y0
    val p00 = (gray[y0 * width + x0].toInt() and 0xFF).toDouble()
    val p10 = (gray[y0 * width + x1].toInt() and 0xFF).toDouble()
    val p01 = (gray[y1 * width + x0].toInt() and 0xFF).toDouble()
    val p11 = (gray[y1 * width + x1].toInt() and 0xFF).toDouble()
    val a = p00 + (p10 - p00) * fx
    val b = p01 + (p11 - p01) * fx
    return a + (b - a) * fy
}

/**
 * Estimate the black-disk rim radius from an angle-averaged radial luminance
 * profile about (cx, cy). Printed ring lines, digits, holes and pasters average
 * out, leaving one strong dark->light step at the rim. Returns the radius of the
 * steepest rise within [rLo, rHi], or -1 if none stands out.
 */
fun estimateDiskRadius(
    gray: ByteArray,
    width: Int,
    height: Int,
    cx: Double,
    cy: Double,
    rLo: Double,
    rHi: Double,
    rays: Int = 180,
): Double {
    val n = rHi.toInt() + 1
    if (n < 4) return -1.0
    val prof = DoubleArray(n)
    for (ri in 0 until n) {
        var s = 0.0
        for (a in 0 until rays) {
            val ang = 2.0 * PI * a / rays
            s += sampleLum(gray, width, height, cx + cos(ang) * ri, cy + sin(ang) * ri)
        }
        prof[ri] = s / rays
    }
    val k = 6
    var bestR = -1.0
    var bestSlope = 0.0
    var r = max(rLo.toInt(), k)
    while (r < min(rHi.toInt(), n - k - 1)) {
        val slope = prof[r + k] - prof[r - k]
        if (slope > bestSlope) {
            bestSlope = slope
            bestR = r.toDouble()
        }
        r++
    }
    return bestR
}

/**
 * Cast [rays] rays and, on each, take the strongest dark->light edge within a
 * narrow band around the expected rim radius [expectedR] (+/- [bandFrac]). The
 * band excludes interior ring lines (smaller radius) and exterior digits/paper
 * (larger radius), so the rim is unambiguous. Sub-pixel via the [threshold]
 * crossing at the gradient peak. Returns [x, y].
 */
fun radialEdgePoints(
    gray: ByteArray,
    width: Int,
    height: Int,
    seedCx: Double,
    seedCy: Double,
    expectedR: Double,
    threshold: Int,
    bandFrac: Double = 0.22,
    rays: Int = 360,
): List<DoubleArray> {
    val rLo = max(2.0, (1.0 - bandFrac) * expectedR)
    val rHi = min((1.0 + bandFrac) * expectedR, 0.5 * min(width, height) - 2.0)
    if (rHi <= rLo + 2) return emptyList()
    val thr = threshold.toDouble()
    val out = ArrayList<DoubleArray>(rays)
    for (i in 0 until rays) {
        val ang = 2.0 * PI * i / rays
        val dx = cos(ang)
        val dy = sin(ang)
        // Find the radius of the strongest positive (dark->light) gradient.
        var bestSlope = 0.0
        var bestR = -1.0
        var prevL = sampleLum(gray, width, height, seedCx + dx * rLo, seedCy + dy * rLo)
        var r = rLo + 1.0
        while (r <= rHi) {
            val l = sampleLum(gray, width, height, seedCx + dx * r, seedCy + dy * r)
            val slope = l - prevL
            if (slope > bestSlope && (prevL <= thr || l > thr)) {
                bestSlope = slope
                // sub-pixel: where it crosses thr between r-1 and r, else midpoint
                bestR = if (prevL <= thr && l > thr && l != prevL) {
                    (r - 1.0) + (thr - prevL) / (l - prevL)
                } else {
                    r - 0.5
                }
            }
            prevL = l
            r += 1.0
        }
        // Require a real edge (contrast), not flat noise.
        if (bestR > 0.0 && bestSlope > 12.0) {
            out.add(doubleArrayOf(seedCx + dx * bestR, seedCy + dy * bestR))
        }
    }
    return out
}

/** Radius of ellipse [e] from its centre at image-frame polar angle [theta]. */
private fun ellipseRadiusAt(e: FittedEllipse, theta: Double): Double {
    val a = e.semiMajor.toDouble()
    val b = e.semiMinor.toDouble()
    val c = cos(theta - e.rotationRad.toDouble())
    val s = sin(theta - e.rotationRad.toDouble())
    val denom = sqrt(b * b * c * c + a * a * s * s)
    return if (denom > 1e-9) a * b / denom else a
}

/**
 * Re-sample the rim with each ray's band centred on ellipse [e]'s own radius at
 * that angle, so a tilted (elliptical) rim is followed tightly. Used as a second
 * pass after a first circular-band fit.
 */
fun radialEdgePointsAround(
    gray: ByteArray,
    width: Int,
    height: Int,
    e: FittedEllipse,
    threshold: Int,
    bandFrac: Double = 0.15,
    rays: Int = 360,
): List<DoubleArray> {
    val ox = e.cx.toDouble()
    val oy = e.cy.toDouble()
    val thr = threshold.toDouble()
    val out = ArrayList<DoubleArray>(rays)
    for (i in 0 until rays) {
        val ang = 2.0 * PI * i / rays
        val dx = cos(ang)
        val dy = sin(ang)
        val expR = ellipseRadiusAt(e, ang)
        val rLo = max(2.0, (1.0 - bandFrac) * expR)
        val rHi = min((1.0 + bandFrac) * expR, 0.5 * min(width, height) - 2.0)
        if (rHi <= rLo + 2) continue
        var bestSlope = 0.0
        var bestR = -1.0
        var prevL = sampleLum(gray, width, height, ox + dx * rLo, oy + dy * rLo)
        var r = rLo + 1.0
        while (r <= rHi) {
            val l = sampleLum(gray, width, height, ox + dx * r, oy + dy * r)
            val slope = l - prevL
            if (slope > bestSlope && (prevL <= thr || l > thr)) {
                bestSlope = slope
                bestR = if (prevL <= thr && l > thr && l != prevL) {
                    (r - 1.0) + (thr - prevL) / (l - prevL)
                } else {
                    r - 0.5
                }
            }
            prevL = l
            r += 1.0
        }
        if (bestR > 0.0 && bestSlope > 12.0) out.add(doubleArrayOf(ox + dx * bestR, oy + dy * bestR))
    }
    return out
}

/**
 * Direct least-squares ellipse fit (Bookstein constraint A^2 + B^2/2 + C^2 = 1).
 * Eliminating the linear part leaves a symmetric 3x3 generalized eigenproblem
 * M u = lambda H u, reduced to a standard symmetric one and solved with
 * [jacobiSymmetric3]. Picks the lowest-residual eigenvector that is an ellipse.
 */
fun fitEllipseDirect(points: List<DoubleArray>): FittedEllipse? {
    if (points.size < 6) return null
    var mx = 0.0
    var my = 0.0
    for (p in points) {
        mx += p[0]
        my += p[1]
    }
    mx /= points.size
    my /= points.size

    val s1 = DoubleArray(9)
    val s2 = DoubleArray(9)
    val s3 = DoubleArray(9)
    for (p in points) {
        val x = p[0] - mx
        val y = p[1] - my
        val d1 = doubleArrayOf(x * x, x * y, y * y)
        val d2 = doubleArrayOf(x, y, 1.0)
        for (i in 0..2) {
            for (j in 0..2) {
                s1[i * 3 + j] += d1[i] * d1[j]
                s2[i * 3 + j] += d1[i] * d2[j]
                s3[i * 3 + j] += d2[i] * d2[j]
            }
        }
    }
    val s2m = Mat3(s2)
    val s3inv = mat3Inverse(Mat3(s3)) ?: return null
    val s2t = mat3Transpose(s2m)
    val reduce = mat3Multiply(s2m, mat3Multiply(s3inv, s2t)) // S2 S3^-1 S2^T
    val mMat = Mat3(DoubleArray(9) { s1[it] - reduce.data[it] }) // symmetric

    // H = diag(1, 0.5, 1); Linv = diag(1, sqrt2, 1). Asym = Linv M Linv.
    val linv = doubleArrayOf(1.0, sqrt(2.0), 1.0)
    val asym = DoubleArray(9)
    for (i in 0..2) for (j in 0..2) asym[i * 3 + j] = linv[i] * mMat[i, j] * linv[j]
    val eig = jacobiSymmetric3(Mat3(asym))

    // Lowest eigenvalue (column 2, sorted descending) first; u = Linv * y.
    var u: DoubleArray? = null
    for (col in intArrayOf(2, 1, 0)) {
        val y = doubleArrayOf(eig.vectors[0, col], eig.vectors[1, col], eig.vectors[2, col])
        val cand = doubleArrayOf(linv[0] * y[0], linv[1] * y[1], linv[2] * y[2])
        if (cand[1] * cand[1] - 4.0 * cand[0] * cand[2] < 0.0) { // B^2 - 4AC < 0 => ellipse
            u = cand
            break
        }
    }
    val uu = u ?: return null
    val wv = mat3MulVec(s3inv, mat3MulVec(s2t, Vec3(uu[0], uu[1], uu[2])))
    return conicToEllipse(uu[0], uu[1], uu[2], -wv.x, -wv.y, -wv.z, mx, my)
}

/** A x^2 + B xy + C y^2 + D x + E y + F = 0 (recentred by [ox],[oy]) -> ellipse. */
private fun conicToEllipse(
    a: Double,
    b: Double,
    c: Double,
    d: Double,
    e: Double,
    f: Double,
    ox: Double,
    oy: Double,
): FittedEllipse? {
    if (b * b - 4.0 * a * c >= 0.0) return null
    val a11 = a
    val a12 = b / 2.0
    val a22 = c
    val det2 = a11 * a22 - a12 * a12
    if (abs(det2) < 1e-20) return null
    val rhsx = -d / 2.0
    val rhsy = -e / 2.0
    val xc = (a22 * rhsx - a12 * rhsy) / det2
    val yc = (a11 * rhsy - a12 * rhsx) / det2

    val detM0 = det3(a, b / 2.0, d / 2.0, b / 2.0, c, e / 2.0, d / 2.0, e / 2.0, f)
    val tr = a + c
    val s = sqrt((a - c) * (a - c) + b * b)
    val l1 = (tr - s) / 2.0
    val l2 = (tr + s) / 2.0
    if (l1 == 0.0 || l2 == 0.0) return null
    val ax1Sq = -detM0 / (det2 * l1)
    val ax2Sq = -detM0 / (det2 * l2)
    if (ax1Sq <= 0.0 || ax2Sq <= 0.0) return null
    val ax1 = sqrt(ax1Sq)
    val ax2 = sqrt(ax2Sq)

    fun eigvec(lambda: Double): Pair<Double, Double> {
        var vx = a12
        var vy = lambda - a11
        if (abs(vx) < 1e-12 && abs(vy) < 1e-12) {
            vx = lambda - a22
            vy = a12
        }
        return vx to vy
    }

    val majorLambda = if (ax1 >= ax2) l1 else l2
    val (vx, vy) = eigvec(majorLambda)
    return FittedEllipse(
        cx = (xc + ox).toFloat(),
        cy = (yc + oy).toFloat(),
        semiMajor = max(ax1, ax2).toFloat(),
        semiMinor = min(ax1, ax2).toFloat(),
        rotationRad = atan2(vy, vx).toFloat(),
    )
}

/** Normalised radial residual of a point to [e] (~geometric distance to rim). */
private fun radialResidual(e: FittedEllipse, px: Double, py: Double): Double {
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

/** Fit, then drop the worst-residual points and refit, [passes] times. */
fun fitEllipseRobust(
    points: List<DoubleArray>,
    passes: Int = 3,
    keepFrac: Double = 0.85,
): Pair<FittedEllipse, Double>? {
    var pts = points
    var fit = fitEllipseDirect(pts) ?: return null
    repeat(passes) {
        val res = pts.map { radialResidual(fit, it[0], it[1]) }
        val order = pts.indices.sortedBy { res[it] }
        val keep = max(6, (pts.size * keepFrac).toInt())
        if (keep >= pts.size) return@repeat
        val kept = order.take(keep).map { pts[it] }
        val next = fitEllipseDirect(kept) ?: return@repeat
        pts = kept
        fit = next
    }
    val rms = sqrt(pts.map { radialResidual(fit, it[0], it[1]).let { r -> r * r } }.average())
    return fit to rms
}

/**
 * Edge-based black-ring calibration: seed a rough centre/radius from a coarse
 * dark blob, sample the rim by radial scan, and robustly fit an ellipse to it.
 */
fun calibrateBlackRingEdge(
    gray: ByteArray,
    width: Int,
    height: Int,
    guideRadiusPx: Float,
): EdgeFitResult {
    val empty = EdgeFitResult(null, emptyList(), width / 2.0, height / 2.0, guideRadiusPx.toDouble(), 0.0)
    if (width <= 0 || height <= 0 || gray.size < width * height) return empty
    val threshold = otsuThreshold(gray, width, height)
    if (threshold <= 0) return empty

    val minAxis = min(width, height)
    val imgCx = width / 2.0
    val imgCy = height / 2.0
    val minArea = (PI * (0.15 * minAxis) * (0.15 * minAxis)).toInt().coerceAtLeast(16)
    val cc = findDarkBlobs(gray, width, height, threshold, minArea)
    val seed = cc.blobs.maxByOrNull { bl ->
        val rEst = sqrt(bl.pixelCount.toDouble() / PI)
        val dx = bl.centroidX - imgCx
        val dy = bl.centroidY - imgCy
        -(abs(rEst - guideRadiusPx) + sqrt(dx * dx + dy * dy))
    }
    val seedCx = seed?.centroidX?.toDouble() ?: imgCx
    val seedCy = seed?.centroidY?.toDouble() ?: imgCy

    // Robust rim radius from the angle-averaged profile (ring lines/digits/holes
    // average out). Search broadly; fall back to the blob radius / guide radius.
    val blobR = seed?.let { sqrt(it.pixelCount.toDouble() / PI) } ?: guideRadiusPx.toDouble()
    val profR = estimateDiskRadius(gray, width, height, seedCx, seedCy, 0.12 * minAxis, 0.49 * minAxis)
    val seedR = if (profR > 0) profR else blobR

    var pts = radialEdgePoints(gray, width, height, seedCx, seedCy, seedR, threshold)
    if (pts.size < 24) return EdgeFitResult(null, pts, seedCx, seedCy, seedR, 0.0)
    var fitted = fitEllipseRobust(pts) ?: return EdgeFitResult(null, pts, seedCx, seedCy, seedR, 0.0)
    // Second pass: re-anchor each ray's band on the first ellipse's radius so a
    // tilted rim (radius varies with angle) is followed tightly.
    run {
        val e0 = fitted.first
        val pts2 = radialEdgePointsAround(gray, width, height, e0, threshold)
        if (pts2.size >= 24) {
            val f2 = fitEllipseRobust(pts2)
            if (f2 != null) {
                pts = pts2
                fitted = f2
            }
        }
    }
    val (e, rms) = fitted
    if (e.semiMajor < 0.1 * minAxis || e.semiMajor > 0.5 * minAxis) {
        return EdgeFitResult(null, pts, seedCx, seedCy, seedR, rms)
    }
    // Quality gate: a clean rim sits within a few % of the ellipse; a high RMS
    // means the points were paster/paper edges, not the ring — reject honestly.
    if (rms > 0.06 * e.semiMajor) {
        return EdgeFitResult(null, pts, seedCx, seedCy, seedR, rms)
    }
    val calib = TargetCalibration(
        centerX = e.cx,
        centerY = e.cy,
        semiMajorPx = e.semiMajor,
        semiMinorPx = e.semiMinor,
        rotationRad = e.rotationRad,
        mmPerPx = TARGET_BLACK_RING_RADIUS_MM / e.semiMajor,
        confidence = (e.semiMinor / e.semiMajor).coerceIn(0f, 1f),
    )
    return EdgeFitResult(calib, pts, seedCx, seedCy, seedR, rms)
}
