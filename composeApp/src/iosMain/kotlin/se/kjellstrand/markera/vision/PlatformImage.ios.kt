@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGInterpolationHigh
import platform.UIKit.UIImage

actual typealias PlatformImage = UIImage

// Pixel dimensions, not points: UIImage.size is points (× scale).
actual val PlatformImage.width: Int
    get() = CGImage?.let { CGImageGetWidth(it).toInt() } ?: 0

actual val PlatformImage.height: Int
    get() = CGImage?.let { CGImageGetHeight(it).toInt() } ?: 0

// Assumes the UIImage is already upright — the picker/camera code normalises
// orientation before anything here sees it — so the backing CGImage can be
// cropped and drawn directly, ignoring `imageOrientation`.
actual fun PlatformImage.centerSquare(): PlatformImage {
    val w = width
    val h = height
    if (w == h || w == 0 || h == 0) return this
    val cg = CGImage ?: return this
    val side = minOf(w, h)
    val cropped = CGImageCreateWithImageInRect(
        cg,
        CGRectMake(
            ((w - side) / 2).toDouble(),
            ((h - side) / 2).toDouble(),
            side.toDouble(),
            side.toDouble(),
        ),
    ) ?: return this
    val out = UIImage(cGImage = cropped)
    CGImageRelease(cropped)
    return out
}

actual fun PlatformImage.argb(width: Int, height: Int): IntArray {
    val out = IntArray(width * height)
    val cg = CGImage ?: return out
    val space = CGColorSpaceCreateDeviceRGB()
    val ctx = CGBitmapContextCreate(
        data = null,
        width = width.toULong(),
        height = height.toULong(),
        bitsPerComponent = 8.toULong(),
        bytesPerRow = (width * 4).toULong(),
        space = space,
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
    )
    CGColorSpaceRelease(space)
    if (ctx == null) return out
    CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh)
    CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cg)
    // The JPEG/camera input is opaque, so the premultiplied RGBA above is the
    // plain RGBA we want; just repack it into 0xAARRGGBB.
    val raw = CGBitmapContextGetData(ctx)?.readBytes(width * height * 4)
    CGContextRelease(ctx)
    if (raw != null) {
        for (i in out.indices) {
            val o = i * 4
            out[i] = ((raw[o + 3].toInt() and 0xFF) shl 24) or
                ((raw[o].toInt() and 0xFF) shl 16) or
                ((raw[o + 1].toInt() and 0xFF) shl 8) or
                (raw[o + 2].toInt() and 0xFF)
        }
    }
    return out
}
