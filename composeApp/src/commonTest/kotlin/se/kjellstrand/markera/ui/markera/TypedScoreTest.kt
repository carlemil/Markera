package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.toHoleDto
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.HitScore

class TypedScoreTest {

    private val boxes = listOf(box(10f), box(20f))
    private val nine = HitScore(10f, 0f, 0f, 30.0, 9, false)
    private val eight = HitScore(20f, 0f, 0f, 60.0, 8, false)

    private fun scanned() = MarkeraViewModelImpl().apply { onHolesDetected(boxes, listOf(nine, eight)) }

    @Test
    fun typingXOntoAHoleKeepsItInPlaceAndTheDetectorScoreAsDetected() {
        val vm = scanned()

        vm.setScore(1, vm.uiState.value.scores[1].withTypedScore(SCORE_PICKER_INNER_TEN))
        val state = vm.uiState.value

        // The 8 became an X, so it sorts first — with its own box, unmoved.
        assertEquals(listOf(SCORE_PICKER_INNER_TEN, 9, 0, 0, 0), state.topScores)
        assertEquals(listOf(boxes[1], boxes[0]), state.detections)
        val typed = state.scores[0]
        assertTrue(typed.typed)
        assertEquals(eight, typed.original)
        assertEquals(60.0, typed.distanceMm)
        assertEquals(HoleDto(20.0, 0.0, 10, true, 60.0, 8, false, 20.0, 0.0), typed.toHoleDto())
    }

    @Test
    fun aTypedManualHoleStaysManualWithNoDetectedValues() {
        val placed = HitScore(5f, 5f, 0f, 40.0, 8, false, manual = true)
        assertEquals(HoleDto(5.0, 5.0, 9, false, 40.0, null, null), placed.withTypedScore(9).toHoleDto())
    }

    @Test
    fun aTypedScoreSurvivesRemovingAnotherHole() {
        val vm = scanned()
        vm.setScore(0, vm.uiState.value.scores[0].withTypedScore(6))
        // The 9 typed down to 6 now sorts after the 8.
        assertEquals(listOf(8, 6, 0, 0, 0), vm.uiState.value.topScores)

        vm.removeHit(0)

        assertEquals(listOf(6, 0, 0, 0, 0), vm.uiState.value.topScores)
        assertEquals(listOf(boxes[0]), vm.uiState.value.detections)
        assertTrue(vm.uiState.value.scores[0].typed)
    }

    @Test
    fun aTypedScoreSurvivesADrag() {
        val typed = eight.withTypedScore(SCORE_PICKER_INNER_TEN)
        val fresh = HitScore(25f, 0f, 0f, 45.0, 9, false)

        val moved = fresh.movedFrom(typed)

        assertTrue(moved.typed)
        assertEquals(10, moved.ring)
        assertTrue(moved.isInnerTen)
        assertEquals(45.0, moved.distanceMm)
        assertEquals(25f, moved.centerXpx)
        assertEquals(eight, moved.original)
    }

    @Test
    fun anUntypedHoleTakesTheFreshScoreWhenDragged() {
        val moved = HitScore(25f, 0f, 0f, 45.0, 9, false).movedFrom(eight)

        assertFalse(moved.typed)
        assertEquals(9, moved.ring)
        assertEquals(eight, moved.original)
    }

    @Test
    fun settingAScoreOnNoHoleChangesNothing() {
        val vm = scanned()
        val before = vm.uiState.value
        vm.setScore(3, nine.withTypedScore(1))
        assertEquals(before, vm.uiState.value)
        assertNull(before.scores.getOrNull(3))
    }

    private fun box(cx: Float) = Detection(cx - 5f, -5f, cx + 5f, 5f, 1f)
}
