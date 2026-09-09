package se.kjellstrand.markera.ui.markera

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.argb
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.width

actual fun PlatformImage.toImageBitmap(): ImageBitmap = toImageBitmap(width, height)

/** The image drawn into a raster of [w] x [h] — the scaling decode path too. */
internal fun PlatformImage.toImageBitmap(w: Int, h: Int): ImageBitmap {
    val pixels = argb(w, h)
    // N32 on Apple is BGRA premultiplied.
    val bytes = ByteArray(w * h * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        val o = i * 4
        bytes[o] = (p and 0xFF).toByte()
        bytes[o + 1] = ((p shr 8) and 0xFF).toByte()
        bytes[o + 2] = ((p shr 16) and 0xFF).toByte()
        bytes[o + 3] = ((p shr 24) and 0xFF).toByte()
    }
    return Image.makeRaster(ImageInfo.makeN32Premul(w, h), bytes, w * 4).toComposeImageBitmap()
}
