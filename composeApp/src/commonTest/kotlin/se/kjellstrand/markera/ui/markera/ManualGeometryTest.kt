package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.restoreHole
import se.kjellstrand.markera.series.scoreHoleAt
import se.kjellstrand.markera.series.seriesJson
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.scoreHits

class ManualGeometryTest {

    private fun near(a: Float, b: Float, tol: Float = 0.5f) = assertTrue(kotlin.math.abs(a - b) <= tol, "$a vs $b")

    @Test
    fun draftStartsMidFrameAtGuideSizeWhenNothingWasFound() {
        val draft = initialGeometryDraft(MarkeraUiState(imageWidth = 1000, imageHeight = 1000))
        assertEquals(GeometryDraft.circle(500f, 500f, 350f, centreFixed = false), draft)
        val ring = assertNotNull(draft.ring)
        near(ring.semiMajor, 350f)
        near(ring.semiMinor, 350f)
    }

    @Test
    fun draftStartsOnWhatTheScanFound() {
        val centre = CentreEstimate(500f, 500f, CentreMethod.LINE_INTERSECTION)
        val found = FittedEllipse(500f, 500f, 300f, 200f, 0f)
        val draft = initialGeometryDraft(MarkeraUiState(imageWidth = 1000, imageHeight = 1000, centre = centre, ring = found))
        // The digit centre is kept, and the four points rebuild the same ellipse.
        assertTrue(draft.centreFixed)
        assertEquals(500f, draft.centreX)
        val ring = assertNotNull(draft.ring)
        near(ring.semiMajor, 300f)
        near(ring.semiMinor, 200f)
    }

    @Test
    fun anUnresolvedCentreIsIgnored() {
        val state = MarkeraUiState(
            imageWidth = 1000,
            imageHeight = 800,
            centre = CentreEstimate(0f, 0f, CentreMethod.NONE),
        )
        assertEquals(GeometryDraft.circle(500f, 400f, 280f, centreFixed = false), initialGeometryDraft(state))
    }

    @Test
    fun movingAPointReshapesTheRingIntoAnEllipse() {
        // Squash the circle vertically: top and bottom 100 px closer in.
        val draft = GeometryDraft.circle(500f, 500f, 300f, centreFixed = false)
            .withPoint(2, 500f, 300f)
            .withPoint(3, 500f, 700f)
        val ring = assertNotNull(draft.ring)
        near(ring.semiMajor, 300f)
        near(ring.semiMinor, 200f)
    }

    @Test
    fun aFreeCentreFollowsThePointsAndADraggedOneStays() {
        // Top and bottom 50 px right: the vertical diameter is now x = 550, and the
        // centre (a line map keeps lines) is where it crosses the horizontal one.
        val free = GeometryDraft.circle(500f, 500f, 300f, centreFixed = false)
            .withPoint(2, 550f, 200f)
            .withPoint(3, 550f, 800f)
        near(free.centreX, 550f)
        near(free.centreY, 500f)
        val dragged = free.withCentre(480f, 510f).withPoint(1, 900f, 500f)
        assertEquals(480f, dragged.centreX)
        assertEquals(510f, dragged.centreY)
    }

    @Test
    fun threePointsInALineMakeNoRing() {
        val draft = GeometryDraft.circle(500f, 500f, 300f, centreFixed = false).withPoint(2, 500f, 500f)
        assertNull(draft.ring)
    }

    @Test
    fun aCentreDraggedUpToTheRingCannotBeFinished() {
        val draft = GeometryDraft.circle(500f, 500f, 200f, centreFixed = false)
        assertTrue(draft.usable)
        assertTrue(draft.withCentre(510f, 490f).usable)
        // Next to the rim the vanishing line (x ~ 710) runs through the target face.
        val dragged = draft.withCentre(690f, 500f)
        assertNotNull(dragged.ring)
        assertFalse(dragged.usable)
        assertFalse(GeometryDraft.circle(500f, 500f, 300f, centreFixed = false).withPoint(2, 500f, 500f).usable)
    }

    @Test
    fun aHolePastTheVanishingLineIsAMissWithNoDistanceAndStillSaves() {
        // A stored series whose centre sits next to the ring: a hole beyond the line used to map
        // mirrored onto the face and score a 5.
        val geometry = GeometryDto(690.0, 500.0, 500.0, 500.0, 200.0, 200.0, 0.0)
        val hole = geometry.scoreHoleAt(790.0, 500.0, Caliber.NONE)
        assertEquals(0, hole.ring)
        assertFalse(hole.innerTen)
        assertNull(hole.distanceMm)
        assertTrue(seriesJson.encodeToString(HoleDto.serializer(), hole).isNotEmpty())
        val restored = listOf(hole.copy(detectedRing = 0, detectedInnerTen = false, detectedX = 790.0, detectedY = 500.0))
            .restoreHole(0, geometry)
        assertNull(restored.single().distanceMm)
    }

    @Test
    fun manualGeometryScoresTheHolesTheScanFound() {
        val vm = MarkeraViewModelImpl()
        // A scan with no ring: two holes found, nothing scored.
        val near = Detection(495f, 495f, 505f, 505f, 0.9f)
        val far = Detection(745f, 495f, 755f, 505f, 0.8f)
        vm.onGeometryReady(emptyList(), CentreEstimate(0f, 0f, CentreMethod.NONE), null, 1000, 1000)
        vm.onHolesDetected(listOf(far, near), emptyList())
        assertEquals(0, vm.uiState.value.scores.size)

        val draft = GeometryDraft.circle(500f, 500f, 200f, centreFixed = false)
        val ring = assertNotNull(draft.ring)
        vm.onManualGeometry(draft.centre, ring, scoreHits(vm.uiState.value.detections, draft.centre, ring))

        val state = vm.uiState.value
        assertEquals(ring, state.ring)
        assertEquals(2, state.scores.size)
        // Best first, the detections re-sorted with them: the centre hole is an inner X.
        assertEquals(near, state.detections.first())
        assertEquals(SCORE_PICKER_INNER_TEN, state.topScores[0])
        // 250 px on a 200 px = 100 mm ring is 125 mm out; the 10 px hole's inner edge at 122.5 mm is a 6.
        assertEquals(6, state.topScores[1])
    }
}
