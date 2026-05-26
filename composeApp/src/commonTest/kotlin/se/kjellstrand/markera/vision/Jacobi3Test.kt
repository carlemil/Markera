package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.abs

class Jacobi3Test {

    @Test
    fun `diagonal matrix eigenvalues are sorted descending`() {
        val m = Mat3.of(
            3.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 2.0,
        )
        val eig = jacobiSymmetric3(m)
        assertEquals(3.0, eig.values[0], 1e-12)
        assertEquals(2.0, eig.values[1], 1e-12)
        assertEquals(1.0, eig.values[2], 1e-12)
    }

    @Test
    fun `eigenvectors are orthonormal`() {
        // Symmetric matrix with three distinct eigenvalues.
        val m = Mat3.of(
            4.0, 1.0, 2.0,
            1.0, 5.0, 3.0,
            2.0, 3.0, 6.0,
        )
        val eig = jacobiSymmetric3(m)
        val v = eig.vectors
        // Each column unit length, mutually orthogonal.
        for (i in 0..2) {
            val ci = Vec3(v[0, i], v[1, i], v[2, i])
            assertEquals(1.0, ci.norm(), 1e-9, "col $i norm")
            for (j in i + 1..2) {
                val cj = Vec3(v[0, j], v[1, j], v[2, j])
                assertEquals(0.0, ci.dot(cj), 1e-9, "col $i dot col $j")
            }
        }
    }

    @Test
    fun `reconstructs original matrix M = V diag V^T`() {
        val m = Mat3.of(
            4.0, 1.0, -2.0,
            1.0, -5.0, 3.0,
            -2.0, 3.0, 6.0,
        )
        val eig = jacobiSymmetric3(m)
        val diag = Mat3.of(
            eig.values[0], 0.0, 0.0,
            0.0, eig.values[1], 0.0,
            0.0, 0.0, eig.values[2],
        )
        val reconstructed = mat3Multiply(eig.vectors, mat3Multiply(diag, mat3Transpose(eig.vectors)))
        for (i in 0..8) {
            assertTrue(
                abs(m.data[i] - reconstructed.data[i]) < 1e-9,
                "entry $i: expected=${m.data[i]} got=${reconstructed.data[i]}",
            )
        }
    }

    @Test
    fun `mixed sign eigenvalues for indefinite matrix`() {
        val m = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, -2.0,
        )
        val eig = jacobiSymmetric3(m)
        assertEquals(1.0, eig.values[0], 1e-12)
        assertEquals(1.0, eig.values[1], 1e-12)
        assertEquals(-2.0, eig.values[2], 1e-12)
    }
}
