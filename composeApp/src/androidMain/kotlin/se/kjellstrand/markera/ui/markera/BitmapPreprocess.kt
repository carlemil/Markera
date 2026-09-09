package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * The captured still as an upright bitmap: the viewport [ImageProxy.getCropRect]
 * and the sensor rotation applied in one pass over the decoded JPEG, so
 * downstream coordinates match the square PreviewView the user sees.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val src = toBitmap()
    val crop = cropRect
    val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
    val out = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height(), matrix, true)
    if (out !== src) src.recycle()
    return out
}
