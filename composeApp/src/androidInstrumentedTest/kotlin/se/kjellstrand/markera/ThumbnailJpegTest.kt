package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.kjellstrand.markera.series.thumbnailJpeg

/** [thumbnailJpeg] needs the real BitmapFactory, so it runs on a device. */
class ThumbnailJpegTest {

    private fun jpeg(w: Int, h: Int): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun aFullFrameShrinksToExactlyTheMaxOnItsLongerSide() {
        assertEquals(384 to 384, size(thumbnailJpeg(jpeg(3072, 3072), 384)!!))
        // Not a power-of-two ratio, and landscape.
        assertEquals(384 to 256, size(thumbnailJpeg(jpeg(2994, 1996), 384)!!))
    }

    @Test
    fun aSmallImageIsNotUpscaled() {
        assertEquals(200 to 100, size(thumbnailJpeg(jpeg(200, 100), 384)!!))
    }

    @Test
    fun garbageGivesNull() {
        assertNull(thumbnailJpeg(byteArrayOf(1, 2, 3), 384))
    }
}
