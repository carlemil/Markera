package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousScanTest {

    private fun scene(vararg changedAt: Int) = ByteArray(CHANGE_GRID * CHANGE_GRID) { 100 }.also {
        for (i in changedAt) it[i] = (100 + CHANGE_CELL_DELTA).toByte()
    }

    @Test
    fun changedCellsCountsOnlyCellsPastTheDelta() {
        val a = ByteArray(4) { 100 }
        val b = byteArrayOf(100, (100 + CHANGE_CELL_DELTA).toByte(), (100 + CHANGE_CELL_DELTA - 1).toByte(), 0)
        assertEquals(2, changedCells(a, b))
    }

    @Test
    fun aStaticSceneNeverFires() {
        val watch = ChangeWatch()
        val still = scene()
        repeat(5) { assertFalse(watch.offer(still)) }
    }

    @Test
    fun aChangeFiresOnlyOnceItHasSettled() {
        val watch = ChangeWatch()
        watch.offer(scene())
        // Still moving: the first changed sample must not trigger a scan.
        assertFalse(watch.offer(scene(0, 1, 2, 3)))
        // Settled on the new scene.
        assertTrue(watch.offer(scene(0, 1, 2, 3)))
    }

    @Test
    fun aChangeBelowTheCellCountIsIgnored() {
        val watch = ChangeWatch()
        watch.offer(scene())
        watch.offer(scene(0))
        assertFalse(watch.offer(scene(0)))
    }

    @Test
    fun resetReBaselinesSoTheSameSceneDoesNotFireAgain() {
        val watch = ChangeWatch()
        watch.offer(scene())
        watch.offer(scene(0, 1, 2, 3))
        assertTrue(watch.offer(scene(0, 1, 2, 3)))
        watch.reset()
        repeat(3) { assertFalse(watch.offer(scene(0, 1, 2, 3))) }
    }
}
