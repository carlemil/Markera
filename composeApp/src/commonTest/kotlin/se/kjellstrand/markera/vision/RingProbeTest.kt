package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RingProbeTest {

    private val hLine = TargetLine(0f, 0f, 1f, 0f)
    private val vLine = TargetLine(0f, 0f, 0f, 1f)

    /** 6..9 digit boxes along ([ux],[uy]) from ([cx],[cy]) with dist(v) = [d65] - [width]*(v - 6.5). */
    private fun row(cx: Float, cy: Float, ux: Float, uy: Float, d65: Float, width: Float) = (6..9).map { value ->
        val d = d65 - width * (value - 6.5f)
        val x = cx + ux * d
        val y = cy + uy * d
        DigitDetection(x - 12f, y - 15f, x + 12f, y + 15f, value, 1f)
    }

    @Test
    fun `probes settle on the rim of a black ellipse`() {
        val w = 1500
        val h = 1500
        val ex = 760.0
        val ey = 740.0
        val a = 420.0
        val b = 380.0
        val gray = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val q = sqrt(((x - ex) / a) * ((x - ex) / a) + ((y - ey) / b) * ((y - ey) / b))
            val value = if (q <= 1.0 && abs(q - 0.75) > 0.004) 30 else 220 // disk with a white 7 ring line
            gray[y * w + x] = value.toByte()
        }
        val cx = 766f
        val cy = 736f
        // True rim distance along each ray from the digit centre.
        fun rim(ux: Double, uy: Double): Double {
            var lo = 0.0
            var hi = 800.0
            repeat(60) {
                val s = (lo + hi) / 2
                val px = cx + s * ux - ex
                val py = cy + s * uy - ey
                if ((px / a) * (px / a) + (py / b) * (py / b) < 1) lo = s else hi = s
            }
            return lo
        }
        val dirs = listOf(-1.0 to 0.0, 1.0 to 0.0, 0.0 to -1.0, 0.0 to 1.0)
        val offsets = listOf(-30f, 30f, 30f, -30f)
        val digits = dirs.indices.flatMap { i ->
            val d65 = rim(dirs[i].first, dirs[i].second).toFloat() + offsets[i]
            row(cx, cy, dirs[i].first.toFloat(), dirs[i].second.toFloat(), d65, 0.25f * d65)
        }
        val centre = CentreEstimate(cx, cy, CentreMethod.LINE_INTERSECTION, hLine, vLine)

        val result = assertNotNull(fit67RingByProbes(gray, w, h, digits, centre))
        println("probes = ${result.probes}\nellipse = ${result.ellipse}")
        result.probes.forEachIndexed { i, p ->
            val startOff = hypot(p.startX - cx, p.startY - cy) - rim(dirs[i].first, dirs[i].second)
            assertTrue(abs(abs(startOff) - 30) < 0.5, "probe $i started $startOff px off the rim")
            val off = hypot(p.x - cx, p.y - cy) - rim(dirs[i].first, dirs[i].second)
            assertTrue(abs(off) <= 2.0, "probe $i ended $off px off the rim")
        }
        val e = assertNotNull(result.ellipse)
        assertTrue(abs(e.semiMajor - a) / a <= 0.01, "semiMajor ${e.semiMajor}")
        assertTrue(abs(e.semiMinor - b) / b <= 0.01, "semiMinor ${e.semiMinor}")
    }

    @Test
    fun `four points on a rotated ellipse give that ellipse back`() {
        val truth = FittedEllipse(512f, 430f, 300f, 220f, 0.3f)
        val c = cos(0.3)
        val s = sin(0.3)
        val params = doubleArrayOf(10.0, 100.0, 200.0, 290.0).map { it * PI / 180 }
        val xs = DoubleArray(4) { 512 + 300 * cos(params[it]) * c - 220 * sin(params[it]) * s }
        val ys = DoubleArray(4) { 430 + 300 * cos(params[it]) * s + 220 * sin(params[it]) * c }
        // Frame rotated a quarter turn from the major axis: the axes must swap back.
        val e = assertNotNull(ellipseThroughFour(xs, ys, 0.3 - PI / 2))
        println("fit = $e")
        assertEquals(truth.cx, e.cx, 0.01f)
        assertEquals(truth.cy, e.cy, 0.01f)
        assertEquals(truth.semiMajor, e.semiMajor, 0.01f)
        assertEquals(truth.semiMinor, e.semiMinor, 0.01f)
        assertEquals(truth.rotationRad, e.rotationRad, 1e-4f)
    }

    @Test
    fun `start distances come from the digits on each side and a bare side mirrors its opposite`() {
        val cx = 700f
        val cy = 650f
        val digits = row(cx, cy, -1f, 0f, 360f, 45f) + row(cx, cy, 1f, 0f, 400f, 50f) + row(cx, cy, 0f, -1f, 380f, 40f)
        val centre = CentreEstimate(cx, cy, CentreMethod.LINE_INTERSECTION, hLine, vLine)
        // Blank image: no dark pixels, only the starts matter here.
        val result = assertNotNull(fit67RingByProbes(ByteArray(1400 * 1300), 1400, 1300, digits, centre))
        val (l, r, t, b) = result.probes
        assertEquals(cx - 360f, l.startX, 0.01f)
        assertEquals(cx + 400f, r.startX, 0.01f)
        assertEquals(cy - 380f, t.startY, 0.01f)
        assertEquals(cy + 380f, b.startY, 0.01f)
        assertEquals(cy, l.startY, 0.01f)
        assertEquals(cx, b.startX, 0.01f)
    }
}
