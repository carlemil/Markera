package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetPlaneTest {

    /** A target photographed from below and to the side: target mm -> image px. */
    private val camera = doubleArrayOf(5.0, 0.3, 1500.0, 0.2, 4.2, 1500.0, 0.0002, 0.0012, 1.0)

    private fun project(xMm: Double, yMm: Double): Pair<Double, Double> {
        val w = camera[6] * xMm + camera[7] * yMm + camera[8]
        return (camera[0] * xMm + camera[1] * yMm + camera[2]) / w to (camera[3] * xMm + camera[4] * yMm + camera[5]) / w
    }

    private val centre = project(0.0, 0.0).let { CentreEstimate(it.first.toFloat(), it.second.toFloat(), CentreMethod.LINE_INTERSECTION) }

    /** The 6/7 ring as the probe fit now stores it: the exact image of the 100 mm circle. */
    private val ring: FittedEllipse = run {
        val pts = listOf(-100.0 to 0.0, 100.0 to 0.0, 0.0 to -100.0, 0.0 to 100.0).map { project(it.first, it.second) }
        val plane = assertNotNull(
            TargetPlane.fromCross(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), 100.0),
        )
        assertNotNull(plane.circleOutline(100.0))
    }

    @Test
    fun everyRingMeasuresItsOwnRadiusFromTheStoredEllipseAndCentre() {
        for (r in RING_RADII_MM) {
            for (step in 0 until 24) {
                val t = step * PI / 12
                val (x, y) = project(r * cos(t), r * sin(t))
                assertEquals(r, distanceMm(x.toFloat(), y.toFloat(), centre, ring), 0.05, "r=$r t=$step")
            }
        }
    }

    @Test
    fun theOldAffineModelDriftsOutwards() {
        val (x, y) = project(0.0, 250.0)
        val (ox, oy) = affineOffsetMm(x.toFloat(), y.toFloat(), centre, ring)
        assertTrue(kotlin.math.abs(hypot(ox, oy) - 250.0) > 5.0, "affine measured ${hypot(ox, oy)} for 250 mm")
    }

    @Test
    fun ringOutlinePassesThroughTheProjectedCircle() {
        for (r in listOf(25.0, 75.0, 175.0)) {
            val o = ringOutline(ring, centre, r)
            val c = cos(o.rotationRad.toDouble())
            val s = sin(o.rotationRad.toDouble())
            for (step in 0 until 12) {
                val t = step * PI / 6
                val (x, y) = project(r * cos(t), r * sin(t))
                val dx = x - o.cx
                val dy = y - o.cy
                val u = (dx * c + dy * s) / o.semiMajor
                val v = (-dx * s + dy * c) / o.semiMinor
                assertEquals(1.0, u * u + v * v, 1e-3, "r=$r t=$step")
            }
        }
    }

    /** Head-on, the plane is the old affine model exactly. */
    @Test
    fun aCentredEllipseMatchesTheAffineModel() {
        val e = FittedEllipse(500f, 400f, 300f, 200f, 0.4f)
        val c = CentreEstimate(500f, 400f, CentreMethod.LINE_INTERSECTION)
        for ((x, y) in listOf(700f to 300f, 350f to 600f, 520f to 410f)) {
            val (ax, ay) = affineOffsetMm(x, y, c, e)
            val (px, py) = targetOffsetMm(x, y, c, e)
            assertEquals(ax, px, 1e-6)
            assertEquals(ay, py, 1e-6)
        }
    }

    @Test
    fun aCentreOutsideTheRingHasNoPlane() {
        val e = FittedEllipse(500f, 400f, 300f, 200f, 0f)
        assertNull(TargetPlane.of(CentreEstimate(900f, 400f, CentreMethod.LINE_INTERSECTION), e))
    }

    // A hand-placed centre dragged next to the ring: a 200 px circle about (500, 500) with
    // the centre at (690, 500) puts the vanishing line (the centre's polar) at x ~ 710.5.
    private val nearRing = FittedEllipse(500f, 500f, 200f, 200f, 0f)
    private val nearCentre = CentreEstimate(690f, 500f, CentreMethod.MANUAL)

    @Test
    fun aPointPastTheVanishingLineIsOffTheTarget() {
        val plane = assertNotNull(TargetPlane.of(nearCentre, nearRing))
        val (x, y) = plane.toMm(790.0, 500.0)
        assertTrue(x.isNaN() && y.isNaN(), "past the vanishing line mapped to ($x, $y)")
        // Between the centre and the line the map still holds.
        val (ix, iy) = plane.toMm(700.0, 500.0)
        assertTrue(ix.isFinite() && iy.isFinite(), "inside mapped to ($ix, $iy)")
    }

    @Test
    fun aHolePastTheVanishingLineScoresAMiss() {
        val hole = Detection(785f, 495f, 795f, 505f, 0.9f)
        val score = scoreHits(listOf(hole), nearCentre, nearRing).single()
        assertEquals(0, score.ring)
        assertTrue(!score.isInnerTen)
    }

    @Test
    fun aHandBoxPastTheVanishingLineHasNoSizeAndAMissStaysAMiss() {
        // holeBoxSizePx takes finite differences of toMm: past the line they are NaN, and it must say "no size",
        // never hand a NaN box to the scan screen. A box with a real size there still scores a miss.
        assertNull(holeBoxSizePx(790f, 500f, 4.5, nearCentre, nearRing))
        val size = holeBoxSizePx(600f, 500f, 4.5, nearCentre, nearRing)
        assertNotNull(size)
        assertTrue(size.first.isFinite() && size.second.isFinite() && size.first > 0f && size.second > 0f)
    }

    @Test
    fun aCrossMapGuardsTheVanishingLineOnTheCentresSide() {
        // The same map rebuilt from its four probe points (fromCross fixes h33, not the centre's sign).
        val exact = assertNotNull(TargetPlane.of(nearCentre, nearRing))
        val pts = listOf(-100.0 to 0.0, 100.0 to 0.0, 0.0 to -100.0, 0.0 to 100.0).map { exact.toImage(it.first, it.second) }
        val plane = assertNotNull(
            TargetPlane.fromCross(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), 100.0),
        )
        val (x, y) = plane.toMm(790.0, 500.0)
        assertTrue(x.isNaN() && y.isNaN(), "past the vanishing line mapped to ($x, $y)")
        val (ex, ey) = exact.toMm(600.0, 520.0)
        val (cx, cy) = plane.toMm(600.0, 520.0)
        assertEquals(ex, cx, 1e-6)
        assertEquals(ey, cy, 1e-6)
        // Mirrored, the image origin lies past the line (x ~ 289.5): the guard follows the centre's side.
        val leftCentre = CentreEstimate(310f, 500f, CentreMethod.MANUAL)
        val leftExact = assertNotNull(TargetPlane.of(leftCentre, nearRing))
        val leftPts = listOf(-100.0 to 0.0, 100.0 to 0.0, 0.0 to -100.0, 0.0 to 100.0).map { leftExact.toImage(it.first, it.second) }
        val left = assertNotNull(
            TargetPlane.fromCross(leftPts.map { it.first }.toDoubleArray(), leftPts.map { it.second }.toDoubleArray(), 100.0),
        )
        val (lx, ly) = left.toMm(400.0, 500.0)
        val (lex, ley) = leftExact.toMm(400.0, 500.0)
        assertEquals(lex, lx, 1e-6)
        assertEquals(ley, ly, 1e-6)
        val (px, py) = left.toMm(210.0, 500.0)
        assertTrue(px.isNaN() && py.isNaN(), "past the vanishing line mapped to ($px, $py)")
    }

    @Test
    fun handPlacedGeometryIsCheckedLikeAFoundRing() {
        assertTrue(plausibleGeometry(CentreEstimate(505f, 495f, CentreMethod.MANUAL), nearRing))
        // The centre dragged up to the ring: the vanishing line runs through the target.
        assertFalse(plausibleGeometry(nearCentre, nearRing))
        // A sliver, and a centre outside the ring.
        assertFalse(plausibleGeometry(CentreEstimate(500f, 500f, CentreMethod.MANUAL), nearRing.copy(semiMinor = 50f)))
        assertFalse(plausibleGeometry(CentreEstimate(900f, 500f, CentreMethod.MANUAL), nearRing))
        assertFalse(plausibleGeometry(CentreEstimate(500f, 500f, CentreMethod.MANUAL), nearRing.copy(semiMajor = 0f)))
    }
}
