package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CentreEstimatorTest {

    // --- helpers -----------------------------------------------------------

    private fun digit(x: Float, y: Float, value: Int, conf: Float = 1f): DigitDetection {
        val half = 5f
        return DigitDetection(x - half, y - half, x + half, y + half, value, conf)
    }

    /**
     * Build a target whose digits sit on a horizontal and a vertical row about
     * (cx, cy). For value v the digit is at distance (10 - v) * step from the
     * centre on each side, so the rows cross exactly at (cx, cy). [theta]
     * rotates the whole pattern.
     */
    private fun target(
        cx: Float,
        cy: Float,
        step: Float = 20f,
        hValues: IntRange = 1..9,
        vValues: IntRange = 1..9,
        theta: Double = 0.0,
    ): List<DigitDetection> {
        val out = mutableListOf<DigitDetection>()
        fun place(dx: Float, dy: Float, value: Int) {
            val rx = dx * cos(theta) - dy * sin(theta)
            val ry = dx * sin(theta) + dy * cos(theta)
            out += digit(cx + rx.toFloat(), cy + ry.toFloat(), value)
        }
        for (v in hValues) {
            val d = (10 - v) * step
            place(-d, 0f, v)
            place(d, 0f, v)
        }
        for (v in vValues) {
            val d = (10 - v) * step
            place(0f, -d, v)
            place(0f, d, v)
        }
        return out
    }

    private fun assertCentre(e: CentreEstimate, x: Float, y: Float, tol: Float = 0.5f) {
        assertEquals(x, e.x, tol)
        assertEquals(y, e.y, tol)
    }

    // --- tests -------------------------------------------------------------

    @Test
    fun `ideal cross intersects at the true centre`() {
        val e = estimateCentre(target(100f, 200f))
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 100f, 200f)
    }

    @Test
    fun `both fitted lines are returned for the UI`() {
        val e = estimateCentre(target(100f, 200f))
        val h = assertNotNull(e.horizontalLine)
        val v = assertNotNull(e.verticalLine)
        // Horizontal row line runs along x at y=200; vertical along y at x=100.
        assertEquals(200f, h.py, 0.5f)
        assertTrue(abs(h.dx) > 0.99f)
        assertEquals(100f, v.px, 0.5f)
        assertTrue(abs(v.dy) > 0.99f)
    }

    @Test
    fun `off-centre target still resolves the centre`() {
        val e = estimateCentre(target(640f, 360f))
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 640f, 360f)
    }

    @Test
    fun `rotated rows intersect at the true centre`() {
        val e = estimateCentre(target(300f, 300f, theta = 10.0 * kotlin.math.PI / 180.0))
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 300f, 300f, tol = 1f)
    }

    @Test
    fun `partial rows still intersect at the centre`() {
        // Only digits 5..9 read on each row — fewer points, same lines.
        val e = estimateCentre(target(100f, 200f, hValues = 5..9, vValues = 5..9))
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }

    @Test
    fun `missing vertical row yields NONE`() {
        val e = estimateCentre(target(100f, 200f, vValues = IntRange.EMPTY))
        assertEquals(CentreMethod.NONE, e.method)
    }

    @Test
    fun `too few digits yields NONE`() {
        val e = estimateCentre(listOf(digit(10f, 10f, 5), digit(20f, 20f, 4)))
        assertEquals(CentreMethod.NONE, e.method)
    }

    @Test
    fun `low-confidence digits are ignored`() {
        val noisy = target(100f, 200f).map { it.copy(conf = 0.1f) }
        val e = estimateCentre(noisy)
        assertEquals(CentreMethod.NONE, e.method)
    }

    @Test
    fun `a stray misread digit does not corrupt the centre`() {
        val digits = target(100f, 200f).toMutableList()
        // A '7' parked far off both rows.
        digits += digit(400f, 500f, 7)
        val e = estimateCentre(digits)
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }

    @Test
    fun `a digit sitting at the cross centre does not break resolution`() {
        val digits = target(100f, 200f).toMutableList()
        digits += digit(100f, 200f, 5)
        val e = estimateCentre(digits)
        assertEquals(CentreMethod.LINE_INTERSECTION, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }
}
