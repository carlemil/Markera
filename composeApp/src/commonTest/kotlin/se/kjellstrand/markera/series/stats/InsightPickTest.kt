package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The finding picked on the Insights tab follows its kind, not its place in the list (issue 39). */
class InsightPickTest {

    private val offset = Finding.OffsetGroup(
        hitCount = 20, mpiXMm = 30.0, mpiYMm = 0.0, distanceMm = 30.0, bearingDeg = 90.0,
        standardErrorMm = 2.0, daysChecked = 3, daysAgreeing = 3, stable = true, confidence = 0.9,
    )
    private val stretched = Finding.StretchedGroup(
        hitCount = 20, elongation = 2.0, majorSdMm = 20.0, minorSdMm = 10.0, axisDeg = 90.0,
        orientation = Finding.Orientation.VERTICAL, confidence = 0.6,
    )
    private val fliers = Finding.Fliers(
        seriesCount = 8, seriesWithFliers = 3, flierCount = 3, hitCount = 40,
        coreRadialSdMm = 10.0, flierMeanDistanceMm = 50.0, confidence = 0.4,
    )

    @Test
    fun tappingAFindingPicksItsKindAndTappingItAgainLetsGo() {
        val list = listOf(offset, stretched, fliers)
        val picked = list.toggledPick(null, 1)
        assertEquals(1, list.pickedIndex(picked))
        assertEquals(InsightMark.ELLIPSE, list.pickedMark(picked))
        assertNull(list.toggledPick(picked, 1))
        // Tapping another one moves the pick there.
        assertEquals(2, list.pickedIndex(list.toggledPick(picked, 2)))
    }

    @Test
    fun thePickFollowsItsFindingWhenAnEarlierOneGoes() {
        val picked = listOf(offset, stretched, fliers).toggledPick(null, 1)
        // "I adjusted the sights": the offset finding is gone, the stretch moves up to index 0.
        val after = listOf(stretched, fliers)
        assertEquals(0, after.pickedIndex(picked))
        assertEquals(InsightMark.ELLIPSE, after.pickedMark(picked))
    }

    @Test
    fun aPickedFindingThatGoesAwayHighlightsNothing() {
        val picked = listOf(offset, stretched, fliers).toggledPick(null, 0)
        val after = listOf(stretched, fliers)
        assertNull(after.pickedIndex(picked))
        assertEquals(InsightMark.NONE, after.pickedMark(picked))
    }

    @Test
    fun aTapOutsideTheListPicksNothing() {
        assertNull(listOf(offset).toggledPick(null, 3))
        assertEquals(InsightMark.NONE, emptyList<Finding>().pickedMark(null))
    }
}
