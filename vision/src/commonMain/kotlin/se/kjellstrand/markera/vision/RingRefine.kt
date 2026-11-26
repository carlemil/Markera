package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The refined geometry and how well the photo backed it. */
data class RefinedGeometry(
    val centre: CentreEstimate,
    val ring: FittedEllipse,
    /** Edge points the last fit used. */
    val points: Int,
    /** Share of them within 1 mm of their ring after the fit. */
    val inlierShare: Double,
    /** How far the centre moved, in target mm. */
    val centreMoveMm: Double,
)

/** The thin white rings printed inside the black (10/9/8/7 edges; 12.5 mm is the inner X). */
private val WHITE_LINES_MM = doubleArrayOf(12.5, 25.0, 50.0, 75.0)
private const val R67 = TARGET_BLACK_RING_RADIUS_MM
private const val ITERATIONS = 4
private const val RAY_STEP_DEG = 0.5

/** The first rounds only need to get close: a quarter of the rays, so a quarter of the reads. */
private const val COARSE_RAY_STEP_DEG = 2.0
private const val RADIUS_FROM_MM = 5.0
private const val RADIUS_TO_MM = 112.0
private const val RADIUS_STEP_MM = 0.2
private const val WINDOW_MM = 6.0
private const val BLUR_SIGMA_PX = 1.5
private const val SOFT_L1_SCALE_MM = 0.5
private const val EDGE_REFITS = 3

/** Below these the photo did not back the fit, and the unrefined geometry stays. */
private const val MIN_POINT_SHARE = 0.4
private const val MIN_INLIER_SHARE = 0.7
private const val MAX_CENTRE_MOVE_MM = 8.0

/**
 * Refines the scan's [centre] and 6/7 [ring] against the photo: the black's outer edge and the
 * thin white lines inside it (the 10, 9, 8 and 7 edges, and the inner X), all at once, through a
 * full homography with the centre free. Null when the photo does not back it (too few edge points,
 * too few of them on their ring after the fit, a centre moved more than 8 mm, or an implausible
 * result); the caller then keeps what it had.
 *
 * Each of four rounds samples rays out from the current map's centre in target mm (every 2° for the
 * first three, every 0.5° for the last) and keeps,
 * per ray, the strongest dark-to-light step within 6 mm of the 100 mm edge and the brightest
 * point within 6 mm of each white line (when it stands 25 grey levels over the window's median),
 * each to a sub-sample by a parabola. The map is then fitted so each point lies on its ring
 * (robust soft-L1, 0.5 mm). The centre is that map's; the 6/7 ring is then refitted to the black's
 * edge alone around it ([refitRing]). On 27 hand-labelled photos the mean centre error was 0.21 mm
 * and the mean ring gap 0.28 mm (the stored geometry: 0.61 mm ring), and it converges from starts
 * up to about 6 mm off.
 *
 * [gray] is the photo's luma, [width] x [height], row-major.
 */
fun refineGeometry(
    gray: ByteArray,
    width: Int,
    height: Int,
    centre: CentreEstimate,
    ring: FittedEllipse,
): RefinedGeometry? {
    if (centre.method == CentreMethod.NONE || centre.method == CentreMethod.MANUAL) return null
    val start = TargetPlane.of(centre, ring) ?: return null
    val image = BlurredRegion.around(gray, width, height, start) ?: return null
    var h = start.matrix
    var points: EdgePoints? = null
    repeat(ITERATIONS) {
        val step = if (it < ITERATIONS - 1) COARSE_RAY_STEP_DEG else RAY_STEP_DEG
        val pts = edgePoints(image, h, step) ?: return null
        if (pts.size < MIN_POINT_SHARE * rayCount(step) * (1 + WHITE_LINES_MM.size)) return null
        h = fitHomography(h, pts) ?: return null
        points = pts
    }
    val plane = TargetPlane.fromMatrix(h) ?: return null
    val (cx, cy) = plane.centreImage()
    val refinedRing = plane.circleOutline(R67) ?: return null
    val refinedCentre = centre.copy(x = cx.toFloat(), y = cy.toFloat())
    if (!plausibleGeometry(refinedCentre, refinedRing)) return null
    val pts = points ?: return null
    val inliers = (0 until pts.size).count { i ->
        val (u, v) = apply(h, pts.x[i], pts.y[i])
        abs(hypot(u, v) - pts.r[i]) < 1.0
    }
    val inlierShare = inliers.toDouble() / pts.size
    // How far the old centre sits from the new one, in the new map's mm.
    val (ox, oy) = apply(h, centre.x.toDouble(), centre.y.toDouble())
    val move = hypot(ox, oy)
    if (inlierShare < MIN_INLIER_SHARE || !(move <= MAX_CENTRE_MOVE_MM)) return null
    return RefinedGeometry(refinedCentre, refitRing(image, refinedCentre, refinedRing), pts.size, inlierShare, move)
}

