package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportDecodeSizeTest {
    /** What the import keeps: the power-of-two subsample, then the exact scale. */
    private fun finalSize(width: Int, height: Int): Pair<Int, Int> {
        val sample = importSampleSize(minOf(width, height))
        return importScaledSize(width / sample, height / sample)
    }

    @Test
    fun a32MegapixelPhotoEndsAt3000OnTheShortSide() {
        // 4896 / 2 < 3000, so the subsample alone stays at 1 and keeps all 4896 px.
        assertEquals(3000 to 4000, finalSize(4896, 6528))
    }

    @Test
    fun aShortSideJustUnderDoubleTheTargetEndsAt3000() {
        assertEquals(3000 to 4001, finalSize(5999, 8000))
        assertEquals(4001 to 3000, finalSize(8000, 5999))
    }

    @Test
    fun a6000By8000PhotoEndsAt3000By4000() {
        assertEquals(3000 to 4000, finalSize(6000, 8000))
    }

    @Test
    fun aVeryLargePhotoEndsAt3000OnTheShortSide() {
        assertEquals(3000 to 4000, finalSize(12000, 16000))
    }

    @Test
    fun aPhotoAtOrBelowTheTargetIsLeftAlone() {
        assertEquals(3000 to 4000, finalSize(3000, 4000))
        assertEquals(2000 to 2500, finalSize(2000, 2500))
        assertEquals(1, importSampleSize(2000))
    }

    @Test
    fun theSubsampleNeverTakesTheShortSideBelowTheTarget() {
        for (shorter in listOf(3000, 3001, 4500, 5999, 6000, 6001, 11999, 12000, 24000)) {
            val sample = importSampleSize(shorter)
            assertTrue(shorter / sample >= IMPORT_SQUARE_SIDE, "shorter=$shorter sample=$sample")
            assertTrue(shorter / (sample * 2) < IMPORT_SQUARE_SIDE, "shorter=$shorter sample=$sample")
        }
    }

    @Test
    fun theScaledShortSideIsNeverBelowTheTarget() {
        for (shorter in 3000..6000 step 7) {
            val (w, h) = importScaledSize(shorter, shorter * 4 / 3)
            assertEquals(IMPORT_SQUARE_SIDE, minOf(w, h), "shorter=$shorter")
        }
    }
}
