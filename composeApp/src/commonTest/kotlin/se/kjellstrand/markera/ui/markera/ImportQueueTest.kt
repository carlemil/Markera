package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import se.kjellstrand.markera.series.Caliber

class ImportQueueTest {

    @Test
    fun autoImportSavesOnlyAScoredSaveableScan() {
        assertEquals(ImportDecision.Save, importDecision(true, true, Caliber.LR22))
        assertEquals(ImportDecision.Review, importDecision(true, false, Caliber.LR22))
        assertEquals(ImportDecision.Review, importDecision(true, true, Caliber.NONE))
        assertEquals(ImportDecision.Review, importDecision(false, true, Caliber.LR22))
    }

    @Test
    fun autoPassSavesScoredImagesWithoutAnAccount() {
        val queue = ImportQueue(listOf("first", "second"), auto = true)
        while (queue.current != null) {
            queue.next(importDecision(queue.auto, hasScores = true, caliber = Caliber.LR22))
        }
        assertEquals(2, queue.saved)
        assertEquals(0, queue.skipped)
        assertNull(queue.current)
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
    fun saveCountsOnlyWhatWasKeptAndStaysOnARetainedFailure() {
        assertEquals(ImportDecision.Save, afterSave(kept = true, retained = false))
        // Storage failed, the scan is retained: stay on the frame for Save again.
        assertNull(afterSave(kept = false, retained = true))
        // Nothing to save, or waiting for the caliber dialog: leave, uncounted as saved.
        assertEquals(ImportDecision.Skip, afterSave(kept = false, retained = false))
    }

    /** #34: a manual Save whose local save failed is not counted; the image stays for a retry or Skip. */
    @Test
    fun aFailedManualSaveIsNotCountedAndKeepsTheImage() {
        val queue = ImportQueue(listOf("a", "b"), auto = false)
        afterSave(kept = false, retained = true)?.let(queue::next)
        assertEquals("a", queue.current)
        assertEquals(0, queue.saved)

        // Save again succeeds.
        afterSave(kept = true, retained = false)?.let(queue::next)
        assertEquals("b", queue.current)
        // It fails again and the user gives up: Skip.
        afterSave(kept = false, retained = true)?.let(queue::next)
        assertEquals("b", queue.current)
        queue.next(ImportDecision.Skip)

        assertNull(queue.current)
        assertEquals(1, queue.saved)
        assertEquals(1, queue.skipped)
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