/**
 * The 6/7 ring refitted to the black's edge alone, around the refined [centre]: [EDGE_REFITS]
 * passes, each sampling the edge through the current centre and ring (every 0.5°) and fitting a
 * plain least-squares ellipse to those points. The homography spreads its error over the white
 * lines too; this puts the ring back on the edge (on the hand labels its mean gap fell from 0.35 to
 * 0.28 mm) without touching the centre. A pass that finds too few points or an implausible ellipse
 * keeps the ring it had.
 */
private fun refitRing(image: BlurredRegion, centre: CentreEstimate, ring: FittedEllipse): FittedEllipse {
    var current = ring
    repeat(EDGE_REFITS) {
        val plane = TargetPlane.of(centre, current) ?: return current
        val pts = edgePoints(image, plane.matrix, RAY_STEP_DEG) ?: return current
        val idx = (0 until pts.size).filter { pts.r[it] == R67 }
        if (idx.size < MIN_POINT_SHARE * rayCount(RAY_STEP_DEG)) return current
        val fitted = fitEllipse(DoubleArray(idx.size) { pts.x[idx[it]] }, DoubleArray(idx.size) { pts.y[idx[it]] })
        if (fitted == null || !plausibleGeometry(centre, fitted)) return current
        current = fitted
    }
    return current
}

/**
 * Least-squares conic through the points under the trace constraint a + c = 1 (in coordinates
 * centred on their mean and scaled to unit spread, so the fit is invariant to translation, rotation
 * and scale), as an ellipse; null when the points do not make one.
 */
internal fun fitEllipse(xs: DoubleArray, ys: DoubleArray): FittedEllipse? {
    val n = xs.size
    if (n < 5) return null
    val mx = xs.average()
    val my = ys.average()
    var spread = 0.0
    for (i in 0 until n) spread += hypot(xs[i] - mx, ys[i] - my)
    spread /= n
    if (!(spread > 0.0)) return null
    // a(x² - y²) + b xy + d x + e y + f = -y², with c = 1 - a.
    val ata = Array(5) { DoubleArray(6) }
    val row = DoubleArray(5)
    for (i in 0 until n) {
        val x = (xs[i] - mx) / spread
        val y = (ys[i] - my) / spread
        row[0] = x * x - y * y; row[1] = x * y; row[2] = x; row[3] = y; row[4] = 1.0
        val rhs = -y * y
        for (a in 0 until 5) {
            for (b in 0 until 5) ata[a][b] += row[a] * row[b]
            ata[a][5] += row[a] * rhs
        }
    }
    val s = solveLinear(ata) ?: return null
    val a = s[0]
    val c = 1 - a
    val kn = doubleArrayOf(a, s[1] / 2, s[2] / 2, s[1] / 2, c, s[3] / 2, s[2] / 2, s[3] / 2, s[4])
    // Back to image px: the normalised point is T p, so the conic is Tᵀ K T.
    val t = doubleArrayOf(1 / spread, 0.0, -mx / spread, 0.0, 1 / spread, -my / spread, 0.0, 0.0, 1.0)
    val tt = doubleArrayOf(t[0], t[3], t[6], t[1], t[4], t[7], t[2], t[5], t[8])
    return ellipseOf(multiply(multiply(tt, kn), t))
}

private fun rayCount(stepDeg: Double): Int = (360.0 / stepDeg).toInt()

