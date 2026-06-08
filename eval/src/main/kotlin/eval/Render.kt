package eval

import se.kjellstrand.markera.vision.Detection
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * Draw the detection result onto a fresh RGB copy of [src]:
 *  - every detected hole's box (green) labelled with its confidence,
 *  - a caption banner with the per-image summary.
 */
fun annotate(
    src: BufferedImage,
    detections: List<Detection>,
    fileName: String,
): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(src, 0, 0, null)

    val unit = max(src.width, src.height) / 500f
    val boxStroke = BasicStroke(max(1.5f, 2f * unit))

    g.stroke = boxStroke
    val labelFont = Font("SansSerif", Font.BOLD, max(11, (10 * unit).toInt()))
    g.font = labelFont
    detections.forEach { d ->
        g.color = Color(0x00, 0xE6, 0x76)
        val x = d.left.toInt()
        val y = d.top.toInt()
        val w = (d.right - d.left).toInt()
        val h = (d.bottom - d.top).toInt()
        g.drawRect(x, y, w, h)
        val label = "${(d.conf * 100).toInt()}%"
        drawLabel(g, label, x, y - 2, Color(0x00, 0xC8, 0x5A))
    }

    drawCaption(g, src.width, src.height, unit, fileName, detections)
    g.dispose()
    return out
}

private fun drawLabel(g: Graphics2D, text: String, x: Int, y: Int, bg: Color) {
    val fm = g.fontMetrics
    val tw = fm.stringWidth(text)
    val th = fm.height
    val ty = (y - th).coerceAtLeast(0)
    val prev = g.composite
    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
    g.color = bg
    g.fillRect(x, ty, tw + 6, th)
    g.composite = prev
    g.color = Color.BLACK
    g.drawString(text, x + 3, ty + fm.ascent)
}

private fun drawCaption(
    g: Graphics2D,
    w: Int,
    h: Int,
    unit: Float,
    fileName: String,
    detections: List<Detection>,
) {
    val line1 = fileName
    val line2 = "holes: ${detections.size}"

    val font = Font("SansSerif", Font.BOLD, max(12, (11 * unit).toInt()))
    g.font = font
    val fm = g.fontMetrics
    val pad = (4 * unit).toInt().coerceAtLeast(3)
    val bandH = fm.height * 2 + pad * 2

    val prev = g.composite
    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f)
    g.color = Color.BLACK
    g.fillRect(0, h - bandH, w, bandH)
    g.composite = prev

    g.color = Color.WHITE
    g.drawString(line1, pad, h - bandH + pad + fm.ascent)
    g.color = Color(0xB2, 0xFF, 0x59)
    g.drawString(line2, pad, h - bandH + pad + fm.height + fm.ascent)
}
