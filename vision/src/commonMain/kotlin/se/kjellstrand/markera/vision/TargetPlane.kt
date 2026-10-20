package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The photo-to-target map under full perspective: [h] takes source-image px
 * (homogeneous) to target-plane mm, centre at the origin, y down like the photo.
 *
 * A tilted camera does not just squash the rings into concentric ellipses: each
 * ring's ellipse centre slides towards the near side, more the bigger the ring
 * (about radius squared), so a plain rotate-and-stretch about the centre drifts
 * further out. The 6/7 ellipse plus the image of its centre pin the perspective
 * down exactly: the centre's polar line with respect to the ellipse is the
 * target's vanishing line. [of] sends that line to infinity, which leaves an
 * ellipse centred on the digit centre, and then stretches that onto the 100 mm
 * circle.
 */
class TargetPlane private constructor(private val h: DoubleArray) {
    private val inv = invert3(h)

    /** [x],[y] (image px) in target mm. */
    fun toMm(x: Double, y: Double): Pair<Double, Double> = apply3(h, x, y)

    /** [xMm],[yMm] (target mm) back in image px. */
    fun toImage(xMm: Double, yMm: Double): Pair<Double, Double> = apply3(inv, xMm, yMm)

    /** The image of the target circle of [radiusMm]: an ellipse, generally not centred on the target centre. */
    fun circleOutline(radiusMm: Double): FittedEllipse? {
        // Conic of the circle (x² + y² = r²), pulled back to the image: hᵀ Q h.
        val q = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, -radiusMm * radiusMm)
        return ellipseOf(mul3(mul3(transpose3(h), q), h))
    }

    companion object {
        /**
         * The map from the 6/7 [ring] and the digit [centre]; null when they can't
         * be a circle and its centre (centre outside the ellipse, degenerate ellipse).
         * The rotation about the centre is free (a circle has none of its own); the
         * symmetric stretch keeps the photo's own up, as the affine model did.
         */
        fun of(centre: CentreEstimate, ring: FittedEllipse): TargetPlane? {
            if (ring.semiMajor <= 0f || ring.semiMinor <= 0f) return null
            // Ellipse conic, centre at the origin: (u - m)ᵀ A (u - m) = 1.
            val c = kotlin.math.cos(ring.rotationRad.toDouble())
            val s = kotlin.math.sin(ring.rotationRad.toDouble())
            val ia = 1.0 / (ring.semiMajor.toDouble() * ring.semiMajor)
            val ib = 1.0 / (ring.semiMinor.toDouble() * ring.semiMinor)
            val a11 = c * c * ia + s * s * ib
            val a12 = c * s * (ia - ib)
            val a22 = s * s * ia + c * c * ib
            val mx = ring.cx.toDouble() - centre.x
            val my = ring.cy.toDouble() - centre.y
            val amx = a11 * mx + a12 * my
            val amy = a12 * mx + a22 * my
            // Polar of the origin: the third column of the conic matrix.
            val l1 = -amx
            val l2 = -amy
            val l3 = mx * amx + my * amy - 1.0
            if (l3 > -1e-9) return null // centre on or outside the ellipse
            val conic = doubleArrayOf(a11, a12, l1, a12, a22, l2, l1, l2, l3)
            // p sends the polar to infinity and fixes the origin.
            val v1 = l1 / l3
            val v2 = l2 / l3
            val p = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, v1, v2, 1.0)
            val pInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, -v1, -v2, 1.0)
            val k = mul3(mul3(transpose3(pInv), conic), pInv)
            // Now centred: x ᵀ B x = 1 with B = k's 2x2 block / -k33.
            val f = -k[8]
            if (f <= 0.0) return null
            val m = sqrtSym(k[0] / f, k[1] / f, k[4] / f) ?: return null
            val r = TARGET_BLACK_RING_RADIUS_MM
            val stretch = doubleArrayOf(r * m[0], r * m[1], 0.0, r * m[1], r * m[2], 0.0, 0.0, 0.0, 1.0)
            val shift = doubleArrayOf(1.0, 0.0, -centre.x.toDouble(), 0.0, 1.0, -centre.y.toDouble(), 0.0, 0.0, 1.0)
            return TargetPlane(mul3(mul3(stretch, p), shift))
        }

        /**
         * The exact map from four image points that are the target's
         * (-[radiusMm], 0), ([radiusMm], 0), (0, -[radiusMm]), (0, [radiusMm]),
         * in that order (the probes' left, right, top, bottom); null when three
         * of them are (near) collinear.
         */
        fun fromCross(xs: DoubleArray, ys: DoubleArray, radiusMm: Double): TargetPlane? {
            val tx = doubleArrayOf(-radiusMm, radiusMm, 0.0, 0.0)
            val ty = doubleArrayOf(0.0, 0.0, -radiusMm, radiusMm)
            // h33 = 1; two rows per point of the usual direct linear transform.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = xs[i]
                val y = ys[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -tx[i] * x, -tx[i] * y, tx[i])
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -ty[i] * x, -ty[i] * y, ty[i])
            }
            val sol = solve(a) ?: return null
            return TargetPlane(doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0))
        }
    }
}

