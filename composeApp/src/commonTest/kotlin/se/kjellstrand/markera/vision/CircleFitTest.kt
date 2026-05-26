package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.cos
import kotlin.math.sin

class CircleFitTest {

    private fun sample(cx: Double, cy: Double, r: Double, n: Int): List<IntArray> {
        return List(n) { i ->
            val angle = 2.0 * Math.PI * i / n
            intArrayOf((cx + r * cos(angle)).toInt(), (cy + r * sin(angle)).toInt())
        }
    }

    @Test
    fun `perfect circle recovers cx cy r within integer rounding`() {
        val points = sample(50.0, 50.0, 30.0, 64)
        val c = fitCircle(points)!!
        assertEquals(50f, c.cx, 1.0f)
        assertEquals(50f, c.cy, 1.0f)
        assertEquals(30f, c.r, 1.0f)
        assertTrue(c.rmsError < 1.0f)
    }

    @Test
    fun `noisy circle still recovers parameters within a few pixels`() {
        val rnd = java.util.Random(42L)
        val points = sample(120.0, 80.0, 50.0, 80).map {
            intArrayOf(it[0] + rnd.nextInt(5) - 2, it[1] + rnd.nextInt(5) - 2)
        }
        val c = fitCircle(points)!!
        assertEquals(120f, c.cx, 3f)
        assertEquals(80f, c.cy, 3f)
        assertEquals(50f, c.r, 3f)
    }

    @Test
    fun `fewer than three points returns null`() {
        assertNull(fitCircle(emptyList()))
        assertNull(fitCircle(listOf(intArrayOf(0, 0), intArrayOf(1, 1))))
    }

    @Test
    fun `collinear points return null`() {
        val collinear = listOf(intArrayOf(0, 0), intArrayOf(1, 1), intArrayOf(2, 2), intArrayOf(3, 3))
        assertNull(fitCircle(collinear))
    }
}
