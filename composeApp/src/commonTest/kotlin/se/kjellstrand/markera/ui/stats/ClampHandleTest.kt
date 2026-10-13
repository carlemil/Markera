package se.kjellstrand.markera.ui.stats

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClampHandleTest {
    @Test
    fun unzoomedHandleInsideStaysPut() {
        val p = clampHandle(Offset(300f, 100f), 1f, Offset.Zero, 400f, 400f, 14f)
        assertEquals(Offset(300f, 100f), p)
    }

    @Test
    fun zoomedOffScreenHandleIsPulledIntoView() {
        // zoom 4, panned so the view shows layer x,y 0..100; handle at 350,50 is off to the right.
        val p = clampHandle(Offset(350f, 50f), 4f, Offset.Zero, 400f, 400f, 20f)
        assertEquals(Offset(95f, 50f), p) // screen x 380 = 400 - margin
    }

    @Test
    fun grabbingAClampedHandleKeepsTheRadius() {
        // The clamped handle sits 120 px from the centre while the ring is 150 mm out:
        // a grab, or a tap, must not snap the ring to the finger.
        assertEquals(150.0, draggedLimitMm(150f, 120f, 120f, 3.2f))
    }

    @Test
    fun draggingAClampedHandleMovesTheRingByTheFingersTravel() {
        // 32 px outwards at 3.2 px/mm is 10 mm.
        assertEquals(160.0, draggedLimitMm(150f, 120f, 152f, 3.2f))
    }

    @Test
    fun draggingPastThePlotEdgeIsTheWholeTarget() {
        assertNull(draggedLimitMm(PLOT_RADIUS_MM, 120f, 140f, 3.2f))
    }

    @Test
    fun draggingIntoTheCentreStopsAtOneMm() {
        assertEquals(1.0, draggedLimitMm(10f, 120f, 0f, 3.2f))
    }
}
