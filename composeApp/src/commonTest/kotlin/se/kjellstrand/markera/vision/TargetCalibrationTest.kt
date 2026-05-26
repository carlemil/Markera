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

class TargetCalibrationTest {

    /** Build a grayscale buffer with one dark disc on a white background. */
    private fun discBuffer(
        w: Int,
        h: Int,
        cx: Int,
        cy: Int,
        r: Int,
        discValue: Int = 30,
        bgValue: Int = 220,
        extra: (ByteArray, Int) -> Unit = { _, _ -> },
    ): ByteArray {
        val buf = ByteArray(w * h) { bgValue.toByte() }
        val r2 = r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2) buf[y * w + x] = discValue.toByte()
            }
        }
        extra(buf, w)
        return buf
    }

    /** Build a grayscale buffer with a rotated tilted ellipse (axes [a]x[b], angle [thetaRad]). */
    private fun ellipseBuffer(
        w: Int,
        h: Int,
        cx: Int,
        cy: Int,
        a: Int,
        b: Int,
        thetaRad: Double,
        discValue: Int = 30,
        bgValue: Int = 220,
    ): ByteArray {
        val buf = ByteArray(w * h) { bgValue.toByte() }
        val cosT = cos(thetaRad)
        val sinT = sin(thetaRad)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - cx).toDouble()
                val dy = (y - cy).toDouble()
                // Rotate into ellipse frame
                val u = dx * cosT + dy * sinT
                val v = -dx * sinT + dy * cosT
                if ((u * u) / (a.toDouble() * a) + (v * v) / (b.toDouble() * b) <= 1.0) {
                    buf[y * w + x] = discValue.toByte()
                }
            }
        }
        return buf
    }

    @Test
    fun `centred disc recovers centre and scale as a near-circle`() {
        val buf = discBuffer(w = 400, h = 400, cx = 200, cy = 200, r = 50)
        val c = calibrateFromGrayscale(buf, 400, 400)
        assertNotNull(c); c!!
        assertEquals(200f, c.centerX, 1.5f)
        assertEquals(200f, c.centerY, 1.5f)
        assertEquals(50f, c.semiMajorPx, 2f)
        assertEquals(50f, c.semiMinorPx, 2f)
        assertEquals(2.0, c.mmPerPx, 0.1)
        assertTrue(c.confidence > 0.6f, "confidence should be high for a clean disc")
    }

    @Test
    fun `off-centre disc still recovers centre and scale`() {
        val buf = discBuffer(w = 400, h = 400, cx = 120, cy = 280, r = 40)
        val c = calibrateFromGrayscale(buf, 400, 400)
        assertNotNull(c); c!!
        assertEquals(120f, c.centerX, 2f)
        assertEquals(280f, c.centerY, 2f)
        assertEquals(40f, c.semiMajorPx, 2f)
        assertEquals(2.5, c.mmPerPx, 0.15)
    }

    @Test
    fun `tiny speck near the disc does not pull the centre`() {
        val buf = discBuffer(w = 400, h = 400, cx = 200, cy = 200, r = 50) { b, w ->
            for (y in 5..7) for (x in 5..7) b[y * w + x] = 30.toByte()
        }
        val c = calibrateFromGrayscale(buf, 400, 400)
        assertNotNull(c); c!!
        assertEquals(200f, c.centerX, 1.5f)
        assertEquals(200f, c.centerY, 1.5f)
    }

    @Test
    fun `all-white buffer returns null`() {
        val buf = ByteArray(400 * 400) { 240.toByte() }
        assertNull(calibrateFromGrayscale(buf, 400, 400))
    }

    @Test
    fun `tiny disc fails the size gate and returns null`() {
        val buf = discBuffer(w = 400, h = 400, cx = 200, cy = 200, r = 3)
        assertNull(calibrateFromGrayscale(buf, 400, 400))
    }

    @Test
    fun `full-width letterbox bar is rejected so the disc is picked`() {
        val buf = discBuffer(w = 400, h = 600, cx = 200, cy = 300, r = 50) { b, w ->
            for (y in 0 until 80) for (x in 0 until w) b[y * w + x] = 20.toByte()
            for (y in 520 until 600) for (x in 0 until w) b[y * w + x] = 20.toByte()
        }
        val c = calibrateFromGrayscale(buf, 400, 600)
        assertNotNull(c); c!!
        assertEquals(200f, c.centerX, 2f)
        assertEquals(300f, c.centerY, 2f)
        assertEquals(50f, c.semiMajorPx, 2f)
    }

    @Test
    fun `tilted ellipse recovers axes and rotation`() {
        // 80×50 ellipse rotated 30 degrees
        val theta = PI / 6
        val buf = ellipseBuffer(w = 400, h = 400, cx = 200, cy = 200, a = 80, b = 50, thetaRad = theta)
        val c = calibrateFromGrayscale(buf, 400, 400)
        assertNotNull(c); c!!
        assertEquals(200f, c.centerX, 2f)
        assertEquals(200f, c.centerY, 2f)
        assertEquals(80f, c.semiMajorPx, 3f)
        assertEquals(50f, c.semiMinorPx, 3f)
        // Rotation can come back as θ or θ ± π — only the axis matters, so test sin/cos modulo π
        val recovered = c.rotationRad.toDouble()
        val diffMod = ((recovered - theta) % PI + PI) % PI
        val minDiff = minOf(diffMod, PI - diffMod)
        assertTrue(abs(minDiff) < 0.1, "recovered rotation $recovered should match $theta (mod π); diff=$minDiff")
        // mmPerPx is derived from the major axis: 100mm / 80px = 1.25
        assertEquals(1.25, c.mmPerPx, 0.08)
    }
}