/** Edge points: image position and the target radius (mm) they belong on. */
private class EdgePoints(val x: DoubleArray, val y: DoubleArray, val r: DoubleArray, val size: Int)

/**
 * The photo blurred (Gaussian, sigma 1.5 px) over the target's bounding box, computed lazily: a
 * pixel is blurred (rows, then columns, each pass kept) the first time a ray reads it, so only the
 * bands around the rings cost anything. Samples outside the box or the image are NaN.
 */
private class BlurredRegion(
    private val gray: ByteArray,
    private val width: Int,
    private val height: Int,
    private val x0: Int,
    private val y0: Int,
    private val w: Int,
    private val h: Int,
) {
    private val radius = ceil(3 * BLUR_SIGMA_PX).toInt()
    private val kernel = DoubleArray(2 * radius + 1) {
        exp(-((it - radius) * (it - radius)) / (2 * BLUR_SIGMA_PX * BLUR_SIGMA_PX))
    }.let { k -> val sum = k.sum(); DoubleArray(k.size) { k[it] / sum } }

    /**
     * Row-blurred and fully blurred pixels of the box in 1/128 grey levels (16 bits, half a float's
     * memory: this runs beside the hole model), [UNSET] until first needed.
     */
    private val rows = ShortArray(w * h).also { it.fill(UNSET) }
    private val blurred = ShortArray(w * h).also { it.fill(UNSET) }

    private fun rowBlurred(x: Int, y: Int): Float {
        val i = y * w + x
        val cached = rows[i]
        if (cached != UNSET) return cached / SCALE
        val src = (y0 + y) * width
        var acc = 0.0
        for (k in -radius..radius) {
            val sx = (x0 + x + k).coerceIn(0, width - 1)
            acc += kernel[k + radius] * (gray[src + sx].toInt() and 0xFF)
        }
        return pack(acc).also { rows[i] = it } / SCALE
    }

    private fun pixel(x: Int, y: Int): Float {
        val i = y * w + x
        val cached = blurred[i]
        if (cached != UNSET) return cached / SCALE
        var acc = 0.0
        for (k in -radius..radius) {
            // Columns clamp at the image's edge, as the rows do (the box may stop short of it).
            val sy = (y0 + y + k).coerceIn(0, height - 1) - y0
            acc += kernel[k + radius] * rowBlurredAt(x, sy)
        }
        return pack(acc).also { blurred[i] = it } / SCALE
    }

    /** A row-blurred pixel that may lie above or below the box: computed directly, uncached. */
    private fun rowBlurredAt(x: Int, y: Int): Float {
        if (y in 0 until h) return rowBlurred(x, y)
        val src = (y0 + y) * width
        var acc = 0.0
        for (k in -radius..radius) {
            val sx = (x0 + x + k).coerceIn(0, width - 1)
            acc += kernel[k + radius] * (gray[src + sx].toInt() and 0xFF)
        }
        return acc.toFloat()
    }

    /** Bilinear sample at image px ([x], [y]); NaN off the region. */
    fun sample(x: Double, y: Double): Double {
        val fx = x - x0
        val fy = y - y0
        if (!(fx >= 0.0 && fy >= 0.0 && fx <= w - 1.0 && fy <= h - 1.0)) return Double.NaN
        val ix = min(floor(fx).toInt(), w - 2)
        val iy = min(floor(fy).toInt(), h - 2)
        val tx = fx - ix
        val ty = fy - iy
        val top = pixel(ix, iy) * (1 - tx) + pixel(ix + 1, iy) * tx
        val bottom = pixel(ix, iy + 1) * (1 - tx) + pixel(ix + 1, iy + 1) * tx
        return top * (1 - ty) + bottom * ty
    }

    companion object {
        private const val UNSET: Short = -1
        private const val SCALE = 128f

        private fun pack(grey: Double): Short = (grey * SCALE + 0.5).toInt().coerceIn(0, 255 * 128).toShort()

        fun around(gray: ByteArray, width: Int, height: Int, plane: TargetPlane): BlurredRegion? {
            val outline = plane.circleOutline(RADIUS_TO_MM + 3.0) ?: return null
            val c = cos(outline.rotationRad.toDouble())
            val s = sin(outline.rotationRad.toDouble())
            val a = outline.semiMajor.toDouble()
            val b = outline.semiMinor.toDouble()
            val halfW = sqrt(a * a * c * c + b * b * s * s)
            val halfH = sqrt(a * a * s * s + b * b * c * c)
            val x0 = max(0, floor(outline.cx - halfW).toInt())
            val y0 = max(0, floor(outline.cy - halfH).toInt())
            val x1 = min(width - 1, ceil(outline.cx + halfW).toInt())
            val y1 = min(height - 1, ceil(outline.cy + halfH).toInt())
            if (x1 - x0 < 8 || y1 - y0 < 8) return null
            return BlurredRegion(gray, width, height, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }
    }
}

/** The rays' edge points under map [h]; null when the map cannot be inverted. */
private fun edgePoints(image: BlurredRegion, h: DoubleArray, stepDeg: Double): EdgePoints? {
    val inv = invert(h) ?: return null
    val n = ((RADIUS_TO_MM - RADIUS_FROM_MM) / RADIUS_STEP_MM).toInt()
    val radii = DoubleArray(n) { RADIUS_FROM_MM + it * RADIUS_STEP_MM }
    val values = DoubleArray(n)
    // Only the windows around the edge and the lines are read (plus a neighbour each side).
    val edgeWindow = window(radii, R67, WINDOW_MM)
    val lineWindows = WHITE_LINES_MM.toList().mapNotNull { line -> window(radii, line, min(WINDOW_MM, line * 0.3))?.let { line to it } }
    val windows = listOfNotNull(edgeWindow) + lineWindows.map { it.second }
    val rays = rayCount(stepDeg)
    val cap = rays * (1 + WHITE_LINES_MM.size)
    val xs = DoubleArray(cap)
    val ys = DoubleArray(cap)
    val rs = DoubleArray(cap)
    var count = 0
    fun add(angle: Double, radiusAt: Double, onRing: Double) {
        val (ix, iy) = apply(inv, radiusAt * cos(angle), radiusAt * sin(angle))
        xs[count] = ix
        ys[count] = iy
        rs[count] = onRing
        count++
    }
    for (ray in 0 until rays) {
        val angle = ray * stepDeg * PI / 180.0
        val c = cos(angle)
        val s = sin(angle)
        values.fill(Double.NaN)
        for ((from, to) in windows) {
            for (k in max(0, from - 1)..min(n - 1, to + 1)) {
                if (!values[k].isNaN()) continue
                val u = radii[k] * c
                val v = radii[k] * s
                val w = inv[6] * u + inv[7] * v + inv[8]
                values[k] = image.sample((inv[0] * u + inv[1] * v + inv[2]) / w, (inv[3] * u + inv[4] * v + inv[5]) / w)
            }
        }
        // The black's outer edge: the steepest rise, dark to light.
        edgeWindow?.let { (from, to) ->
            var best = -1
            var bestRise = Double.NEGATIVE_INFINITY
            for (k in from..to) {
                val rise = gradient(values, k)
                if (rise > bestRise) {
                    bestRise = rise
                    best = k
                }
            }
            if (best > 0 && best < n - 1 && bestRise > 3.0) {
                val off = parabolaPeak(gradient(values, best - 1), bestRise, gradient(values, best + 1))
                add(angle, radii[best] + off * RADIUS_STEP_MM, R67)
            }
        }
        // Each white line: the brightest sample, clearly over the window's median.
        for ((line, range) in lineWindows) {
            val (from, to) = range
            var best = -1
            var bestValue = Double.NEGATIVE_INFINITY
            var off = false
            for (k in from..to) {
                val v = values[k]
                if (v.isNaN()) {
                    off = true
                    break
                }
                if (v > bestValue) {
                    bestValue = v
                    best = k
                }
            }
            if (off) continue
            val base = median(values, from, to)
            if (best > 0 && best < n - 1 && bestValue - base > 25.0) {
                val p = parabolaPeak(values[best - 1], bestValue, values[best + 1])
                if (p.isFinite()) add(angle, radii[best] + p * RADIUS_STEP_MM, line)
            }
        }
    }
    return EdgePoints(xs, ys, rs, count)
}

/** First and last index of [radii] strictly inside [centre] ± [half]; null when there is none. */
private fun window(radii: DoubleArray, centre: Double, half: Double): Pair<Int, Int>? {
    var from = -1
    var to = -1
    for (k in radii.indices) {
        if (radii[k] > centre - half && radii[k] < centre + half) {
            if (from < 0) from = k
            to = k
        }
    }
    return if (from < 0) null else from to to
}

/** numpy.gradient: central differences inside, one-sided at the ends; NaN where a sample is off the photo. */
private fun gradient(v: DoubleArray, k: Int): Double = when (k) {
    0 -> v[1] - v[0]
    v.size - 1 -> v[k] - v[k - 1]
    else -> (v[k + 1] - v[k - 1]) / 2
}.let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it }

