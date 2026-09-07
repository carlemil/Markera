package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.HitScore

class ManualHitTest {

    // 200x100 image in a 400x400 viewport: scale 2, letterboxed 100 px top/bottom.
    private fun map(x: Float, y: Float) = viewportToImage(x, y, 400f, 400f, 200, 100)

    @Test
    fun aTapMapsBackThroughTheFitCentreLetterbox() {
        assertEquals(0f to 0f, map(0f, 100f))
        assertEquals(100f to 50f, map(200f, 200f))
        assertEquals(200f to 100f, map(400f, 300f))
    }

    @Test
    fun tapsOnTheLetterboxBarsAreIgnored() {
        assertNull(map(200f, 99f))
        assertNull(map(200f, 301f))
        assertNull(viewportToImage(10f, 10f, 400f, 400f, 0, 0))
    }

    @Test
    fun aManualBoxTakesTheMedianDetectedSize() {
        val existing = listOf(box(0f, 0f, 10f), box(500f, 500f, 20f), box(900f, 900f, 30f))
        val d = assertNotNull(manualDetection(100f, 200f, existing, minGapPx = 24f))
        assertEquals(Detection(90f, 190f, 110f, 210f, 1f), d)
    }

    @Test
    fun withNoDetectionsTheBoxFallsBackToAFixedSize() {
        val d = assertNotNull(manualDetection(50f, 50f, emptyList(), minGapPx = 24f))
        assertEquals(MANUAL_HOLE_FALLBACK_PX, d.right - d.left)
        assertEquals(MANUAL_HOLE_FALLBACK_PX, d.bottom - d.top)
    }

    @Test
    fun aTapNextToAnExistingHoleIsAMisTapAndAddsNothing() {
        val existing = listOf(box(100f, 100f, 10f))
        assertNull(manualDetection(110f, 100f, existing, minGapPx = 24f))
        assertNotNull(manualDetection(130f, 100f, existing, minGapPx = 24f))
    }

    @Test
    fun addManualHitAppendsAndFillsTheNextPickerSlot() {
        val vm = MarkeraViewModelImpl()
        vm.onHolesDetected(listOf(box(0f, 0f, 10f)), listOf(hit(ring = 9)))
        assertEquals(listOf(9, 0, 0, 0, 0), vm.uiState.value.topScores)

        vm.addManualHit(box(50f, 50f, 10f), hit(ring = 10, inner = true, manual = true))

        assertEquals(2, vm.uiState.value.scores.size)
        assertEquals(2, vm.uiState.value.detections.size)
        assertEquals(listOf(9, SCORE_PICKER_INNER_TEN, 0, 0, 0), vm.uiState.value.topScores)
    }

    @Test
    fun aSixthManualHitScoresButTouchesNoPickerSlot() {
        val vm = MarkeraViewModelImpl()
        val five = List(5) { box(it * 100f, 0f, 10f) }
        vm.onHolesDetected(five, List(5) { hit(ring = 8) })
        repeat(2) { vm.addManualHit(box(900f, 900f, 10f), hit(ring = 5, manual = true)) }

        assertEquals(7, vm.uiState.value.scores.size)
        assertEquals(List(5) { 8 }, vm.uiState.value.topScores)
    }

    @Test
    fun aTapFindsAManualHoleButNeverADetectedOne() {
        val holes = listOf(box(100f, 100f, 10f), box(300f, 100f, 10f))
        val scores = listOf(hit(ring = 9), hit(ring = 8, manual = true))
        assertEquals(1, manualHitAt(310f, 100f, holes, scores, minGapPx = 24f))
        assertEquals(-1, manualHitAt(110f, 100f, holes, scores, minGapPx = 24f))
        assertEquals(-1, manualHitAt(300f, 400f, holes, scores, minGapPx = 24f))
    }

    @Test
    fun removingAHoleShiftsLaterPicksLeftAndKeepsUserEdits() {
        val vm = MarkeraViewModelImpl()
        vm.onHolesDetected(List(3) { box(it * 100f, 0f, 10f) }, List(3) { hit(ring = 7 + it) })
        vm.setTopScoreAt(2, SCORE_PICKER_INNER_TEN) // user corrected the third slot
        assertEquals(listOf(7, 8, SCORE_PICKER_INNER_TEN, 0, 0), vm.uiState.value.topScores)

        vm.removeManualHit(1)

        assertEquals(2, vm.uiState.value.scores.size)
        assertEquals(2, vm.uiState.value.detections.size)
        assertEquals(listOf(7, SCORE_PICKER_INNER_TEN, 0, 0, 0), vm.uiState.value.topScores)
    }

    @Test
    fun removingAHolePullsTheNextOneIntoTheLastPickerSlot() {
        val vm = MarkeraViewModelImpl()
        val six = List(6) { box(it * 100f, 0f, 10f) }
        vm.onHolesDetected(six, List(5) { hit(ring = 5 + it) } + hit(ring = 10, inner = true))
        assertEquals(listOf(5, 6, 7, 8, 9), vm.uiState.value.topScores)

        vm.removeManualHit(2)

        assertEquals(5, vm.uiState.value.scores.size)
        assertEquals(listOf(5, 6, 8, 9, SCORE_PICKER_INNER_TEN), vm.uiState.value.topScores)
    }

    @Test
    fun removingAHoleBeyondThePickersLeavesThemAlone() {
        val vm = MarkeraViewModelImpl()
        val seven = List(7) { box(it * 100f, 0f, 10f) }
        vm.onHolesDetected(seven, List(7) { hit(ring = 6) })

        vm.removeManualHit(5)

        assertEquals(6, vm.uiState.value.scores.size)
        assertEquals(6, vm.uiState.value.detections.size)
        assertEquals(List(5) { 6 }, vm.uiState.value.topScores)
    }

    private fun box(cx: Float, cy: Float, side: Float) =
        Detection(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f, 1f)

    private fun hit(ring: Int, inner: Boolean = false, manual: Boolean = false) =
        HitScore(0f, 0f, 0f, 10.0, ring, inner, manual)
}
