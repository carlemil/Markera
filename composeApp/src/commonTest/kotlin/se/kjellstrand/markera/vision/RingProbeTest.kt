package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // A 1500^2 white frame with a black axis-aligned 6/7 disk (a white 7 ring line inside it);
    // the digit centre sits a few px off the disk centre.
    private val w = 1500
    private val h = 1500
    private val ex = 760.0
    private val ey = 740.0
    private val a = 420.0
    private val b = 380.0
    private val cx = 766f
    private val cy = 736f
    private val centre = CentreEstimate(cx, cy, CentreMethod.LINE_INTERSECTION, hLine, vLine)
    private val dirs = listOf(-1.0 to 0.0, 1.0 to 0.0, 0.0 to -1.0, 0.0 to 1.0)

    private val gray: ByteArray by lazy {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val q = sqrt(((x - ex) / a) * ((x - ex) / a) + ((y - ey) / b) * ((y - ey) / b))
            out[y * w + x] = (if (q <= 1.0 && abs(q - 0.75) > 0.004) 30 else 220).toByte()
        }
        out
    }

    /** True rim distance along ray [i] from the digit centre. */
    private fun rim(i: Int): Double {
        var lo = 0.0
        var hi = 800.0
        repeat(60) {
            val s = (lo + hi) / 2
            val px = cx + s * dirs[i].first - ex
            val py = cy + s * dirs[i].second - ey
            if ((px / a) * (px / a) + (py / b) * (py / b) < 1) lo = s else hi = s
        }
        return lo
    }

    /** Digits whose side i predicts the rim [offsets] px off, ring width a quarter of the rim distance. */
    private fun digitsOff(offsets: List<Double>) = dirs.indices.flatMap { i ->
        val r = rim(i)
        row(cx, cy, dirs[i].first.toFloat(), dirs[i].second.toFloat(), (r + offsets[i]).toFloat(), (0.25 * r).toFloat())
    }

    private fun assertOnRim(result: RingProbeResult, offsets: List<Double>) {
        println("probes = ${result.probes}\nellipse = ${result.ellipse}")
        result.probes.forEachIndexed { i, p ->
            val startOff = hypot(p.startX - cx, p.startY - cy) - rim(i)
            assertTrue(abs(startOff - offsets[i]) < 0.5, "probe $i started $startOff px off the rim")
            val off = hypot(p.x - cx, p.y - cy) - rim(i)
            assertTrue(abs(off) <= 2.0, "probe $i ended $off px off the rim")
        }
        val e = assertNotNull(result.ellipse)
        assertTrue(abs(e.semiMajor - a) / a <= 0.01, "semiMajor ${e.semiMajor}")
        assertTrue(abs(e.semiMinor - b) / b <= 0.01, "semiMinor ${e.semiMinor}")
    }

    @Test
    fun `probes settle on the rim of a black ellipse`() {
        val offsets = listOf(-30.0, 30.0, 30.0, -30.0)
        assertOnRim(assertNotNull(fit67RingByProbes(gray, w, h, digitsOff(offsets), centre)), offsets)
    }

    @Test
    fun `a start more than half a ring width short still reaches the rim`() {
        // Left side predicted 0.6 ring widths inside the black: past the old half-width travel cap.
        val offsets = listOf(-0.6 * 0.25 * rim(0), 30.0, 30.0, -30.0)
        assertOnRim(assertNotNull(fit67RingByProbes(gray, w, h, digitsOff(offsets), centre)), offsets)
    }

    @Test
    fun `fit67Ring takes the probe ellipse when it sits on the rim`() {
        val fit = assertNotNull(fit67Ring(gray, w, h, digitsOff(listOf(-30.0, 30.0, 30.0, -30.0)), centre))
        println("fit67Ring = $fit")
        assertTrue(abs(fit.semiMajor - a) / a <= 0.01, "semiMajor ${fit.semiMajor}")
    }

    @Test
    fun `fit67Ring gives no ring when the probes find no rim`() {
        val digits = digitsOff(listOf(0.0, 0.0, 0.0, 0.0))
        // Uniform frame: every probe disk reads 0 dark, so the probe ellipse is implausible.
        val blank = ByteArray(w * h) { 220.toByte() }
        assertEquals(null, fit67Ring(blank, w, h, digits, centre))
        // Too few digits for a seed (one side only, the others can't be mirrored): nothing.
        assertEquals(null, fit67Ring(blank, w, h, digits.take(4), centre))
        assertEquals(null, fit67Ring(gray, w, h, digits, CentreEstimate(0f, 0f, CentreMethod.NONE)))
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

    // plausible67: a passing baseline, then each gate broken on its own.
    private val gateCentre = CentreEstimate(100f, 100f, CentreMethod.LINE_INTERSECTION)
    private val gateEllipse = FittedEllipse(100f, 100f, 100f, 80f, 0f)
    private val gateSeed = FittedEllipse(100f, 100f, 100f, 100f, 0f)
    private val halves = listOf(0.5f, 0.5f, 0.5f, 0.5f)

    private fun gate(
        fractions: List<Float> = halves,
        e: FittedEllipse = gateEllipse,
        seed: FittedEllipse? = gateSeed,
    ) = plausible67(fractions, e, gateCentre, seed)

    @Test
    fun `plausible67 passes the baseline and one off-image probe and no seed`() {
        assertTrue(gate())
        assertTrue(gate(fractions = listOf(Float.NaN, 0.5f, 0.5f, 0.5f)))
        assertTrue(gate(seed = null))
    }

    @Test
    fun `plausible67 rejects two off-image probes`() {
        assertFalse(gate(fractions = listOf(Float.NaN, Float.NaN, 0.5f, 0.5f)))
    }

    @Test
    fun `plausible67 rejects a probe off the rim`() {
        assertFalse(gate(fractions = listOf(0.66f, 0.5f, 0.5f, 0.5f)))
        assertFalse(gate(fractions = listOf(0.5f, 0.5f, 0.34f, 0.5f)))
    }

    @Test
    fun `plausible67 rejects an ellipse centred far from the digits`() {
        assertFalse(gate(e = gateEllipse.copy(cx = 126f)))
        assertTrue(gate(e = gateEllipse.copy(cx = 124f)))
    }

    @Test
    fun `plausible67 rejects a sliver`() {
        assertFalse(gate(e = gateEllipse.copy(semiMinor = 29f)))
    }

    @Test
    fun `plausible67 rejects a size far off the digit seed`() {
        assertFalse(gate(seed = gateSeed.copy(semiMajor = 100f / 0.49f)))
        assertFalse(gate(seed = gateSeed.copy(semiMajor = 100f / 1.71f)))
    }

    /** Digits on all four rows whose ring width is the spec quarter of [d65]. */
    private fun specDigits(d65: Float) = dirs.flatMap { (ux, uy) ->
        row(cx, cy, ux.toFloat(), uy.toFloat(), d65, 0.25f * d65)
    }

    @Test
    fun `fit67RingFromDigits gives the 6-7 radius at the digit centre`() {
        val fit = assertNotNull(fit67RingFromDigits(specDigits(400f), centre))
        assertEquals(cx, fit.cx)
        assertEquals(cy, fit.cy)
        assertEquals(400f, fit.semiMajor, 0.5f)
        assertEquals(fit.semiMajor, fit.semiMinor)
    }

    @Test
    fun `fit67RingFromDigits ignores a wild misread`() {
        val wild = DigitDetection(cx + 1188f, cy - 15f, cx + 1212f, cy + 15f, 9, 1f)
        val fit = assertNotNull(fit67RingFromDigits(specDigits(400f) + wild, centre))
        assertEquals(400f, fit.semiMajor, 0.5f)
    }

    @Test
    fun `fit67RingFromDigits needs five usable digits and a centre`() {
        val digits = specDigits(400f)
        assertNull(fit67RingFromDigits(digits.take(4), centre))
        assertNull(fit67RingFromDigits(digits, CentreEstimate(0f, 0f, CentreMethod.NONE)))
        // Low confidence and values outside 6..9 don't count towards the five.
        val unusable = digits.drop(4).map { it.copy(conf = 0.2f) } + digits.map { it.copy(value = 5) }
        assertNull(fit67RingFromDigits(digits.take(4) + unusable, centre))
    }
}
