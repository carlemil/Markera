package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One probe disk: where it started on the digit-predicted 6/7 boundary and where it settled. */
data class RingProbe(
    val startX: Float,
    val startY: Float,
    val x: Float,
    val y: Float,
    /** Fraction of the disk's pixels at or below the Otsu threshold at the final position (NaN when off-image). */
    val darkFraction: Float,
    val iterations: Int,
)

/** The four probes in left, right, top, bottom order, and the ellipse through their final centres. */
data class RingProbeResult(val probes: List<RingProbe>, val ellipse: FittedEllipse?)

/**
 * The scan's 6/7 ring: the probe-disk ellipse ([fit67RingByProbes]) when it is
 * plausible, else null (no ring, so no scores; there is no fallback).
 *
 * Plausible: every on-image probe's dark share within 0.15 of 0.5, at most one
 * probe off-image, the ellipse centre within 0.25 x semiMajor of the digit
 * centre, not a sliver (minor/major >= 0.3), and semiMajor 0.5..1.7 x the
 * [fit67RingFromDigits] radius when there is one.
 */
fun fit67Ring(
    gray: ByteArray,
    width: Int,
    height: Int,
    digits: List<DigitDetection>,
    centre: CentreEstimate,
): FittedEllipse? {
    if (centre.method == CentreMethod.NONE) return null
    val probes = fit67RingByProbes(gray, width, height, digits, centre) ?: return null
    val e = probes.ellipse ?: return null
    val seed = fit67RingFromDigits(digits, centre)
    return e.takeIf { plausible67(probes.probes.map { it.darkFraction }, e, centre, seed) }
}

/** [fit67Ring]'s plausibility gates for probe ellipse [e]; see there. */
internal fun plausible67(
    fractions: List<Float>,
    e: FittedEllipse,
    centre: CentreEstimate,
    seed: FittedEllipse?,
): Boolean = fractions.count { it.isNaN() } <= 1 &&
    fractions.all { it.isNaN() || abs(it - 0.5f) <= 0.15f } &&
    hypot(e.cx - centre.x, e.cy - centre.y) <= 0.25f * e.semiMajor &&
    e.semiMinor / e.semiMajor >= 0.3f &&
    (seed == null || e.semiMajor in 0.5f * seed.semiMajor..1.7f * seed.semiMajor)

/**
 * A 6/7 circle from the recognised ring digits, at the digit [centre] (never
 * an ellipse centre). [fit67Ring] uses its radius as the size check and
 * [fit67RingByProbes] as the start distance when no digit row gives one.
 *
 * Radius: each digit of value v sits at `rho_v = R67 - (v - 6.5)*w`, so scaling
 * its distance from the centre by `R67/rho_v = 1 / (1 - (v-6.5)*q)` with the
 * spec-fixed [RING_WIDTH_FRACTION] `q = w/R67 = 0.25` maps it to ~the 6/7 radius;
 * the **median** of those is a robust R67 estimate (OCR misreads get amplified
 * to wildly different radii, so the median ignores them).
 */
fun fit67RingFromDigits(
    digits: List<DigitDetection>,
    centre: CentreEstimate,
): FittedEllipse? {
    if (centre.method == CentreMethod.NONE) return null
    val usable = CentreConfig().usable(digits)
    if (usable.size < 5) return null
    val cx = centre.x.toDouble()
    val cy = centre.y.toDouble()
    val q = RING_WIDTH_FRACTION
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
    return FittedEllipse(cx.toFloat(), cy.toFloat(), r0.toFloat(), r0.toFloat(), 0f)
}

/**
 * Four probe disks settle on the black->white 6/7 rim and an ellipse is drawn
 * exactly through them. The scan reaches it through [fit67Ring].
 *
 * Starts: left/right along the horizontal digit row, top/bottom along the
 * vertical one, from the digit [centre]. Each side's distance comes from its
 * own 6..9 digits (digit v sits mid-band, so `dist = alpha - beta*v` evaluated
 * at v = 6.5; one digit uses the spec ring-width ratio), so the near side of a
 * tilted target starts further out. A side without digits mirrors the opposite
 * one; neither falls back to [fit67RingFromDigits]'s median radius.
 *
 * Move: a disk of radius rho centred on a circle of radius R is dark over
 * `f* = 0.5 - rho/(3*pi*R)` of its area. Both the measured fraction and f* are
 * turned into an edge offset with the straight-edge model
 * `f(t) = (pi - acos t + t*sqrt(1-t^2))/pi` and the probe steps outward by
 * `rho*(t_measured - t_target)`, until a step is under 0.5 px. Travel is capped
 * at one ring width (a start from misplaced OCR boxes can be half a ring short)
 * so a probe can't run on past the next printed ring line.
 *
 * Null when there is no centre or no start distance.
 */
