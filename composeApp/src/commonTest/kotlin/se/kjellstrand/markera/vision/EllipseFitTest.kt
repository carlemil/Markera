package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class EllipseFitTest {

    private fun labelGridForEllipse(
        w: Int, h: Int, cx: Int, cy: Int, a: Int, b: Int, thetaRad: Double,
        label: Int = 7,
    ): IntArray {
        val labels = IntArray(w * h)
        val cosT = cos(thetaRad)
        val sinT = sin(thetaRad)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - cx).toDouble()
                val dy = (y - cy).toDouble()
                val u = dx * cosT + dy * sinT
                val v = -dx * sinT + dy * cosT
                if ((u * u) / (a.toDouble() * a) + (v * v) / (b.toDouble() * b) <= 1.0) {
                    labels[y * w + x] = label
                }
            }
        }
        return labels
    }

    @Test
    fun `axis-aligned ellipse recovers semi-axes`() {
        val labels = labelGridForEllipse(w = 200, h = 200, cx = 100, cy = 100, a = 50, b = 30, thetaRad = 0.0)
        val e = fitEllipseFromBlob(labels, 200, 7, 50, 150, 70, 130)
        assertNotNull(e); e!!
        assertEquals(100f, e.cx, 1f)
        assertEquals(100f, e.cy, 1f)
        assertEquals(50f, e.semiMajor, 2f)
        assertEquals(30f, e.semiMinor, 2f)
        // Rotation should be near 0 (mod π)
        val rot = ((e.rotationRad.toDouble() % PI) + PI) % PI
        assertTrue(rot < 0.1 || PI - rot < 0.1, "rotation $rot should be near 0 or π")
    }

    @Test
    fun `rotated ellipse recovers axes and angle`() {
        val theta = PI / 4
        val labels = labelGridForEllipse(w = 240, h = 240, cx = 120, cy = 120, a = 60, b = 30, thetaRad = theta)
        val e = fitEllipseFromBlob(labels, 240, 7, 50, 190, 50, 190)
        assertNotNull(e); e!!
        assertEquals(120f, e.cx, 1.5f)
        assertEquals(120f, e.cy, 1.5f)
        assertEquals(60f, e.semiMajor, 3f)
        assertEquals(30f, e.semiMinor, 3f)
        val recovered = e.rotationRad.toDouble()
        val diffMod = ((recovered - theta) % PI + PI) % PI
        val minDiff = minOf(diffMod, PI - diffMod)
        assertTrue(abs(minDiff) < 0.1, "recovered rotation $recovered should match $theta (mod π); diff=$minDiff")
    }

    @Test
    fun `circle returns equal axes`() {
        val labels = labelGridForEllipse(w = 200, h = 200, cx = 100, cy = 100, a = 40, b = 40, thetaRad = 0.0)
        val e = fitEllipseFromBlob(labels, 200, 7, 60, 140, 60, 140)
        assertNotNull(e); e!!
        assertEquals(40f, e.semiMajor, 2f)
        assertEquals(40f, e.semiMinor, 2f)
    }

    @Test
    fun `empty blob returns null`() {
        val labels = IntArray(200 * 200) // all zero
        assertNull(fitEllipseFromBlob(labels, 200, 7, 0, 199, 0, 199))
    }
}
