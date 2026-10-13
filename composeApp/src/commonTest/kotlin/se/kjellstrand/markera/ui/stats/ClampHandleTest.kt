package se.kjellstrand.markera.ui.stats

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
