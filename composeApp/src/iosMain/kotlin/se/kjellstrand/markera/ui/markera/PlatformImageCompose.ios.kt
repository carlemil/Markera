@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.ui.markera

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGInterpolationHigh
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.width

actual fun PlatformImage.toImageBitmap(): ImageBitmap = toImageBitmap(width, height)

/**
 * The image drawn into a raster of [w] x [h] — the scaling decode path too. Core Graphics draws
 * straight into Skia's N32 layout on Apple (BGRA, premultiplied: little-endian 32-bit with alpha
 * first), so the pixels are copied out once and handed over as they are. A ~3000² frame used to
 * go RGBA → IntArray → BGRA bytes, three passes over 36 MB.
 */
internal fun PlatformImage.toImageBitmap(w: Int, h: Int): ImageBitmap {
    val info = ImageInfo.makeN32Premul(w, h)
    val cg = CGImage ?: return Image.makeRaster(info, ByteArray(w * h * 4), w * 4).toComposeImageBitmap()
    val space = CGColorSpaceCreateDeviceRGB()
    val ctx = CGBitmapContextCreate(
        data = null,
        width = w.toULong(),
        height = h.toULong(),
        bitsPerComponent = 8.toULong(),
        bytesPerRow = (w * 4).toULong(),
        space = space,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
    )
    CGColorSpaceRelease(space)
    val bytes = if (ctx == null) {
        ByteArray(w * h * 4)
    } else {
        CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh)
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cg)
        val raw = CGBitmapContextGetData(ctx)?.readBytes(w * h * 4) ?: ByteArray(w * h * 4)
        CGContextRelease(ctx)
        raw
    }
    return Image.makeRaster(info, bytes, w * 4).toComposeImageBitmap()
}
