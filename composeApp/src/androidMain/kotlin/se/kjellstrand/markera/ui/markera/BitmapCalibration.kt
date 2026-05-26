package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.calibrateFromGrayscale

/**
 * Subsample [this] Bitmap by [stride] in each axis, convert to grayscale
 * using BT.601 luma weights, and run [calibrateFromGrayscale]. Returns a
 * [TargetCalibration] in **original full-resolution Bitmap coordinates**
 * — centre and ellipse axes scaled back up by [stride]. The rotation
 * angle is invariant under uniform subsampling.
 */
fun Bitmap.calibrate(stride: Int = 4): TargetCalibration? {
    val sw = (width + stride - 1) / stride
    val sh = (height + stride - 1) / stride
    if (sw < 8 || sh < 8) return null

    val gray = ByteArray(sw * sh)
    val row = IntArray(width)
    var dst = 0
    var srcY = 0
    for (y in 0 until sh) {
        getPixels(row, 0, width, 0, srcY, width, 1)
        var srcX = 0
        for (x in 0 until sw) {
            val p = row[srcX]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            gray[dst++] = luma.toByte()
            srcX += stride
            if (srcX >= width) srcX = width - 1
        }
        srcY += stride
        if (srcY >= height) srcY = height - 1
    }

    val small = calibrateFromGrayscale(gray, sw, sh) ?: return null
    val s = stride.toFloat()
    val majorFull = small.semiMajorPx * s
    val minorFull = small.semiMinorPx * s
    return TargetCalibration(
        centerX = small.centerX * s,
        centerY = small.centerY * s,
        semiMajorPx = majorFull,
        semiMinorPx = minorFull,
        rotationRad = small.rotationRad,
        mmPerPx = TARGET_BLACK_RING_RADIUS_MM / majorFull,
        confidence = small.confidence,
    )
}
