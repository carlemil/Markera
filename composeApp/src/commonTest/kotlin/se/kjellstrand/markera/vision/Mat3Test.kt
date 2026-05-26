package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test

class Mat3Test {

    private fun assertMat3Near(expected: Mat3, actual: Mat3, eps: Double = 1e-9) {
        for (i in 0..8) {
            assertEquals(expected.data[i], actual.data[i], eps, "entry $i")
        }
    }

    @Test
    fun `identity times any matrix is unchanged`() {
        val a = Mat3.of(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 10.0,
        )
        assertMat3Near(a, mat3Multiply(Mat3.identity(), a))
        assertMat3Near(a, mat3Multiply(a, Mat3.identity()))
    }

    @Test
    fun `transpose is involutive`() {
        val a = Mat3.of(
            1.0, 2.0, 3.0,
            4.0, 5.0, 6.0,
            7.0, 8.0, 10.0,
        )
        assertMat3Near(a, mat3Transpose(mat3Transpose(a)))
    }

    @Test
    fun `inverse round-trips to identity`() {
        val a = Mat3.of(
            1.0, 2.0, 3.0,
            0.0, 1.0, 4.0,
            5.0, 6.0, 0.0,
        )
        val inv = mat3Inverse(a)
        assertNotNull(inv); inv!!
        assertMat3Near(Mat3.identity(), mat3Multiply(a, inv))
        assertMat3Near(Mat3.identity(), mat3Multiply(inv, a))
    }

    @Test
    fun `singular matrix returns null inverse`() {
        val singular = Mat3.of(
            1.0, 2.0, 3.0,
            2.0, 4.0, 6.0,
            7.0, 8.0, 9.0,
        )
        assertNull(mat3Inverse(singular))
    }

    @Test
    fun `fromColumns places vectors as columns`() {
        val m = Mat3.fromColumns(
            Vec3(1.0, 2.0, 3.0),
            Vec3(4.0, 5.0, 6.0),
            Vec3(7.0, 8.0, 9.0),
        )
        assertEquals(1.0, m[0, 0], 0.0)
        assertEquals(4.0, m[0, 1], 0.0)
        assertEquals(7.0, m[0, 2], 0.0)
        assertEquals(2.0, m[1, 0], 0.0)
        assertEquals(9.0, m[2, 2], 0.0)
    }

    @Test
    fun `mulVec applies matrix to vector`() {
        val m = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 2.0, 0.0,
            0.0, 0.0, 3.0,
        )
        val v = Vec3(4.0, 5.0, 6.0)
        val out = mat3MulVec(m, v)
        assertEquals(4.0, out.x, 0.0)
        assertEquals(10.0, out.y, 0.0)
        assertEquals(18.0, out.z, 0.0)
    }
}
