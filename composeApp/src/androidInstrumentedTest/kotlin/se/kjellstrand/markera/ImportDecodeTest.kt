package se.kjellstrand.markera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.ui.markera.LoadedImage

/**
 * #9: Import's decode of a big camera photo. The picker's `loadUri` subsamples, scales the
 * short side to 3000 px and turns the image upright by its EXIF orientation. It is private
 * to ImagePicker.android.kt and only reached through the system picker, so it is called by
 * reflection here (no production seam). The JPEG is drawn in the test, not shipped as an asset.
 */
class ImportDecodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("UNCHECKED_CAST")
    private suspend fun loadUri(context: Context, uri: Uri): LoadedImage? {
        val method = Class.forName("se.kjellstrand.markera.ui.markera.ImagePicker_androidKt").declaredMethods
            .singleOrNull { it.name == "loadUri" && it.parameterTypes.size == 3 }
            ?: throw AssertionError("ImagePicker.android.kt no longer has loadUri(Context, Uri): update this test")
        method.isAccessible = true
        return suspendCoroutineUninterceptedOrReturn<Any?> { cont -> method.invoke(null, context, uri, cont) } as LoadedImage?
    }

    /** A 32 MP portrait as a phone stores it (4896 x 6528, top half red) and tagged "rotate 90" in EXIF. */
    private fun cameraJpeg(file: File) {
        val w = 4896
        val h = 6528
        // RGB_565: half the memory of ARGB for a picture of two flat colours.
        val stored = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(stored).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, w.toFloat(), h / 2f, Paint().apply { color = Color.RED })
        }
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        stored.recycle()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:05:01 08:30:00")
            saveAttributes()
        }
    }

    @Test
    fun aBigRotatedPhotoComesUprightAtTheCameraSize() {
        val file = File(context.cacheDir, "import-decode-${System.nanoTime()}.jpg")
        try {
            cameraJpeg(file)
            val loaded = runBlocking { loadUri(context, Uri.fromFile(file)) }
                ?: throw AssertionError("loadUri gave no image")
            val image = loaded.image
            try {
                // Short side exactly 3000, the aspect kept; turned upright, so now landscape.
                assertEquals(4000, image.width)
                assertEquals(3000, image.height)
                // Rotated 90° clockwise: the stored top (red) is now the right half.
                val left = image.getPixel(image.width / 10, image.height / 2)
                val right = image.getPixel(image.width * 9 / 10, image.height / 2)
                assertTrue("left should be blue: ${Integer.toHexString(left)}", Color.blue(left) > 150 && Color.red(left) < 100)
                assertTrue("right should be red: ${Integer.toHexString(right)}", Color.red(right) > 150 && Color.blue(right) < 100)
                assertNotNull("The EXIF date taken is read", loaded.takenAt)
            } finally {
                image.recycle()
            }
        } finally {
            file.delete()
        }
    }
}
