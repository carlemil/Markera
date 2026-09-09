package se.kjellstrand.markera.series

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.vision.PlatformImage

private const val IMAGE_MAX_DIM = 3072

actual suspend fun encodeSeriesJpeg(image: PlatformImage): EncodedImage =
    withContext(Dispatchers.Default) {
        val longest = maxOf(image.width, image.height)
        val scaled = if (longest > IMAGE_MAX_DIM) {
            val s = IMAGE_MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(image, (image.width * s).toInt(), (image.height * s).toInt(), true)
        } else {
            image
        }
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            if (scaled !== image) scaled.recycle()
            // The size reported is the *source* one the hole coordinates are in.
            EncodedImage(out.toByteArray(), image.width, image.height)
        }
    }

actual fun decodeSeriesJpeg(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}
