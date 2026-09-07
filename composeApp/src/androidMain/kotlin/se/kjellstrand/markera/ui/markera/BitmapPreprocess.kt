package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.min

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

/**
 * Centre-crop [this] to a square (side = the shorter edge). Matches the
 * FILL_CENTER live preview so the frozen frame, detections, and centre all
 * operate on the same square the user framed. Returns [this] if already square.
 */
fun Bitmap.centerSquare(): Bitmap {
    if (width == height) return this
    val side = min(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}

/**
 * Pack [this] into a row-major 8-bit luma [ByteArray] (Rec. 601 weights),
 * the input the `vision` edge/ellipse routines expect. One byte per pixel,
 * no padding or rescale, so pixel coordinates match the bitmap.
 */
fun Bitmap.toGrayscale(): ByteArray {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val out = ByteArray(width * height)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return out
}

/**
 * Letterbox-scale [this] into a square of [inputSize], copy into a CHW
 * float array normalised to 0..1. Matches the inverse transform that
 * `DetectionPostProcess.mapToImageSpace` performs.
 */
fun Bitmap.toModelInput(inputSize: Int): FloatArray {
    val scale = min(inputSize.toFloat() / width, inputSize.toFloat() / height)
    val newW = (width * scale).toInt().coerceAtLeast(1)
    val newH = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, newW, newH, true)
    val padX = (inputSize - newW) / 2
    val padY = (inputSize - newH) / 2

    val padded = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    Canvas(padded).drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
    if (scaled !== this) scaled.recycle()

    val pixels = IntArray(inputSize * inputSize)
    padded.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    padded.recycle()

    val plane = inputSize * inputSize
    val chw = FloatArray(3 * plane)
    for (i in 0 until plane) {
        val p = pixels[i]
        chw[i] = ((p shr 16) and 0xFF) / 255f
        chw[i + plane] = ((p shr 8) and 0xFF) / 255f
        chw[i + 2 * plane] = (p and 0xFF) / 255f
    }
    return chw
}
