package eval

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.vision.fitEllipseDirect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Confirms the direct ellipse fit + conic->geometry conversion recover a known
 * ellipse, so any rim-fit error is in edge extraction, not the solver.
 */
class EllipseFitSanityTest {

    private fun sample(
        cx: Double,
        cy: Double,
        a: Double,
        b: Double,
        rot: Double,
        n: Int = 120,
        noise: Double = 0.0,
    ): List<DoubleArray> {
        val ct = cos(rot)
        val st = sin(rot)
        return (0 until n).map { i ->
            val t = 2.0 * PI * i / n
            val ex = a * cos(t)
            val ey = b * sin(t)
            // rotate + translate; deterministic "noise" via a bounded wiggle
            val nx = if (noise > 0) noise * cos(7.0 * t) else 0.0
            val ny = if (noise > 0) noise * sin(11.0 * t) else 0.0
            doubleArrayOf(cx + ex * ct - ey * st + nx, cy + ex * st + ey * ct + ny)
        }
    }

    @Test
    fun `recovers an axis-aligned ellipse`() {
        val e = fitEllipseDirect(sample(500.0, 400.0, 300.0, 180.0, 0.0))
        assertNotNull(e)
        e!!
        assertTrue("cx ${e.cx}", abs(e.cx - 500.0) < 1.0)
        assertTrue("cy ${e.cy}", abs(e.cy - 400.0) < 1.0)
        assertTrue("a ${e.semiMajor}", abs(e.semiMajor - 300.0) < 1.5)
        assertTrue("b ${e.semiMinor}", abs(e.semiMinor - 180.0) < 1.5)
    }

    @Test
    fun `recovers a tilted ellipse`() {
        val rot = 25.0 * PI / 180.0
        val e = fitEllipseDirect(sample(300.0, 600.0, 260.0, 150.0, rot))
        assertNotNull(e)
        e!!
        assertTrue("cx ${e.cx}", abs(e.cx - 300.0) < 1.5)
        assertTrue("cy ${e.cy}", abs(e.cy - 600.0) < 1.5)
        assertTrue("a ${e.semiMajor}", abs(e.semiMajor - 260.0) < 2.0)
        assertTrue("b ${e.semiMinor}", abs(e.semiMinor - 150.0) < 2.0)
        // rotation modulo PI, within ~2 degrees
        var d = abs(e.rotationRad - rot) % PI
        if (d > PI / 2) d = PI - d
        assertTrue("rot ${e.rotationRad} vs $rot (d=$d)", d < 0.035)
    }

    @Test
    fun `tolerates mild noise`() {
        val e = fitEllipseDirect(sample(400.0, 400.0, 280.0, 200.0, 0.4, noise = 3.0))
        assertNotNull(e)
        e!!
        assertTrue("cx ${e.cx}", abs(e.cx - 400.0) < 6.0)
        assertTrue("cy ${e.cy}", abs(e.cy - 400.0) < 6.0)
        assertTrue("a ${e.semiMajor}", abs(e.semiMajor - 280.0) < 12.0)
        assertTrue("b ${e.semiMinor}", abs(e.semiMinor - 200.0) < 12.0)
    }
}
