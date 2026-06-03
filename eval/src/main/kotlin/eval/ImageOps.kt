package eval

import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.calibrateFromGrayscale
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.min

/**
 * Letterbox-scale [img] into a square of [inputSize] and pack it into a CHW
 * float array normalised to 0..1 — a faithful JVM port of the app's
 * `Bitmap.toModelInput`, so it pairs with the shared `mapToImageSpace`
 * inverse transform.
 */
fun toModelInput(img: BufferedImage, inputSize: Int): FloatArray {
    val w = img.width
    val h = img.height
    val scale = min(inputSize.toFloat() / w, inputSize.toFloat() / h)
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    val padX = (inputSize - newW) / 2
    val padY = (inputSize - newH) / 2

    val padded = BufferedImage(inputSize, inputSize, BufferedImage.TYPE_INT_RGB)
    val g = padded.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, padX, padY, newW, newH, null)
    g.dispose()

    val pixels = padded.getRGB(0, 0, inputSize, inputSize, null, 0, inputSize)
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

/**
 * Subsample [img] by [stride], convert to BT.601 luma, and run the shared
 * `calibrateFromGrayscale`. Returns the recovered 7-ring ellipse in
 * **full-resolution** pixel coordinates — a JVM port of `Bitmap.calibrate`.
 */
fun calibrate(img: BufferedImage, stride: Int = 4): TargetCalibration? {
    val width = img.width
    val height = img.height
    val sw = (width + stride - 1) / stride
    val sh = (height + stride - 1) / stride
    if (sw < 8 || sh < 8) return null

    val gray = ByteArray(sw * sh)
    var dst = 0
    var srcY = 0
    for (y in 0 until sh) {
        var srcX = 0
        for (x in 0 until sw) {
            val p = img.getRGB(srcX, srcY)
            val r = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.299 * r + 0.587 * gg + 0.114 * b).toInt().coerceIn(0, 255)
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
