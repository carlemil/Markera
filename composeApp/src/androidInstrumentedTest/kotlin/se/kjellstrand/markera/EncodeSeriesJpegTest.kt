package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import se.kjellstrand.markera.series.IMAGE_MAX_DIM
import se.kjellstrand.markera.series.encodeSeriesJpeg

/**
 * #4: the Android [encodeSeriesJpeg] fails loudly on a frame it cannot encode, so the recorder
 * fails the save instead of keeping a series with no photo. Needs the real Bitmap, so on a device.
 */
class EncodeSeriesJpegTest {

    private fun frame(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }

    private fun failure(image: Bitmap): Throwable? = runCatching { runBlocking { encodeSeriesJpeg(image) } }.exceptionOrNull()

    @Test
    fun aNormalFrameEncodesAtItsOwnSize() = runBlocking<Unit> {
        val encoded = encodeSeriesJpeg(frame(300, 200))
        assertEquals(300, encoded.width)
        assertEquals(200, encoded.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, bounds)
        assertEquals(300 to 200, bounds.outWidth to bounds.outHeight)
    }

    @Test
    fun aRecycledFrameThrows() {
        val image = frame(300, 200).apply { recycle() }
        assertNotNull("A recycled frame must not encode to a series photo", failure(image))
    }

    /** Over [IMAGE_MAX_DIM] the frame is scaled first; a recycled one fails there instead. */
    @Test
    fun aRecycledOversizedFrameThrows() {
        val image = frame(IMAGE_MAX_DIM + 64, 64).apply { recycle() }
        assertNotNull("A recycled frame must not encode to a series photo", failure(image))
    }
}
