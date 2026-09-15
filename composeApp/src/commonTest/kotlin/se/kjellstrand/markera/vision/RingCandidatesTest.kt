package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A synthetic target photo: light paper, a dark tilted 6/7 disk with two light
 * ring lines printed inside it, and a black shadow crescent off to the left of
 * the disk whose outer edge is a sharper dark->light step than the rim itself.
 */
class RingCandidatesTest {

    private val w = 1200
    private val h = 1200
    private val truth = FittedEllipse(610f, 590f, 330f, 290f, (20.0 * PI / 180.0).toFloat())
    private val centre = CentreEstimate(truth.cx, truth.cy, CentreMethod.LINE_INTERSECTION)

    private val gray: ByteArray by lazy {
        val out = ByteArray(w * h)
        val ct = cos(truth.rotationRad.toDouble())
        val st = sin(truth.rotationRad.toDouble())
        var seed = 12345L
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - truth.cx.toDouble()
            val dy = y - truth.cy.toDouble()
            val xr = dx * ct + dy * st
            val yr = -dx * st + dy * ct
            // Normalised ellipse radius: 1.0 on the rim.
            val q = sqrt((xr / truth.semiMajor) * (xr / truth.semiMajor) + (yr / truth.semiMinor) * (yr / truth.semiMinor))
            val dist = hypot(dx, dy)
            val ang = atan2(dy, dx) * 180.0 / PI // -180..180, 180 = left
            var v = when {
                q <= 1.0 && (abs(q - 0.8) < 0.01 || abs(q - 0.55) < 0.01) -> 200 // printed ring lines
                q <= 1.0 -> 40 // black disk
                dist in 380.0..580.0 && abs(ang) > 120.0 -> 0 // shadow crescent, left
                else -> 200 // paper
            }
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            v += ((seed ushr 33) % 9).toInt() - 4 // deterministic noise +-4
            out[y * w + x] = v.coerceIn(0, 255).toByte()
        }
        out
    }

    private fun assertNear(expected: FittedEllipse, actual: FittedEllipse, label: String) {
        val dc = hypot(actual.cx - expected.cx, actual.cy - expected.cy)
        val da = abs(actual.semiMajor - expected.semiMajor) / expected.semiMajor
        val db = abs(actual.semiMinor - expected.semiMinor) / expected.semiMinor
        println("$label: $actual dc=${dc}px da=${da * 100}% db=${db * 100}%")
        assertTrue(dc <= 0.02f * expected.semiMajor, "$label centre off by $dc px")
        assertTrue(da <= 0.02f, "$label semiMajor off by ${da * 100}%")
        assertTrue(db <= 0.02f, "$label semiMinor off by ${db * 100}%")
    }

    private val badSeed = FittedEllipse(truth.cx, truth.cy, 1.4f * truth.semiMajor, 1.4f * truth.semiMajor, 0f)

    @Test
    fun `refine falls back to a seed whose radius is 40 percent too large`() {
        val refined = refine67ToEdge(gray, w, h, badSeed)
        println("refine(bad seed) = $refined")
        assertEquals(badSeed, refined)
    }

    @Test
    fun `top ring candidate recovers the true rim from the bad seed`() {
        val candidates = ringCandidates(gray, w, h, centre, badSeed)
        println("candidates = $candidates")
        assertTrue(candidates.isNotEmpty())
        assertNear(truth, candidates.first(), "top candidate")
    }

    @Test
    fun `rim contrast ranks the true rim above an inner ring line`() {
        val otsu = otsuThreshold(gray, w, h)
        val onLine = truth.copy(semiMajor = 0.8f * truth.semiMajor, semiMinor = 0.8f * truth.semiMinor)
        val rim = rimContrast(gray, w, h, truth, otsu)
        val line = rimContrast(gray, w, h, onLine, otsu)
        println("otsu=$otsu rimContrast truth=$rim innerLine=$line")
        assertTrue(rim > line)
    }

    @Test
    fun `refine still recovers the rim from a good seed`() {
        val goodSeed = FittedEllipse(truth.cx, truth.cy, 310f, 310f, 0f)
        val refined = refine67ToEdge(gray, w, h, goodSeed)
        assertNear(truth, refined, "refine(good seed)")
        val dRot = abs(((refined.rotationRad - truth.rotationRad) % PI.toFloat() + PI.toFloat() * 1.5f) % PI.toFloat() - PI.toFloat() / 2)
        assertTrue(dRot < 3f * PI.toFloat() / 180f, "rotation off by $dRot rad")
    }
}
