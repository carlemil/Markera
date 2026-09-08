package se.kjellstrand.markera.ui.markera

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.TargetLine
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the scoring result over the frozen frame, fit-centre letterboxed to
 * line up with the Image/PreviewView: the 6/7 [ring], the [centre] crosshair, a
 * marker + ring-value label per scored hole.
 *
 * [showDebug] adds the raw detection data — hole boxes, recognised [digits]
 * boxes and the fitted digit-row lines — for diagnostics; the default clean
 * view shows only the result.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    digits: List<DigitDetection> = emptyList(),
    centre: CentreEstimate? = null,
    ring: FittedEllipse? = null,
    scores: List<HitScore> = emptyList(),
    /**
     * Key letters drawn by the hole markers, parallel to [scores]; when a
     * position is missing the caller must pass these so marker and list stay in
     * step. Defaults to the score's own index ("a", "b", …).
     */
    letters: List<String> = emptyList(),
    showDebug: Boolean = false,
    boxColor: Color = Color(0xFF00E676),
    digitColor: Color = Color(0xFF00B0FF),
    centreColor: Color = Color(0xFFFF1744),
    rowLineColor: Color = Color(0xFFFFC400),
    ringColor: Color = Color(0xFF00E676),
    scoreColor: Color = Color(0xFFFFFFFF),
    holeColor: Color = Color(0xFF00E676),
    /** Holes the user tapped in by hand, marker and label. */
    manualColor: Color = Color(0xFFFFB74D),
    strokeWidthPx: Float = 4f,
) {
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

        // 6/7 boundary ellipse: rotate the canvas about the ellipse centre and
        // draw an axis-aligned oval, matching the fit's (semiMajor, semiMinor,
        // rotationRad) parametrisation.
        if (ring != null) {
            val ecx = ring.cx * scale + offsetX
            val ecy = ring.cy * scale + offsetY
            val a = ring.semiMajor * scale
            val b = ring.semiMinor * scale
            rotate(degrees = (ring.rotationRad * 180.0 / PI).toFloat(), pivot = Offset(ecx, ecy)) {
                drawOval(
                    color = ringColor,
                    topLeft = Offset(ecx - a, ecy - b),
                    size = Size(a * 2f, b * 2f),
                    style = Stroke(width = strokeWidthPx + 2f),
                )
            }
        }

        // Holes: a full box in debug, otherwise just a small marker dot — the
        // hole itself is already visible in the photo. The dots come from the
        // scores when there are any, since only those know which holes the user
        // placed by hand (drawn orange); unscored frames fall back to the boxes.
        val dotRadius = max(3f, strokeWidthPx * 1.1f)
        if (showDebug) {
            detections.forEach { d ->
                drawRect(
                    color = boxColor,
                    topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                    size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                    style = Stroke(width = strokeWidthPx),
                )
            }
        } else if (scores.isNotEmpty()) {
            scores.forEach { hit ->
                drawCircle(
                    color = if (hit.manual) manualColor else holeColor,
                    radius = dotRadius,
                    center = Offset(hit.centerXpx * scale + offsetX, hit.centerYpx * scale + offsetY),
                )
            }
        } else {
            detections.forEach { d ->
                val hx = (d.left + d.right) / 2f * scale + offsetX
                val hy = (d.top + d.bottom) / 2f * scale + offsetY
                drawCircle(holeColor, radius = dotRadius, center = Offset(hx, hy))
            }
        }

        // Key letter just below-right of each hole, tying the marker to its
        // score box / list row. Deliberately small and unbolded — the ring
        // value above the hole is the primary label.
        if (scores.isNotEmpty()) {
            val letterPaint = Paint().apply {
                isAntiAlias = true
                textSize = (28f * scale).coerceIn(44f, 128f) * 0.52f
                textAlign = Paint.Align.LEFT
                setShadowLayer(5f, 0f, 1f, android.graphics.Color.BLACK)
            }
            scores.forEachIndexed { i, hit ->
                letterPaint.color = (if (hit.manual) manualColor else holeColor).toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    letters.getOrElse(i) { holeLetter(i) },
                    hit.centerXpx * scale + offsetX + dotRadius * 1.6f,
                    hit.centerYpx * scale + offsetY + dotRadius + letterPaint.textSize * 0.9f,
                    letterPaint,
                )
            }
        }

        // Recognised digit boxes — debug only.
        if (showDebug) {
            digits.forEach { d ->
                drawRect(
                    color = digitColor,
                    topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                    size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                    style = Stroke(width = strokeWidthPx),
                )
            }
        }

        // Ring-value label above each scored hole ("X" for inner-ten), nudged up
        // to avoid overlapping labels in dense clusters.
        if (scores.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = scoreColor.toArgb()
                textSize = (28f * scale).coerceIn(44f, 128f)
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
                isFakeBoldText = true
            }
            val gap = labelPaint.textSize * 0.15f
            val placed = ArrayList<RectF>(scores.size)
            // Place top holes first so lower labels stack above them.
            scores.sortedBy { it.topYpx }.forEach { hit ->
                val label = if (hit.isInnerTen) "X" else hit.ring.toString()
                labelPaint.color = (if (hit.manual) manualColor else scoreColor).toArgb()
                val w = labelPaint.measureText(label)
                val x = hit.centerXpx * scale + offsetX
                var baseline = hit.topYpx * scale + offsetY - labelPaint.textSize * 0.2f
                var top = baseline - labelPaint.textSize
                var guard = 0
                while (guard++ <= placed.size) {
                    val hitRect = placed.firstOrNull { r ->
                        x + w / 2f > r.left && x - w / 2f < r.right && baseline > r.top && top < r.bottom
                    } ?: break
                    baseline = hitRect.top - gap
                    top = baseline - labelPaint.textSize
                }
                drawContext.canvas.nativeCanvas.drawText(label, x, baseline, labelPaint)
                placed.add(RectF(x - w / 2f, top, x + w / 2f, baseline))
            }
        }

        if (centre != null && centre.method != CentreMethod.NONE) {
            // Fitted digit-row lines — debug only (infinite lines, clipped to
            // the image area so they don't bleed into the letterbox bars).
            if (showDebug) {
                clipRect(
                    left = offsetX,
                    top = offsetY,
                    right = offsetX + imageWidth * scale,
                    bottom = offsetY + imageHeight * scale,
                ) {
                    listOfNotNull(centre.horizontalLine, centre.verticalLine).forEach { line ->
                        drawRowLine(line, scale, offsetX, offsetY, rowLineColor, strokeWidthPx / 2f)
                    }
                }
            }

            val cx = centre.x * scale + offsetX
            val cy = centre.y * scale + offsetY
            val arm = 24f
            drawLine(
                color = centreColor,
                start = Offset(cx - arm, cy),
                end = Offset(cx + arm, cy),
                strokeWidth = strokeWidthPx + 2f,
            )
            drawLine(
                color = centreColor,
                start = Offset(cx, cy - arm),
                end = Offset(cx, cy + arm),
                strokeWidth = strokeWidthPx + 2f,
            )
        }
    }
}

private fun DrawScope.drawRowLine(
    line: TargetLine,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    color: Color,
    strokeWidth: Float,
) {
    // Uniform scaling preserves the direction; extend well past the viewport
    // on both sides and let the clip do the trimming.
    val px = line.px * scale + offsetX
    val py = line.py * scale + offsetY
    val reach = size.width + size.height
    drawLine(
        color = color,
        start = Offset(px - line.dx * reach, py - line.dy * reach),
        end = Offset(px + line.dx * reach, py + line.dy * reach),
        strokeWidth = strokeWidth,
    )
}