/** Centre, semi-axes and tilt of the ellipse conic [k] (row-major 3x3); null when it is not a real ellipse. */
internal fun ellipseOf(k: DoubleArray): FittedEllipse? {
    val a = k[0]
    val b = (k[1] + k[3]) / 2
    val c = k[4]
    val d = (k[2] + k[6]) / 2
    val e = (k[5] + k[7]) / 2
    val det = a * c - b * b
    if (det <= 0.0) return null
    val mx = (-d * c + b * e) / det
    val my = (-a * e + b * d) / det
    val g = -(k[8] + d * mx + e * my) // (X - m)ᵀ A (X - m) = g
    if (g <= 0.0) return null
    val mid = (a + c) / 2
    val spread = sqrt((a - c) * (a - c) / 4 + b * b)
    val big = (mid + spread) / g
    val small = (mid - spread) / g
    if (small <= 0.0) return null
    // The larger eigenvalue's direction is the minor axis; the major is square to it.
    val minorAngle = atan2(2 * b, a - c) / 2
    return FittedEllipse(
        cx = mx.toFloat(),
        cy = my.toFloat(),
        semiMajor = (1 / sqrt(small)).toFloat(),
        semiMinor = (1 / sqrt(big)).toFloat(),
        rotationRad = (minorAngle + kotlin.math.PI / 2).toFloat(),
    )
}

/** Symmetric square root of [[a, b], [b, c]] as (m11, m12, m22); null unless positive definite. */
private fun sqrtSym(a: Double, b: Double, c: Double): DoubleArray? {
    val det = a * c - b * b
    if (a <= 0.0 || det <= 0.0) return null
    // sqrt(M) = (M + sqrt(det) I) / sqrt(tr + 2 sqrt(det)) for a 2x2 positive definite M.
    val sd = sqrt(det)
    val t = sqrt(a + c + 2 * sd)
    return doubleArrayOf((a + sd) / t, b / t, (c + sd) / t)
}

private fun apply3(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
    val w = m[6] * x + m[7] * y + m[8]
    return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
}

private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
    val r = i / 3
    val col = i % 3
    a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
}

private fun transpose3(m: DoubleArray): DoubleArray = DoubleArray(9) { m[(it % 3) * 3 + it / 3] }

private fun invert3(m: DoubleArray): DoubleArray {
    val c00 = m[4] * m[8] - m[5] * m[7]
    val c01 = m[5] * m[6] - m[3] * m[8]
    val c02 = m[3] * m[7] - m[4] * m[6]
    val det = m[0] * c00 + m[1] * c01 + m[2] * c02
    return doubleArrayOf(
        c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
        c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
        c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
    )
}

/** Gaussian elimination with partial pivoting on the augmented rows [a] (n x n+1); null when singular. */
private fun solve(a: Array<DoubleArray>): DoubleArray? {
    val n = a.size
    val scale = a.maxOf { row -> row.maxOf { abs(it) } }
    for (col in 0 until n) {
        val pivot = (col until n).maxBy { abs(a[it][col]) }
        if (abs(a[pivot][col]) <= scale * 1e-12) return null
        val t = a[col]; a[col] = a[pivot]; a[pivot] = t
        for (r in 0 until n) {
            if (r == col) continue
            val f = a[r][col] / a[col][col]
            for (k in col..n) a[r][k] -= f * a[col][k]
        }
    }
    return DoubleArray(n) { a[it][n] / a[it][it] }
}