/** Sub-sample offset of the peak of a parabola through three equally spaced values. */
private fun parabolaPeak(y0: Double, y1: Double, y2: Double): Double {
    val d = y0 - 2 * y1 + y2
    if (d == 0.0 || !d.isFinite()) return 0.0
    return 0.5 * (y0 - y2) / d
}

private fun median(v: DoubleArray, from: Int, to: Int): Double {
    val s = v.copyOfRange(from, to + 1).sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

/**
 * The homography (photo px -> mm) closest to [h0] that puts every point on its ring: minimises
 * the soft-L1 sum of (|H p| - R) by Levenberg-Marquardt. The rotation about the centre is free (a
 * circle has none), which the damping keeps from making the step singular. Null when it fails.
 */
private fun fitHomography(h0: DoubleArray, pts: EdgePoints): DoubleArray? {
    // Normalise the image points (centre on their mean, scale to unit spread) for conditioning;
    // the map in those coordinates is h0 · T⁻¹.
    var mx = 0.0
    var my = 0.0
    for (i in 0 until pts.size) {
        mx += pts.x[i]
        my += pts.y[i]
    }
    mx /= pts.size
    my /= pts.size
    var spread = 0.0
    for (i in 0 until pts.size) spread += hypot(pts.x[i] - mx, pts.y[i] - my)
    spread = spread / pts.size
    if (!(spread > 0.0)) return null
    val px = DoubleArray(pts.size) { (pts.x[it] - mx) / spread }
    val py = DoubleArray(pts.size) { (pts.y[it] - my) / spread }
    val tInv = doubleArrayOf(spread, 0.0, mx, 0.0, spread, my, 0.0, 0.0, 1.0)
    val t = doubleArrayOf(1 / spread, 0.0, -mx / spread, 0.0, 1 / spread, -my / spread, 0.0, 0.0, 1.0)
    var g = multiply(h0, tInv)
    if (g[8] == 0.0) return null
    g = DoubleArray(9) { g[it] / g[8] }
    var p = g.copyOf(8)
    var cost = cost(p, px, py, pts.r, pts.size) ?: return null
    var lambda = 1e-3
    val jtj = Array(8) { DoubleArray(8) }
    val jtr = DoubleArray(8)
    val jac = DoubleArray(8)
    for (iter in 0 until 100) {
        for (a in 0 until 8) {
            jtr[a] = 0.0
            jtj[a].fill(0.0)
        }
        val side = signAtCentre(p)
        for (i in 0 until pts.size) {
            val x = px[i]
            val y = py[i]
            val w = p[6] * x + p[7] * y + 1.0
            val u = (p[0] * x + p[1] * y + p[2]) / w
            val v = (p[3] * x + p[4] * y + p[5]) / w
            val rho = hypot(u, v)
            if (!(rho > 1e-9) || !(w * side > 0)) continue
            val r = rho - pts.r[i]
            val weight = 1.0 / sqrt(1.0 + (r / SOFT_L1_SCALE_MM) * (r / SOFT_L1_SCALE_MM))
            val du = u / rho / w
            val dv = v / rho / w
            jac[0] = du * x; jac[1] = du * y; jac[2] = du
            jac[3] = dv * x; jac[4] = dv * y; jac[5] = dv
            jac[6] = -rho * x / w; jac[7] = -rho * y / w
            for (a in 0 until 8) {
                val wa = weight * jac[a]
                jtr[a] += wa * r
                for (b in a until 8) jtj[a][b] += wa * jac[b]
            }
        }
        for (a in 0 until 8) for (b in 0 until a) jtj[a][b] = jtj[b][a]
        var improved = false
        while (lambda < 1e10) {
            val aug = Array(8) { a ->
                DoubleArray(9) { b ->
                    when {
                        b == 8 -> -jtr[a]
                        a == b -> jtj[a][a] * (1 + lambda) + 1e-12
                        else -> jtj[a][b]
                    }
                }
            }
            val step = solveLinear(aug)
            if (step == null) {
                lambda *= 10
                continue
            }
            val trial = DoubleArray(8) { p[it] + step[it] }
            val c = cost(trial, px, py, pts.r, pts.size)
            if (c != null && c < cost) {
                val gain = (cost - c) / cost
                p = trial
                cost = c
                lambda = max(lambda / 10, 1e-9)
                improved = true
                if (gain < 1e-10) return finish(p, t)
                break
            }
            lambda *= 10
        }
        if (!improved) break
    }
    return finish(p, t)
}

/** The map back in image px: g · T, normalised to h33 = 1. */
private fun finish(p: DoubleArray, t: DoubleArray): DoubleArray? {
    val g = doubleArrayOf(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 1.0)
    val h = multiply(g, t)
    if (h[8] == 0.0 || h.any { !it.isFinite() }) return null
    return DoubleArray(9) { h[it] / h[8] }
}

/** The sign of w at the target centre's image; points must share it (no mirrored points). */
private fun signAtCentre(p: DoubleArray): Double {
    val g = doubleArrayOf(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], 1.0)
    val inv = invert(g) ?: return 1.0
    return if (inv[8] < 0) -1.0 else 1.0
}

