@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.width

class ThumbnailJpegIosTest {

    private fun jpeg(w: Int, h: Int): ByteArray {
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { scale = 1.0 }
        val image = UIGraphicsImageRenderer(size = CGSizeMake(w.toDouble(), h.toDouble()), format = format)
            .imageWithActions { }
        val data = UIImageJPEGRepresentation(image, 0.9)!!
        return data.bytes!!.readBytes(data.length.toInt())
    }

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val image = bytes.usePinned { UIImage(data = NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())) }
        return image.width to image.height
    }

    @Test
    fun aFullFrameShrinksToExactlyTheMaxOnItsLongerSide() {
        assertEquals(384 to 384, size(thumbnailJpeg(jpeg(3072, 3072), 384)!!))
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
