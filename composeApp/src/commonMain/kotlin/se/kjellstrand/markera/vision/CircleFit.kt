package se.kjellstrand.markera.vision

import kotlin.math.sqrt

data class FittedCircle(val cx: Float, val cy: Float, val r: Float, val rmsError: Float)

/**
 * Kasa algebraic least-squares circle fit. Each point (x, y) contributes
 * the equation `2·cx·x + 2·cy·y + F = x² + y²`, with `F = r² − cx² − cy²`.
 * Build the 3×3 normal-equation matrix `AᵀA · θ = Aᵀb` and solve via
 * Cramer's rule. Returns null if the matrix is singular (collinear or
 * insufficient points).
 */
fun fitCircle(points: List<IntArray>): FittedCircle? {
    if (points.size < 3) return null

    // Accumulate normal-equation entries.
    var s1 = 0.0       // n
    var sx = 0.0; var sy = 0.0
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0
    var sxxx_plus_sxyy = 0.0
    var sxxy_plus_syyy = 0.0
    var sxx_plus_syy = 0.0

    for (p in points) {
        val x = p[0].toDouble()
        val y = p[1].toDouble()
        val x2 = x * x
        val y2 = y * y
        s1 += 1.0
        sx += x
        sy += y
        sxx += x2
        sxy += x * y
        syy += y2
        sxxx_plus_sxyy += x * (x2 + y2)
        sxxy_plus_syyy += y * (x2 + y2)
        sxx_plus_syy += (x2 + y2)
    }

    // AᵀA (3x3) where A row = [2x, 2y, 1]
    val m00 = 4.0 * sxx; val m01 = 4.0 * sxy; val m02 = 2.0 * sx
    val m10 = 4.0 * sxy; val m11 = 4.0 * syy; val m12 = 2.0 * sy
    val m20 = 2.0 * sx;  val m21 = 2.0 * sy;  val m22 = s1

    // Aᵀb where b_i = x² + y²
    val b0 = 2.0 * sxxx_plus_sxyy
    val b1 = 2.0 * sxxy_plus_syyy
    val b2 = sxx_plus_syy

    fun det3(
        a: Double, b: Double, c: Double,
        d: Double, e: Double, f: Double,
        g: Double, h: Double, i: Double,
    ) = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)

    val det = det3(m00, m01, m02, m10, m11, m12, m20, m21, m22)
    if (det == 0.0) return null

    val detCx = det3(b0, m01, m02, b1, m11, m12, b2, m21, m22)
    val detCy = det3(m00, b0, m02, m10, b1, m12, m20, b2, m22)
    val detF  = det3(m00, m01, b0, m10, m11, b1, m20, m21, b2)

    val cx = detCx / det
    val cy = detCy / det
    val f = detF / det
    val r2 = f + cx * cx + cy * cy
    if (r2 <= 0.0) return null
    val r = sqrt(r2)

    // RMS geometric error
    var err2 = 0.0
    for (p in points) {
        val dx = p[0] - cx
        val dy = p[1] - cy
        val d = sqrt(dx * dx + dy * dy) - r
        err2 += d * d
    }
    val rmse = sqrt(err2 / points.size)

    return FittedCircle(cx.toFloat(), cy.toFloat(), r.toFloat(), rmse.toFloat())
}
