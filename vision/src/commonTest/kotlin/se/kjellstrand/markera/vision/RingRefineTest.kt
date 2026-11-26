package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingRefineTest {

    /** A tilted target: target mm -> image px, about 4.5 px/mm. */
    private val camera = doubleArrayOf(4.6, 0.2, 600.0, 0.15, 4.1, 600.0, 0.00012, 0.0005, 1.0)
    private val size = 1200

    private fun project(xMm: Double, yMm: Double): Pair<Double, Double> {
        val w = camera[6] * xMm + camera[7] * yMm + camera[8]
        return (camera[0] * xMm + camera[1] * yMm + camera[2]) / w to (camera[3] * xMm + camera[4] * yMm + camera[5]) / w
    }

    private val truthPlane = run {
        val pts = listOf(-100.0 to 0.0, 100.0 to 0.0, 0.0 to -100.0, 0.0 to 100.0).map { project(it.first, it.second) }
        assertNotNull(TargetPlane.fromCross(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), 100.0))
    }
    private val truthCentre = project(0.0, 0.0)
    private val truthRing = assertNotNull(truthPlane.circleOutline(100.0))

    /** The black (radius 100 mm) on white paper, with 1 mm white lines at 12.5, 25, 50 and 75 mm. */
    private val photo: ByteArray = ByteArray(size * size).also { out ->
        for (y in 0 until size) for (x in 0 until size) {
            val (u, v) = truthPlane.toMm(x.toDouble(), y.toDouble())
            val r = hypot(u, v)
            val white = r >= 100.0 || doubleArrayOf(12.5, 25.0, 50.0, 75.0).any { abs(r - it) < 0.5 }
            out[y * size + x] = (if (white) 225 else 25).toByte()
        }
    }

    private fun start(dxMm: Double, dyMm: Double, scale: Float): Pair<CentreEstimate, FittedEllipse> {
        val pxPerMm = truthRing.semiMajor / 100.0
        val centre = CentreEstimate(
            (truthCentre.first + dxMm * pxPerMm).toFloat(),
            (truthCentre.second + dyMm * pxPerMm).toFloat(),
            CentreMethod.LINE_INTERSECTION,
        )
        return centre to truthRing.copy(semiMajor = truthRing.semiMajor * scale, semiMinor = truthRing.semiMinor * scale)
    }

    @Test
    fun findsTheTrueCentreAndRingFromAStartSeveralMillimetresOff() {
        for ((dx, dy, scale) in listOf(Triple(3.0, -2.0, 1.03f), Triple(-4.0, 1.0, 0.97f), Triple(0.0, 0.0, 1.0f))) {
            val (centre, ring) = start(dx, dy, scale)
            val refined = assertNotNull(refineGeometry(photo, size, size, centre, ring), "start $dx,$dy x$scale")
            val pxPerMm = truthRing.semiMajor / 100.0
            val centreErr = hypot(refined.centre.x - truthCentre.first, refined.centre.y - truthCentre.second) / pxPerMm
            assertTrue(centreErr < 0.15, "centre off by $centreErr mm from $dx,$dy x$scale")
            assertTrue(refined.inlierShare > 0.9, "inliers ${refined.inlierShare}")
            // Every ring, through the refined pair as the scoring builds it, lands on the truth.
            val plane = assertNotNull(TargetPlane.of(refined.centre, refined.ring))
            for (r in doubleArrayOf(12.5, 50.0, 100.0, 150.0)) {
                for (step in 0 until 16) {
                    val t = step * PI / 8
                    val (x, y) = project(r * cos(t), r * sin(t))
                    val (u, v) = plane.toMm(x, y)
                    assertTrue(abs(hypot(u, v) - r) < 0.3, "ring $r at $t: ${hypot(u, v)}")
                }
            }
        }
    }

    @Test
    fun theRefinedRingLiesOnTheBlacksEdge() {
        val (centre, ring) = start(2.0, 3.0, 1.04f)
        val refined = assertNotNull(refineGeometry(photo, size, size, centre, ring))
        val pxPerMm = truthRing.semiMajor / 100.0
        for ((got, want) in listOf(
            refined.ring.cx to truthRing.cx, refined.ring.cy to truthRing.cy,
            refined.ring.semiMajor to truthRing.semiMajor, refined.ring.semiMinor to truthRing.semiMinor,
        )) {
            assertTrue(abs(got - want) / pxPerMm < 0.15, "ring $got vs $want")
        }
    }

    @Test
    fun fitEllipseRecoversAnEllipseFromItsPoints() {
        val truth = FittedEllipse(cx = 640f, cy = 410f, semiMajor = 300f, semiMinor = 220f, rotationRad = 0.6f)
        val c = cos(truth.rotationRad.toDouble())
        val s = sin(truth.rotationRad.toDouble())
        val n = 200
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            val a = truth.semiMajor * cos(t)
            val b = truth.semiMinor * sin(t)
            xs[i] = truth.cx + a * c - b * s
            ys[i] = truth.cy + a * s + b * c
        }
        val fit = assertNotNull(fitEllipse(xs, ys))
        assertTrue(abs(fit.cx - truth.cx) < 0.01 && abs(fit.cy - truth.cy) < 0.01, "centre ${fit.cx},${fit.cy}")
        assertTrue(abs(fit.semiMajor - truth.semiMajor) < 0.01 && abs(fit.semiMinor - truth.semiMinor) < 0.01, "axes")
        assertTrue(abs(fit.rotationRad - truth.rotationRad) < 1e-4, "rotation ${fit.rotationRad}")
    }

    @Test
    fun aPhotoWithoutTheTargetKeepsTheOldGeometry() {
        val (centre, ring) = start(0.0, 0.0, 1f)
        assertNull(refineGeometry(ByteArray(size * size) { 120 }, size, size, centre, ring))
    }

    @Test
    fun handPlacedOrMissingGeometryIsNeverRefined() {
        val (centre, ring) = start(1.0, 1.0, 1f)
        assertNull(refineGeometry(photo, size, size, centre.copy(method = CentreMethod.MANUAL), ring))
        assertNull(refineGeometry(photo, size, size, centre.copy(method = CentreMethod.NONE), ring))
    }
}
