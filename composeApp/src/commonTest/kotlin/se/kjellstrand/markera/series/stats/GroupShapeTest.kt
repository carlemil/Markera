package se.kjellstrand.markera.series.stats

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupShapeTest {
    private val k = 0.7071067811865476

    private fun hit(x: Double, y: Double) = PlottedHit(x, y, 0, false)

    /** Eight hits on a circle of [r] around ([cx], [cy]). */
    private fun round(r: Double, cx: Double = 0.0, cy: Double = 0.0) =
        listOf(
            1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0, 0.0 to -1.0,
            k to k, -k to k, -k to -k, k to -k,
        ).map { (a, b) -> hit(cx + a * r, cy + b * r) }

    /** Hits spread along the unit direction (ux, uy) (y down) with a little across. */
    private fun stretched(ux: Double, uy: Double): List<PlottedHit> {
        val along = listOf(-20.0, -10.0, 0.0, 10.0, 20.0, -20.0, -10.0, 0.0, 10.0, 20.0)
        val across = listOf(2.0, -2.0, 2.0, -2.0, 2.0, -2.0, 2.0, -2.0, 2.0, -2.0)
        return along.zip(across).map { (a, c) -> hit(a * ux - c * uy, a * uy + c * ux) }
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected but was $actual")

    @Test
    fun centredRoundGroup() {
        val s = assertNotNull(round(10.0).groupShape())
        near(0.0, s.mpiDistanceMm)
        near(1.0, s.elongation, 1e-6)
        assertTrue(s.fliers.isEmpty())
        assertEquals(8, s.coreCount)
        assertNotNull(s.coreRadialSdMm)
    }

    @Test
    fun shiftedLow() {
        val s = assertNotNull(round(5.0, cy = 20.0).groupShape())
        near(180.0, s.mpiBearingDeg)
        near(20.0, s.mpiDistanceMm)
        assertTrue(s.mpiDistanceMm > 2 * s.offsetSeMm)
    }

    @Test
    fun shiftedRight() {
        val s = assertNotNull(round(5.0, cx = 20.0).groupShape())
        near(90.0, s.mpiBearingDeg)
        assertTrue(s.mpiDistanceMm > 2 * s.offsetSeMm)
    }

    @Test
    fun shiftedUpAndLeft() {
        near(0.0, assertNotNull(round(5.0, cy = -20.0).groupShape()).mpiBearingDeg)
        near(270.0, assertNotNull(round(5.0, cx = -20.0).groupShape()).mpiBearingDeg)
    }

    @Test
    fun smallOffsetInNoiseIsNotReal() {
        val s = assertNotNull(round(10.0, cx = 0.5).groupShape())
        assertFalse(s.mpiDistanceMm > 2 * s.offsetSeMm)
    }

    @Test
    fun horizontalStretch() {
        val s = assertNotNull(stretched(1.0, 0.0).groupShape())
        near(0.0, s.majorAxisDeg)
        assertTrue(s.elongation > 4)
    }

    @Test
    fun verticalStretch() {
        val s = assertNotNull(stretched(0.0, 1.0).groupShape())
        near(90.0, s.majorAxisDeg)
        assertTrue(s.elongation > 4)
    }

    @Test
    fun diagonalStretchUpperRight() {
        // y down: upper-right is (+x, -y).
        val s = assertNotNull(stretched(k, -k).groupShape())
        near(45.0, s.majorAxisDeg, 1e-4)
        assertTrue(s.elongation > 4)
    }

    @Test
    fun diagonalStretchUpperLeft() {
        val s = assertNotNull(stretched(k, k).groupShape())
        near(135.0, s.majorAxisDeg, 1e-4)
        assertTrue(s.elongation > 4)
    }

    @Test
    fun tightGroupWithOneFlier() {
        val tight = listOf(
            hit(1.0, 0.0), hit(-1.0, 0.0), hit(0.0, 1.0), hit(0.0, -1.0), hit(0.5, 0.5), hit(-0.5, -0.5),
        )
        val flier = hit(30.0, 30.0)
        val s = assertNotNull((tight + flier).groupShape())
        assertEquals(listOf(flier), s.fliers)
        assertEquals(tight.size, s.coreCount)
        val whole = hypot(s.majorSdMm, s.minorSdMm)
        assertTrue(assertNotNull(s.coreRadialSdMm) < whole / 5)
        assertNotNull(s.coreMeanRadiusMm)
    }

    @Test
    fun degenerateInputsAreNull() {
        assertNull(emptyList<PlottedHit>().groupShape())
        assertNull(listOf(hit(1.0, 1.0)).groupShape())
        assertNull(listOf(hit(1.0, 1.0), hit(2.0, 3.0)).groupShape())
        assertNull(listOf(hit(4.0, 4.0), hit(4.0, 4.0), hit(4.0, 4.0)).groupShape())
        assertNull(listOf(hit(0.0, 0.0), hit(1.0, 1.0), hit(2.0, 2.0)).groupShape())
    }

    @Test
    fun threeHitGroupHasNoNaN() {
        val s = assertNotNull(listOf(hit(0.0, 0.0), hit(4.0, 1.0), hit(1.0, 5.0)).groupShape())
        val values = listOf(
            s.mpiXMm, s.mpiYMm, s.mpiDistanceMm, s.mpiBearingDeg, s.seXMm, s.seYMm, s.offsetSeMm,
            s.covXX, s.covXY, s.covYY, s.majorSdMm, s.minorSdMm, s.majorAxisDeg, s.elongation,
        ) + listOfNotNull(s.coreRadialSdMm, s.coreMeanRadiusMm)
        values.forEach { assertFalse(it.isNaN() || it.isInfinite(), "bad value $it") }
    }
}
