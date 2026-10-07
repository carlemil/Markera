package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import se.kjellstrand.markera.series.Caliber

class ImportQueueTest {

    @Test
    fun autoImportSavesOnlyAScoredSaveableScan() {
        assertEquals(ImportDecision.Save, importDecision(true, true, Caliber.LR22, true))
        assertEquals(ImportDecision.Review, importDecision(true, false, Caliber.LR22, true))
        assertEquals(ImportDecision.Review, importDecision(true, true, Caliber.NONE, true))
        assertEquals(ImportDecision.Review, importDecision(true, true, Caliber.LR22, false))
        assertEquals(ImportDecision.Review, importDecision(false, true, Caliber.LR22, true))
    }

    @Test
    fun theFailedImagesOfAnAutoPassAreReviewedAfterIt() {
        val queue = ImportQueue(listOf("a", "b", "c"), auto = true)
        queue.next(ImportDecision.Save)
        queue.next(ImportDecision.Review)
        assertEquals("c", queue.current)
        queue.next(ImportDecision.Save)

        assertEquals(listOf("b"), queue.items)
        assertEquals("b", queue.current)
        assertFalse(queue.auto)
        queue.next(ImportDecision.Skip)

        assertNull(queue.current)
        assertEquals(2, queue.saved)
        assertEquals(1, queue.skipped)
    }

    @Test
    fun aSingleFailedImageStartsANewPass() {
        // Its review pass has the same list and index as the auto pass: only `pass` tells them apart.
        val queue = ImportQueue(listOf("a"), auto = true)
        queue.next(ImportDecision.Review)

        assertEquals(listOf("a"), queue.items)
        assertEquals(0, queue.index)
        assertEquals(1, queue.pass)
    }

    @Test
    fun aManualQueueEndsAfterItsLastImage() {
        val queue = ImportQueue(listOf("a", "b"), auto = false)
        queue.next(ImportDecision.Save)
        queue.next(ImportDecision.Skip)

        assertNull(queue.current)
        assertEquals(1, queue.saved)
        assertEquals(1, queue.skipped)
        queue.next(ImportDecision.Save) // Past the end: nothing changes.
        assertEquals(1, queue.saved)
    }
}
