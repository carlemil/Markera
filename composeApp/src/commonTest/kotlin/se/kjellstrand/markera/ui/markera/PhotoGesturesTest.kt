package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoGesturesTest {

    private fun end(onHole: Boolean, travel: Float = 0f, pinched: Boolean = false, heldMs: Long = 100) =
        gestureEnd(onHole, travel, slop = 8f, pinched = pinched, heldMs = heldMs, longPressMs = 400)

    @Test
    fun `a short tap off a hole adds one`() = assertEquals(GestureEnd.ADD, end(onHole = false))

    @Test
    fun `a short tap on a hole does nothing`() = assertEquals(GestureEnd.NONE, end(onHole = true))

    @Test
    fun `a long press on a hole removes it`() =
        assertEquals(GestureEnd.REMOVE, end(onHole = true, heldMs = 400))

    @Test
    fun `a drag past the slop on a hole ends a move`() =
        assertEquals(GestureEnd.MOVE_END, end(onHole = true, travel = 9f))

    @Test
    fun `a drag off a hole does nothing`() = assertEquals(GestureEnd.NONE, end(onHole = false, travel = 9f))

    @Test
    fun `a pinch does nothing even on a hole`() {
        assertEquals(GestureEnd.NONE, end(onHole = true, pinched = true, heldMs = 1000))
        assertEquals(GestureEnd.NONE, end(onHole = false, pinched = true))
    }

    @Test
    fun `travel exactly at the slop is still a tap`() {
        assertEquals(GestureEnd.ADD, end(onHole = false, travel = 8f))
        assertEquals(GestureEnd.REMOVE, end(onHole = true, travel = 8f, heldMs = 500))
    }
}
