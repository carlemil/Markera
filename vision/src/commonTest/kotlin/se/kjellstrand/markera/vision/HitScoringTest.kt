package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitScoringTest {

    @Test
    fun scoreAtOnTheTenLineWithNoRadiusIsATen() {
        assertEquals(10 to false, scoreAt(25.0, 0.0))
    }

    @Test
    fun scoreAtGaugesTheEdgeByTheHoleRadius() {
        assertEquals(9, scoreAt(27.0, 0.0).first)
        // A .22 lr hole (radius 2.83 mm) 27 mm out still touches the 10 line.
        assertEquals(10, scoreAt(27.0, 2.83).first)
        assertFalse(scoreAt(14.0, 0.0).second)
        assertTrue(scoreAt(14.0, 2.0).second)
    }

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
    fun `100mm boundary is ring 7 - the black 6_7 edge`() {
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
        // 100x50 ellipse: a hole just inside the major edge (+99) and one just
        // inside the minor edge (+49.5) both unstretch to 99mm → ring 7. (Not on
        // the line itself: there the last bit of rounding decides the ring.)
        val e = FittedEllipse(cx = 0f, cy = 0f, semiMajor = 100f, semiMinor = 50f, rotationRad = 0f)
        val sMajor = scoreHits(listOf(pointAt(0f, 0f, 99f, 0f)), centre(0f, 0f), e).single()
        val sMinor = scoreHits(listOf(pointAt(0f, 0f, 0f, 49.5f)), centre(0f, 0f), e).single()
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
    fun `results stay in input order - hit i is detection i`() {
        val low = pointAt(400f, 400f, 240f) // ring 1
        val high = pointAt(400f, 400f, 5f) // inner X
        val mid = pointAt(400f, 400f, 60f) // ring 8
        val scores = scoreHits(listOf(low, high, mid), centre(400f, 400f), ring(400f, 400f))
        assertEquals(listOf(1, 10, 8), scores.map { it.ring })
        assertTrue(scores[1].isInnerTen)
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

    @Test
    fun `every point of a drawn ring outline scores its own radius`() {
        // Rotated, eccentric rim with the digit centre off the ellipse centre —
        // an axis-aligned circle would pass even with the rotation dropped. Under
        // perspective the outlines are not centred on the digit centre, so only
        // the round trip is pinned down.
        val e = FittedEllipse(cx = 300f, cy = 280f, semiMajor = 200f, semiMinor = 80f, rotationRad = (PI / 5.0).toFloat())
        val c = centre(317f, 291f)
        for (r in listOf(12.5, 25.0, 50.0, 75.0, 100.0)) {
            val o = ringOutline(e, c, r)
            val cosR = cos(o.rotationRad.toDouble())
            val sinR = sin(o.rotationRad.toDouble())
            for (i in 0 until 16) {
                // Parametric point of the oval the overlay draws: axis-aligned,
                // then rotated about the outline centre.
                val t = 2.0 * PI * i / 16.0
                val px = o.semiMajor * cos(t)
                val py = o.semiMinor * sin(t)
                val x = o.cx + (px * cosR - py * sinR).toFloat()
                val y = o.cy + (px * sinR + py * cosR).toFloat()
                assertEquals(r, distanceMm(x, y, c, e), 1e-2)
            }
        }
    }

    @Test
    fun `targetOffsetMm keeps the photo direction of a tilted ellipse`() {
        // Same tilted rim: the major-axis endpoint is 100mm out along the photo
        // direction (cos θ, sin θ) — the un-projection is rotated back, not left
        // in the ellipse's own frame.
        val rot = (PI / 6.0).toFloat()
        val e = FittedEllipse(cx = 300f, cy = 300f, semiMajor = 200f, semiMinor = 80f, rotationRad = rot)
        val (dx, dy) = targetOffsetMm(
            300f + 200f * cos(rot), 300f + 200f * sin(rot), centre(300f, 300f), e,
        )
        assertEquals(100.0 * cos(rot.toDouble()), dx, 1e-3)
        assertEquals(100.0 * sin(rot.toDouble()), dy, 1e-3)
        assertEquals(100.0, kotlin.math.hypot(dx, dy), 1e-3)
    }

    // A tilted photo under real perspective: minor/major 0.8, rotated off the
    // image axes, the digit centre off the ellipse centre. 5 px per mm along
    // the major axis, so a 9 mm hole is a box of about 40-45 px.
    private val tiltedRing = FittedEllipse(cx = 1000f, cy = 1000f, semiMajor = 500f, semiMinor = 400f, rotationRad = 0.5f)
    private val tiltedCentre = centre(1012f, 1035f)
    private val tiltedPlane = TargetPlane.of(tiltedCentre, tiltedRing)!!

    /** A 9 mm hole (radius 4.5 mm). */
    private val holeRadius = 4.5

    /**
     * The detector's box for a true round hole of [radiusMm] whose centre is
     * [distMm] from the target centre at [angleDeg] (target frame): the image of
     * the hole's rim, and its axis-aligned bounds.
     */
    private fun tiltedHole(distMm: Double, angleDeg: Int, radiusMm: Double = holeRadius): Detection {
        val a = angleDeg * PI / 180.0
        val mx = distMm * cos(a)
        val my = distMm * sin(a)
        var left = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE
        for (i in 0 until 720) {
            val t = 2.0 * PI * i / 720.0
            val (x, y) = tiltedPlane.toImage(mx + radiusMm * cos(t), my + radiusMm * sin(t))
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        return Detection(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), conf = 0.9f)
    }

    private val directions = (0 until 360 step 15).toList()

    @Test
    fun tiltedHoleWhoseEdgeJustTouchesTheTenLineScoresTen() {
        // Edge 0.15 mm inside the 10 line: the higher ring in every direction.
        for (deg in directions) {
            val s = scoreHits(listOf(tiltedHole(25.0 - 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            // The centre itself is measured right; only the radius decides.
            assertEquals(10, scoreAt(s.distanceMm, holeRadius).first, "centre at $deg°")
            assertEquals(10, s.ring, "hole at $deg°")
        }
    }

    @Test
    fun tiltedHoleJustShortOfTheTenLineStaysNine() {
        // Edge 0.15 mm outside the 10 line: the radius must not be over-sized either.
        for (deg in directions) {
            val s = scoreHits(listOf(tiltedHole(25.0 + 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            assertEquals(9, s.ring, "hole at $deg°")
        }
    }

    @Test
    fun tiltedHoleWhoseEdgeJustTouchesTheInnerXIsAnInnerX() {
        for (deg in directions) {
            val inside = scoreHits(listOf(tiltedHole(12.5 - 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            val outside = scoreHits(listOf(tiltedHole(12.5 + 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            assertTrue(inside.isInnerTen, "inside at $deg°")
            assertFalse(outside.isInnerTen, "outside at $deg°")
        }
    }

    @Test
    fun holeBoxSizePxIsReadBackAsItsRadius() {
        // A hand-placed caliber box scores the caliber's radius, tilted or not:
        // its edge 0.05 mm inside the 10 line is a 10, 0.05 mm outside a 9.
        fun scoreBoxAt(edgeMm: Double, deg: Int): Int {
            val a = deg * PI / 180.0
            val (x, y) = tiltedPlane.toImage((edgeMm + holeRadius) * cos(a), (edgeMm + holeRadius) * sin(a))
            val (w, h) = holeBoxSizePx(x.toFloat(), y.toFloat(), holeRadius, tiltedCentre, tiltedRing)!!
            val box = Detection((x - w / 2).toFloat(), (y - h / 2).toFloat(), (x + w / 2).toFloat(), (y + h / 2).toFloat(), conf = 1f)
            return scoreHits(listOf(box), tiltedCentre, tiltedRing).single().ring
        }
        for (deg in directions) {
            assertEquals(10, scoreBoxAt(24.95, deg), "inside at $deg°")
            assertEquals(9, scoreBoxAt(25.05, deg), "outside at $deg°")
        }
        // Frontal: the old square of 2r · semiMajor / 100 px.
        val (w, h) = holeBoxSizePx(230f, 200f, 2.83, centre(200f, 200f), ring(200f, 200f, 500f))!!
        assertEquals(2 * 2.83 * 5, w.toDouble(), 1e-3)
        assertEquals(2 * 2.83 * 5, h.toDouble(), 1e-3)
    }

    @Test
    fun tiltedHoleOnTheBlackEdgeIsGaugedInTargetMm() {
        // Far from the centre, where perspective changes the local scale most.
        for (deg in directions) {
            val inside = scoreHits(listOf(tiltedHole(100.0 - 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            val outside = scoreHits(listOf(tiltedHole(100.0 + 0.15 + holeRadius, deg)), tiltedCentre, tiltedRing).single()
            assertEquals(7, inside.ring, "inside at $deg°")
            assertEquals(6, outside.ring, "outside at $deg°")
        }
    }
}