fun fit67RingByProbes(
    gray: ByteArray,
    width: Int,
    height: Int,
    digits: List<DigitDetection>,
    centre: CentreEstimate,
    probeDiameterPx: Int = 50,
    maxIterations: Int = 10,
): RingProbeResult? {
    if (centre.method == CentreMethod.NONE) return null
    val h = centre.horizontalLine ?: return null
    val v = centre.verticalLine ?: return null
    val cx = centre.x.toDouble()
    val cy = centre.y.toDouble()
    // Right points to +x, bottom to +y, whichever way the fitted lines happen to run.
    val hs = if (h.dx < 0) -1.0 else 1.0
    val vs = if (v.dy < 0) -1.0 else 1.0
    val dirs = arrayOf(
        doubleArrayOf(-hs * h.dx, -hs * h.dy), doubleArrayOf(hs * h.dx, hs * h.dy),
        doubleArrayOf(-vs * v.dx, -vs * v.dy), doubleArrayOf(vs * v.dx, vs * v.dy),
    )

    val usable = CentreConfig().usable(digits)
    val q = RING_WIDTH_FRACTION
    // Per side: [start distance, ring width], both px.
    val sides = Array(4) { sideDistance(usable, cx, cy, dirs[it][0], dirs[it][1], q) }
    for (i in 0 until 4) if (sides[i] == null) sides[i] = sides[i xor 1]
    if (sides.any { it == null }) {
        val r = fit67RingFromDigits(digits, centre)?.semiMajor?.toDouble() ?: return null
        for (i in 0 until 4) if (sides[i] == null) sides[i] = doubleArrayOf(r, q * r)
    }

    val threshold = otsuThreshold(gray, width, height)
    val rho = probeDiameterPx / 2.0
    val finalX = DoubleArray(4)
    val finalY = DoubleArray(4)
    val probes = List(4) { i ->
        val ux = dirs[i][0]
        val uy = dirs[i][1]
        val start = sides[i]!![0]
        val cap = sides[i]!![1]
        var s = start
        var iterations = 0
        while (iterations < maxIterations) {
            iterations++
            val frac = darkFraction(gray, width, height, cx + s * ux, cy + s * uy, rho, threshold) ?: break
            val target = 0.5 - rho / (3 * PI * max(s, rho))
            val next = (s + rho * (edgeOffset(frac) - edgeOffset(target))).coerceIn(start - cap, start + cap)
            val step = next - s
            s = next
            if (abs(step) < 0.5) break
        }
        finalX[i] = cx + s * ux
        finalY[i] = cy + s * uy
        val frac = darkFraction(gray, width, height, finalX[i], finalY[i], rho, threshold)
        RingProbe(
            (cx + start * ux).toFloat(), (cy + start * uy).toFloat(),
            finalX[i].toFloat(), finalY[i].toFloat(),
            frac?.toFloat() ?: Float.NaN, iterations,
        )
    }
    return RingProbeResult(probes, ellipseThroughFour(finalX, finalY, atan2(hs * h.dy, hs * h.dx).toDouble()))
}

/**
 * [start distance, ring width] from the digits on the side of ([cx],[cy]) that
 * ([ux],[uy]) points to (within 45 degrees of it, so the other row's digits
 * don't count). Null when the side has none.
 */
private fun sideDistance(
    digits: List<DigitDetection>,
    cx: Double,
    cy: Double,
    ux: Double,
    uy: Double,
    q: Double,
): DoubleArray? {
    val values = ArrayList<Double>()
    val dists = ArrayList<Double>()
    for (d in digits) {
        val ax = d.cx - cx
        val ay = d.cy - cy
        val along = ax * ux + ay * uy
        if (along > abs(ay * ux - ax * uy)) {
            values.add(d.value.toDouble())
            dists.add(along)
        }
    }
    if (values.isEmpty()) return null
    val mv = values.average()
    val md = dists.average()
    var sxx = 0.0
    var sxy = 0.0
    for (i in values.indices) {
        sxx += (values[i] - mv) * (values[i] - mv)
        sxy += (values[i] - mv) * (dists[i] - md)
    }
    val beta = if (sxx > 0) -sxy / sxx else 0.0
    if (beta > 0) return doubleArrayOf(md + beta * (mv - 6.5), beta)
    // One digit value (or a misread that flips the slope): the spec ratio per digit.
    val dist = values.indices.map { dists[it] / (1.0 - (values[it] - 6.5) * q) }.average()
    return doubleArrayOf(dist, q * dist)
}

