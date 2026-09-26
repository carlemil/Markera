package se.kjellstrand.markera.series

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.vision.PlatformImage

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

actual fun thumbnailJpeg(bytes: ByteArray, maxDim: Int): ByteArray? {
    // Subsampled to within 2× of the target, then one filtered scale to exactly it.
    val sampled = decodeSampled(bytes, maxDim * 2) ?: return null
    val s = maxDim.toFloat() / maxOf(sampled.width, sampled.height)
    val thumb = if (s < 1f) {
        Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * s).roundToInt().coerceAtLeast(1),
            (sampled.height * s).roundToInt().coerceAtLeast(1),
            true,
        ).also { sampled.recycle() }
    } else {
        sampled
    }
    return ByteArrayOutputStream().use { out ->
        thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
        thumb.recycle()
        out.toByteArray()
    }
}

actual suspend fun decodeSeriesJpeg(bytes: ByteArray, maxDim: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    decodeSampled(bytes, maxDim)?.asImageBitmap()
}

private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
