package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.scoreHits

class ManualGeometryTest {

    @Test
    fun draftStartsMidFrameAtGuideSizeWhenNothingWasFound() {
        val draft = initialGeometryDraft(MarkeraUiState(imageWidth = 1000, imageHeight = 1000))
        assertEquals(GeometryDraft(500f, 500f, 350f), draft)
    }

    @Test
    fun draftStartsOnWhatTheScanFound() {
        val state = MarkeraUiState(
            imageWidth = 1000,
            imageHeight = 1000,
            centre = CentreEstimate(420f, 480f, CentreMethod.LINE_INTERSECTION),
            ring = FittedEllipse(430f, 470f, 300f, 280f, 0f),
        )
        assertEquals(GeometryDraft(420f, 480f, 300f), initialGeometryDraft(state))
    }

    @Test
    fun anUnresolvedCentreIsIgnored() {
        val state = MarkeraUiState(
            imageWidth = 1000,
            imageHeight = 800,
            centre = CentreEstimate(0f, 0f, CentreMethod.NONE),
        )
        assertEquals(GeometryDraft(500f, 400f, 280f), initialGeometryDraft(state))
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

        val centre = CentreEstimate(500f, 500f, CentreMethod.MANUAL)
        val ring = FittedEllipse(500f, 500f, 200f, 200f, 0f)
        vm.onManualGeometry(centre, ring, scoreHits(vm.uiState.value.detections, centre, ring))

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
