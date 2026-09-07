package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitScoringTest {

    private fun centre(x: Float, y: Float) = CentreEstimate(x, y, CentreMethod.LINE_INTERSECTION)

    /** A circular 6/7 ring; semiMajor 100 px makes mmPerPx == 1.0. */
    private fun ring(cx: Float, cy: Float, r: Float = 100f) = FittedEllipse(cx, cy, r, r, 0f)

    /** A zero-size ("point") hole, so the edge gauge subtracts nothing and
     *  the ring is decided purely by the centre distance. */
    private fun pointAt(cx: Float, cy: Float, dx: Float, dy: Float = 0f): Detection {
        val x = cx + dx
        val y = cy + dy
        return Detection(left = x, top = y, right = x, bottom = y, conf = 0.9f)
    }

    @Test
    fun `dead-centre hit scores ring 10 with inner-X`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 0f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(10, s.ring)
        assertTrue(s.isInnerTen)
        assertEquals(0.0, s.distanceMm, 1e-3)
    }

    @Test
    fun `12_5mm boundary still counts as inner-X`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 12.5f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(10, s.ring)
        assertTrue(s.isInnerTen)
    }

    @Test
    fun `just past inner-X is still ring 10 but not X`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 12.6f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(10, s.ring)
        assertFalse(s.isInnerTen)
    }

    @Test
    fun `25mm boundary is ring 10`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 25f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(10, s.ring)
        assertFalse(s.isInnerTen)
    }

    @Test
    fun `25_01mm drops to ring 9`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 25.01f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(9, s.ring)
    }

    @Test
    fun `100mm boundary is ring 7 (the black 6_7 edge)`() {
        val s = scoreHits(listOf(pointAt(200f, 200f, 100f)), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(7, s.ring)
    }

    @Test
    fun `250mm boundary is ring 1`() {
        val s = scoreHits(listOf(pointAt(400f, 400f, 250f)), centre(400f, 400f), ring(400f, 400f)).single()
        assertEquals(1, s.ring)
    }

    @Test
    fun `past 250mm is a miss`() {
        val s = scoreHits(listOf(pointAt(400f, 400f, 250.01f)), centre(400f, 400f), ring(400f, 400f)).single()
        assertEquals(0, s.ring)
    }

    @Test
    fun `edge gauge promotes a hole whose edge crosses the line`() {
        // Hole centre at 26mm (would be ring 9 by centre), 2mm radius bbox →
        // inner edge at 24mm → ring 10.
        val d = Detection(left = 224f, top = 198f, right = 228f, bottom = 202f, conf = 0.9f)
        val s = scoreHits(listOf(d), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(10, s.ring)
        assertEquals(26.0, s.distanceMm, 1e-3)
    }

    @Test
    fun `bbox centre and centre distance are reported`() {
        // bbox centre (150,250); target centre (200,200) → 70.71mm.
        val d = Detection(left = 100f, top = 200f, right = 200f, bottom = 300f, conf = 0.9f)
        val s = scoreHits(listOf(d), centre(200f, 200f), ring(200f, 200f)).single()
        assertEquals(150f, s.centerXpx, 1e-3f)
        assertEquals(250f, s.centerYpx, 1e-3f)
        assertEquals(70.710678, s.distanceMm, 1e-3)
    }

    @Test
    fun `ellipse stretches the foreshortened axis back`() {
        // 100x50 ellipse: a hole at the major edge (+100) and one at the minor
        // edge (+50) both unstretch to 100mm → ring 7.
        val e = FittedEllipse(cx = 0f, cy = 0f, semiMajor = 100f, semiMinor = 50f, rotationRad = 0f)
        val sMajor = scoreHits(listOf(pointAt(0f, 0f, 100f, 0f)), centre(0f, 0f), e).single()
        val sMinor = scoreHits(listOf(pointAt(0f, 0f, 0f, 50f)), centre(0f, 0f), e).single()
        assertEquals(7, sMajor.ring)
        assertEquals(7, sMinor.ring)
        assertEquals(sMajor.distanceMm, sMinor.distanceMm, 0.5)
    }

    @Test
    fun `ellipse rotation rotates hole offsets before stretching`() {
        // Rotated 90°: semiMajor along y. A hole at (+49,0) unstretches to ~98mm;
        // one at (0,+98) is the same true distance. Both ring 7 (clear of 100mm).
        val e = FittedEllipse(cx = 0f, cy = 0f, semiMajor = 100f, semiMinor = 50f, rotationRad = (PI / 2).toFloat())
        val sX = scoreHits(listOf(pointAt(0f, 0f, 49f, 0f)), centre(0f, 0f), e).single()
        val sY = scoreHits(listOf(pointAt(0f, 0f, 0f, 98f)), centre(0f, 0f), e).single()
        assertEquals(7, sX.ring)
        assertEquals(7, sY.ring)
        assertEquals(sX.distanceMm, sY.distanceMm, 1e-3)
    }

    @Test
    fun `results are sorted highest-score first`() {
        val low = pointAt(400f, 400f, 240f) // ring 1
        val high = pointAt(400f, 400f, 5f) // inner X
        val mid = pointAt(400f, 400f, 60f) // ring 8
        val scores = scoreHits(listOf(low, high, mid), centre(400f, 400f), ring(400f, 400f))
        assertEquals(listOf(10, 8, 1), scores.map { it.ring })
        assertTrue(scores.first().isInnerTen)
    }

    @Test
    fun `empty input returns empty list`() {
        assertTrue(scoreHits(emptyList(), centre(200f, 200f), ring(200f, 200f)).isEmpty())
    }

    @Test
    fun `degenerate ring returns empty list`() {
        val bad = FittedEllipse(cx = 0f, cy = 0f, semiMajor = 0f, semiMinor = 0f, rotationRad = 0f)
        assertTrue(scoreHits(listOf(pointAt(0f, 0f, 1f)), centre(0f, 0f), bad).isEmpty())
    }

    @Test
    fun `distanceMm measures both axes of a tilted ellipse as the 100mm rim`() {
        // Tilted, foreshortened 6/7 rim: either semi-axis endpoint is the
        // black edge, i.e. TARGET_BLACK_RING_RADIUS_MM away.
        val rot = (PI / 6.0).toFloat()
        val e = FittedEllipse(cx = 300f, cy = 300f, semiMajor = 200f, semiMinor = 80f, rotationRad = rot)
        val c = centre(300f, 300f)
        val major = distanceMm(
            300f + 200f * cos(rot), 300f + 200f * sin(rot), c, e,
        )
        val minor = distanceMm(
            300f - 80f * sin(rot), 300f + 80f * cos(rot), c, e,
        )
        assertEquals(100.0, major, 1e-3)
        assertEquals(100.0, minor, 1e-3)
    }
}
