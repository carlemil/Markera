package se.kjellstrand.markera.vision

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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
     * centre on each side, so the two 9s are innermost (distance step) and the
     * centre is exactly between them. [theta] rotates the whole pattern.
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
    fun `ideal cross resolves via inner nines at the true centre`() {
        val e = estimateCentre(target(100f, 200f))
        assertEquals(CentreMethod.INNER_NINES, e.method)
        assertCentre(e, 100f, 200f)
    }

    @Test
    fun `off-centre target still resolves the centre`() {
        val e = estimateCentre(target(640f, 360f))
        assertEquals(CentreMethod.INNER_NINES, e.method)
        assertCentre(e, 640f, 360f)
    }

    @Test
    fun `rotated rows still resolve exactly because midpoints are rotation invariant`() {
        val e = estimateCentre(target(300f, 300f, theta = 10.0 * kotlin.math.PI / 180.0))
        assertEquals(CentreMethod.INNER_NINES, e.method)
        assertCentre(e, 300f, 300f, tol = 1f)
    }

    @Test
    fun `one missing inner nine falls back on that axis only`() {
        val digits = target(100f, 200f).toMutableList()
        // Drop a single horizontal 9 (innermost left, at cx = 80, cy = 200).
        val removed = digits.removeAll { it.value == 9 && it.cx < 100f && it.cy == 200f }
        assertTrue(removed)
        val e = estimateCentre(digits)
        assertEquals(CentreMethod.LINE_FIT_FALLBACK, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }

    @Test
    fun `both inner nines missing on an axis uses symmetric pair fallback`() {
        val digits = target(100f, 200f, hValues = 1..8, vValues = 1..9)
        val e = estimateCentre(digits)
        assertEquals(CentreMethod.LINE_FIT_FALLBACK, e.method)
        assertCentre(e, 100f, 200f, tol = 1.5f)
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
        assertEquals(CentreMethod.INNER_NINES, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }

    @Test
    fun `a digit sitting at the cross centre does not break resolution`() {
        val digits = target(100f, 200f).toMutableList()
        digits += digit(100f, 200f, 5)
        val e = estimateCentre(digits)
        assertEquals(CentreMethod.INNER_NINES, e.method)
        assertCentre(e, 100f, 200f, tol = 1f)
    }
}
