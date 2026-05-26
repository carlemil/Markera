package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.min

/**
 * Convert the analyzer-thread ImageProxy into an upright ARGB_8888 Bitmap.
 * CameraX's [ImageProxy.toBitmap] returns the bitmap in sensor orientation;
 * we apply the rotation metadata so downstream coordinates match the
 * PreviewView the user sees.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
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
