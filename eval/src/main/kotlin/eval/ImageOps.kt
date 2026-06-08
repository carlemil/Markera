package eval

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
