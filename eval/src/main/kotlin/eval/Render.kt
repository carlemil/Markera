package eval

import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.INNER_TEN_RADIUS_MM
import se.kjellstrand.markera.vision.RING_RADII_MM
import se.kjellstrand.markera.vision.TargetCalibration
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * Draw the full detection result onto a fresh RGB copy of [src]:
 *  - the recovered 7-ring ellipse + all scoring rings (cyan), perspective
 *    foreshortening preserved, plus the centre crosshair,
 *  - every detected hole's box (green) labelled with its ring + confidence,
 *  - a caption banner with the per-image summary.
 */
fun annotate(
    src: BufferedImage,
    detections: List<Detection>,
    perHole: List<HitScore>,
    calibration: TargetCalibration?,
    fileName: String,
): BufferedImage {
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(src, 0, 0, null)

    val unit = max(src.width, src.height) / 500f
    val ringStroke = BasicStroke(max(1f, 1.5f * unit))
    val boxStroke = BasicStroke(max(1.5f, 2f * unit))

    if (calibration != null) {
        drawRings(g, calibration, ringStroke, unit)
    }

    g.stroke = boxStroke
    val labelFont = Font("SansSerif", Font.BOLD, max(11, (10 * unit).toInt()))
    g.font = labelFont
    detections.forEachIndexed { i, d ->
        g.color = Color(0x00, 0xE6, 0x76)
        val x = d.left.toInt()
        val y = d.top.toInt()
        val w = (d.right - d.left).toInt()
        val h = (d.bottom - d.top).toInt()
        g.drawRect(x, y, w, h)
        val score = perHole.getOrNull(i)
        val ringStr = score?.let { if (it.isInnerTen) "X" else it.ring.toString() } ?: "?"
        val label = "$ringStr  ${(d.conf * 100).toInt()}%"
        drawLabel(g, label, x, y - 2, Color(0x00, 0xC8, 0x5A))
    }

    drawCaption(g, src.width, src.height, unit, fileName, detections, perHole, calibration)
    g.dispose()
    return out
}

/** Concentric scoring rings + inner-ten, transformed by the fitted ellipse. */
private fun drawRings(g: Graphics2D, c: TargetCalibration, stroke: BasicStroke, unit: Float) {
    val pxPerMmMajor = c.semiMajorPx / TARGET_BLACK_RING_RADIUS_MM_LOCAL
    val pxPerMmMinor = c.semiMinorPx / TARGET_BLACK_RING_RADIUS_MM_LOCAL

    val saved: AffineTransform = g.transform
    g.translate(c.centerX.toDouble(), c.centerY.toDouble())
    g.rotate(c.rotationRad.toDouble())
    g.stroke = stroke

    val radiiMm = DoubleArray(RING_RADII_MM.size + 1)
    radiiMm[0] = INNER_TEN_RADIUS_MM
    for (i in RING_RADII_MM.indices) radiiMm[i + 1] = RING_RADII_MM[i]

    for ((idx, mm) in radiiMm.withIndex()) {
        val rx = mm * pxPerMmMajor
        val ry = mm * pxPerMmMinor
        // Black-ring radius (7-ring, 100mm) drawn brighter as the anchor.
        g.color = when {
            idx == 0 -> Color(0xFF, 0xD5, 0x4F) // inner-X
            mm == TARGET_BLACK_RING_RADIUS_MM_LOCAL -> Color(0x00, 0xE5, 0xFF)
            else -> Color(0x4D, 0xD0, 0xE1)
        }
        g.draw(Ellipse2D.Double(-rx, -ry, 2 * rx, 2 * ry))
    }
    g.transform = saved

    // Centre crosshair (drawn un-rotated, in image space).
    g.color = Color(0x00, 0xE5, 0xFF)
    val cx = c.centerX.toInt()
    val cy = c.centerY.toInt()
    val cross = (6 * unit).toInt().coerceAtLeast(4)
    g.drawLine(cx - cross, cy, cx + cross, cy)
    g.drawLine(cx, cy - cross, cx, cy + cross)
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
    perHole: List<HitScore>,
    calibration: TargetCalibration?,
) {
    val total = perHole.sumOf { if (it.isInnerTen) 10 else it.ring }
    val calStr = if (calibration != null) {
        "ring-fit conf %.2f".format(calibration.confidence)
    } else {
        "no ring fit (centre fallback)"
    }
    val line1 = fileName
    val line2 = "holes: ${detections.size}   total: $total   $calStr"

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

private const val TARGET_BLACK_RING_RADIUS_MM_LOCAL = 100.0
