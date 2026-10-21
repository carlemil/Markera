package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
