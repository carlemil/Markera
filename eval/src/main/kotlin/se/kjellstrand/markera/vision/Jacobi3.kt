package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Eigendecomposition of a real symmetric 3x3 matrix. Returns eigenvalues
 * sorted descending (`values[0] >= values[1] >= values[2]`) along with the
 * orthonormal eigenvectors as the columns of [vectors].
 *
 * Cyclic Jacobi rotations: each sweep applies up to three plane rotations
 * (one per off-diagonal entry) and the off-diagonal norm decreases
 * quadratically. ~5–10 sweeps are typical for 3x3.
 */
data class Eigen3(val values: DoubleArray, val vectors: Mat3) {
    init {
        require(values.size == 3)
    }
}

fun jacobiSymmetric3(input: Mat3, maxSweeps: Int = 50, eps: Double = 1e-14): Eigen3 {
    // Copy so we can mutate.
    val a = input.data.copyOf()
    // Eigenvector matrix starts as identity; we accumulate rotations into it.
    val v = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    fun off(): Double {
        // Sum of squared off-diagonals (upper triangle).
        val ab = a[0 * 3 + 1]
        val ac = a[0 * 3 + 2]
        val bc = a[1 * 3 + 2]
        return ab * ab + ac * ac + bc * bc
    }

    fun rotate(p: Int, q: Int) {
        val apq = a[p * 3 + q]
        if (abs(apq) < eps) return
        val app = a[p * 3 + p]
        val aqq = a[q * 3 + q]
        val theta = (aqq - app) / (2.0 * apq)
        val t = if (abs(theta) > 1e10) 1.0 / (2.0 * theta) else {
            val sign = if (theta >= 0.0) 1.0 else -1.0
            sign / (abs(theta) + sqrt(theta * theta + 1.0))
        }
        val c = 1.0 / sqrt(1.0 + t * t)
        val s = t * c
        // Update diagonal & zero out (p,q).
        a[p * 3 + p] = app - t * apq
        a[q * 3 + q] = aqq + t * apq
        a[p * 3 + q] = 0.0
        a[q * 3 + p] = 0.0
        // Rotate remaining row/column entries.
        for (r in 0..2) {
            if (r == p || r == q) continue
            val arp = a[r * 3 + p]
            val arq = a[r * 3 + q]
            a[r * 3 + p] = c * arp - s * arq
            a[p * 3 + r] = a[r * 3 + p]
            a[r * 3 + q] = s * arp + c * arq
            a[q * 3 + r] = a[r * 3 + q]
        }
        // Accumulate rotation into v.
        for (r in 0..2) {
            val vrp = v[r * 3 + p]
            val vrq = v[r * 3 + q]
            v[r * 3 + p] = c * vrp - s * vrq
            v[r * 3 + q] = s * vrp + c * vrq
        }
    }

    var sweeps = 0
    while (sweeps < maxSweeps && off() > eps) {
        rotate(0, 1)
        rotate(0, 2)
        rotate(1, 2)
        sweeps++
    }

    val rawValues = doubleArrayOf(a[0], a[4], a[8])

    // Sort eigenvalues descending while keeping columns of v in sync.
    val idx = intArrayOf(0, 1, 2)
    // Simple selection sort on 3 elements.
    for (i in 0..1) {
        var best = i
        for (j in i + 1..2) if (rawValues[idx[j]] > rawValues[idx[best]]) best = j
        if (best != i) {
            val tmp = idx[i]; idx[i] = idx[best]; idx[best] = tmp
        }
    }
    val sortedValues = DoubleArray(3) { rawValues[idx[it]] }
    val sortedVecs = DoubleArray(9)
    for (col in 0..2) {
        val src = idx[col]
        for (row in 0..2) sortedVecs[row * 3 + col] = v[row * 3 + src]
    }
    return Eigen3(sortedValues, Mat3(sortedVecs))
}