/** Soft-L1 cost, f² · Σ 2(√(1 + (r/f)²) - 1); null when a point lands past the vanishing line. */
private fun cost(p: DoubleArray, px: DoubleArray, py: DoubleArray, radius: DoubleArray, n: Int): Double? {
    val side = signAtCentre(p)
    var sum = 0.0
    val f = SOFT_L1_SCALE_MM
    for (i in 0 until n) {
        val w = p[6] * px[i] + p[7] * py[i] + 1.0
        if (!(w * side > 0)) return null
        val u = (p[0] * px[i] + p[1] * py[i] + p[2]) / w
        val v = (p[3] * px[i] + p[4] * py[i] + p[5]) / w
        val r = hypot(u, v) - radius[i]
        sum += 2 * (sqrt(1 + (r / f) * (r / f)) - 1)
    }
    return f * f * sum
}

private fun apply(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
    val w = m[6] * x + m[7] * y + m[8]
    return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
}

private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
    val r = i / 3
    val col = i % 3
    a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
}

private fun invert(m: DoubleArray): DoubleArray? {
    val c00 = m[4] * m[8] - m[5] * m[7]
    val c01 = m[5] * m[6] - m[3] * m[8]
    val c02 = m[3] * m[7] - m[4] * m[6]
    val det = m[0] * c00 + m[1] * c01 + m[2] * c02
    if (det == 0.0 || !det.isFinite()) return null
    return doubleArrayOf(
        c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
        c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
        c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
    )
}

/** Gaussian elimination with partial pivoting on augmented rows [a] (n x n+1); null when singular. */
private fun solveLinear(a: Array<DoubleArray>): DoubleArray? {
    val n = a.size
    for (col in 0 until n) {
        val pivot = (col until n).maxBy { abs(a[it][col]) }
        if (!(abs(a[pivot][col]) > 1e-300)) return null
        val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
        for (r in 0 until n) {
            if (r == col) continue
            val f = a[r][col] / a[col][col]
            if (f == 0.0) continue
            for (k in col..n) a[r][k] -= f * a[col][k]
        }
    }
    return DoubleArray(n) { a[it][n] / a[it][it] }.takeIf { s -> s.all { it.isFinite() } }
}
