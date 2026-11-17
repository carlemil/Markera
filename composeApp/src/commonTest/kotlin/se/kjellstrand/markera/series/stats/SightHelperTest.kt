package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SightHelperTest {

    private fun offset(xMm: Double, yMm: Double) = Finding.OffsetGroup(
        hitCount = 40, mpiXMm = xMm, mpiYMm = yMm, distanceMm = kotlin.math.hypot(xMm, yMm),
        bearingDeg = 0.0, standardErrorMm = 3.0, daysChecked = 3, daysAgreeing = 3, stable = true, confidence = 0.5,
    )

    @Test
    fun mmToClicksDividesByTheClickValue() {
        assertEquals(4, clicksFor(24.0, 6.0))
        assertEquals(-4, clicksFor(-24.0, 6.0))
        assertEquals(0, clicksFor(0.0, 6.0))
        assertEquals(12, clicksFor(30.0, 2.5))
    }

    @Test
    fun clicksRoundHalfAwayFromZeroAlikeBothWays() {
        assertEquals(2, clicksFor(7.5, 5.0))
        assertEquals(-2, clicksFor(-7.5, 5.0))
        assertEquals(1, clicksFor(7.4, 5.0))
        assertEquals(-1, clicksFor(-7.4, 5.0))
        // Under half a click is no click.
        assertEquals(0, clicksFor(2.4, 5.0))
        assertEquals(0, clicksFor(-2.4, 5.0))
    }

    @Test
    fun aClickValueMustBePositive() {
        assertFailsWith<IllegalArgumentException> { clicksFor(10.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { clicksFor(10.0, -1.0) }
    }

    @Test
    fun aGroupLowAndRightIsCorrectedUpAndLeft() {
        // Target mm with y down: x 20 = right, y 30 = low.
        val c = sightCorrection(offset(20.0, 30.0), SightSetup(mmPerClick = 6.0))
        assertEquals(30, c.upMm)
        assertEquals(-20, c.rightMm)
        assertEquals(5, c.upClicks)
        assertEquals(-3, c.rightClicks)
        assertEquals(DEFAULT_DISTANCE_M, c.distanceM)
        assertTrue(c.hasClicks)
    }

    @Test
    fun aGroupHighAndLeftIsCorrectedDownAndRight() {
        val c = sightCorrection(offset(-12.4, -18.6), SightSetup(distanceM = 50.0, mmPerClick = 10.0))
        assertEquals(-19, c.upMm)
        assertEquals(12, c.rightMm)
        assertEquals(-2, c.upClicks)
        assertEquals(1, c.rightClicks)
        assertEquals(50.0, c.distanceM)
    }

    @Test
    fun clicksComeFromTheUnroundedMm() {
        // 7.4 mm rounds to 7 mm, but 7.4 / 2.9 is 2.55 clicks: 3, not 7 / 2.9 = 2.41 -> 2.
        val c = sightCorrection(offset(0.0, 7.4), SightSetup(mmPerClick = 2.9))
        assertEquals(7, c.upMm)
        assertEquals(3, c.upClicks)
    }

    @Test
    fun withoutAClickValueOnlyMm() {
        val none = sightCorrection(offset(20.0, 30.0), null)
        assertNull(none.upClicks)
        assertNull(none.rightClicks)
        assertFalse(none.hasClicks)
        assertEquals(30, none.upMm)
        val onlyAdjusted = sightCorrection(offset(20.0, 30.0), SightSetup(adjustedAtMs = 1L))
        assertFalse(onlyAdjusted.hasClicks)
        // A broken stored value is no click value, not a division by zero.
        assertFalse(sightCorrection(offset(20.0, 30.0), SightSetup(mmPerClick = 0.0)).hasClicks)
    }

    @Test
    fun typedNumbersTakeEitherDecimalMark() {
        assertEquals(2.5, parsePositiveDecimal("2,5"))
        assertEquals(2.5, parsePositiveDecimal(" 2.5 "))
        assertEquals(25.0, parsePositiveDecimal("25"))
        assertNull(parsePositiveDecimal("0"))
        assertNull(parsePositiveDecimal("-3"))
        assertNull(parsePositiveDecimal(""))
        assertNull(parsePositiveDecimal("abc"))
    }

    @Test
    fun setupsRoundTripAndBadTextReadsAsNone() {
        val setups = mapOf("9mm" to SightSetup(25.0, 6.0, 1_700_000_000_000L), ".22 lr" to SightSetup(mmPerClick = 2.5))
        assertEquals(setups, decodeSightSetups(encodeSightSetups(setups)))
        assertEquals(emptyMap(), decodeSightSetups(null))
        assertEquals(emptyMap(), decodeSightSetups(""))
        assertEquals(emptyMap(), decodeSightSetups("not json"))
        // A missing field takes its default.
        assertEquals(SightSetup(mmPerClick = 3.0), decodeSightSetups("{\"9mm\":{\"mmPerClick\":3.0}}")["9mm"])
    }

    @Test
    fun theCutOffKeepsOnlyLaterSeries() {
        val at = Instant.parse("2026-09-03T12:00:00Z")
        fun s(time: String) = PlottedSeries(
            se.kjellstrand.markera.series.SeriesDto(id = 1, timestamp = time, caliber = "9mm", holes = emptyList()),
            emptyList(), 0f, Instant.parse(time),
        )
        val list = listOf(s("2026-09-02T10:00:00Z"), s("2026-09-03T12:00:00Z"), s("2026-09-03T12:00:01Z"))
        assertEquals(list, list.sinceAdjustment(null))
        // Strictly after: a series stamped at the very moment was shot before the turn.
        assertEquals(listOf(list[2]), list.sinceAdjustment(at))
        assertNull(null.offsetCutOff())
        assertNull(SightSetup(mmPerClick = 6.0).offsetCutOff())
        assertEquals(at, SightSetup(adjustedAtMs = at.toEpochMilliseconds()).offsetCutOff())
    }
}
