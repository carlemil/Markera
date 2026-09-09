@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package se.kjellstrand.markera.series

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import se.kjellstrand.markera.ui.markera.toImageBitmap
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.width

private const val IMAGE_MAX_DIM = 3072

actual suspend fun encodeSeriesJpeg(image: PlatformImage): EncodedImage {
    val w = image.width
    val h = image.height
    val longest = maxOf(w, h)
    val target = if (longest > IMAGE_MAX_DIM) {
        val s = IMAGE_MAX_DIM.toDouble() / longest
        val tw = (w * s).toInt().coerceAtLeast(1)
        val th = (h * s).toInt().coerceAtLeast(1)
        // scale = 1 so the renderer's point size is the pixel size.
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        UIGraphicsImageRenderer(size = CGSizeMake(tw.toDouble(), th.toDouble()), format = format)
            .imageWithActions {
                image.drawInRect(CGRectMake(0.0, 0.0, tw.toDouble(), th.toDouble()))
            }
    } else {
        image
    }
    val data = UIImageJPEGRepresentation(target, 0.9)
    // The size reported is the *source* one the hole coordinates are in.
    return EncodedImage(data?.toByteArray() ?: ByteArray(0), w, h)
}

actual fun decodeSeriesJpeg(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    // ponytail: full decode, then one downscaling draw (peak memory ~ the full
    // frame as ARGB). Swap in CGImageSourceCreateThumbnailAtIndex if that bites.
    val image = UIImage(data = bytes.toNSData())
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return null
    val longest = maxOf(w, h)
    if (longest <= maxDim) return image.toImageBitmap()
    val s = maxDim.toDouble() / longest
    return image.toImageBitmap(
        (w * s).toInt().coerceAtLeast(1),
        (h * s).toInt().coerceAtLeast(1),
    )
}

private fun ByteArray.toNSData(): NSData = usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

private fun NSData.toByteArray(): ByteArray =
    bytes?.readBytes(length.toInt()) ?: ByteArray(0)
