package eval

import se.kjellstrand.markera.vision.letterboxChw
import se.kjellstrand.markera.vision.letterboxDims
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Letterbox [img] into the model's CHW input the way the app does: bilinear
 * scale to the shared [letterboxDims], then the shared [letterboxChw] pad.
 */
fun toModelInput(img: BufferedImage, inputSize: Int): FloatArray {
    val (w, h) = letterboxDims(img.width, img.height, inputSize)
    val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = scaled.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return letterboxChw(scaled.getRGB(0, 0, w, h, null, 0, w), w, h, inputSize)
}