/** Share of pixels (centre inside the disk) at or below [threshold] (Otsu foreground); null when the disk is off-image. */
private fun darkFraction(
    gray: ByteArray,
    width: Int,
    height: Int,
    x: Double,
    y: Double,
    rho: Double,
    threshold: Int,
): Double? {
    var dark = 0
    var total = 0
    val r2 = rho * rho
    for (py in max(0, ceil(y - rho).toInt())..min(height - 1, floor(y + rho).toInt())) {
        for (px in max(0, ceil(x - rho).toInt())..min(width - 1, floor(x + rho).toInt())) {
            val dx = px - x
            val dy = py - y
            if (dx * dx + dy * dy > r2) continue
            total++
            if ((gray[py * width + px].toInt() and 0xFF) <= threshold) dark++
        }
    }
    return if (total == 0) null else dark.toDouble() / total
}

/** Signed edge offset t in [-1, 1] (disk radii, + = centre on the dark side) whose straight-edge dark share is [fraction]. */
private fun edgeOffset(fraction: Double): Double {
    var lo = -1.0
    var hi = 1.0
    repeat(40) {
        val t = (lo + hi) / 2
        if ((PI - acos(t) + t * sqrt(1 - t * t)) / PI < fraction) lo = t else hi = t
    }
    return (lo + hi) / 2
}

/**
 * The ellipse with axes along [theta] through four points: in the rotated frame
 * solve `A*u^2 + C*v^2 + D*u + E*v = 1` exactly, centre `(-D/2A, -E/2C)`,
 * semi-axes `sqrt(G/A)`, `sqrt(G/C)` with `G = 1 + A*u0^2 + C*v0^2`. Null for a
 * hyperbola (A or C <= 0) or a singular system.
 */
internal fun ellipseThroughFour(xs: DoubleArray, ys: DoubleArray, theta: Double): FittedEllipse? {
    // Centred and scaled to ~1 so the elimination is well conditioned.
    val ox = xs.average()
    val oy = ys.average()
    val c = cos(theta)
    val sn = sin(theta)
    val u = DoubleArray(4) { (xs[it] - ox) * c + (ys[it] - oy) * sn }
    val v = DoubleArray(4) { -(xs[it] - ox) * sn + (ys[it] - oy) * c }
    val scale = max(u.maxOf { abs(it) }, v.maxOf { abs(it) })
    if (scale <= 0) return null
    val m = Array(4) {
        val a = u[it] / scale
        val b = v[it] / scale
        doubleArrayOf(a * a, b * b, a, b, 1.0)
    }
    for (col in 0 until 4) {
        val p = (col until 4).maxBy { abs(m[it][col]) }
        if (abs(m[p][col]) < 1e-9) return null
        val tmp = m[col]
        m[col] = m[p]
        m[p] = tmp
        for (r in col + 1 until 4) {
            val f = m[r][col] / m[col][col]
            for (k in col..4) m[r][k] -= f * m[col][k]
        }
    }
    val sol = DoubleArray(4)
    for (r in 3 downTo 0) {
        var acc = m[r][4]
        for (k in r + 1 until 4) acc -= m[r][k] * sol[k]
        sol[r] = acc / m[r][r]
    }
    val (a, cc, d, e) = sol
    if (a <= 0 || cc <= 0) return null
    val u0 = -d / (2 * a)
    val v0 = -e / (2 * cc)
    val g = 1 + a * u0 * u0 + cc * v0 * v0
    val au = (sqrt(g / a) * scale).toFloat()
    val av = (sqrt(g / cc) * scale).toFloat()
    val x0 = (ox + (u0 * c - v0 * sn) * scale).toFloat()
    val y0 = (oy + (u0 * sn + v0 * c) * scale).toFloat()
    return if (au >= av) {
        FittedEllipse(x0, y0, au, av, theta.toFloat())
    } else {
        FittedEllipse(x0, y0, av, au, (theta + PI / 2).toFloat())
    }
}
