package se.kjellstrand.markera.ui.markera

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
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.TargetLine
import kotlin.math.PI
import kotlin.math.min

/**
 * Draws image-space [Detection] boxes, recognised [digits], the fitted
 * digit-row lines, and the estimated [centre] crosshair scaled into this
 * Composable's canvas, using fit-centre letterboxing so everything lines up
 * with a PreviewView / Image that uses the same scale type.
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
    boxColor: Color = Color(0xFF00E676),
    digitColor: Color = Color(0xFF00B0FF),
    centreColor: Color = Color(0xFFFF1744),
    rowLineColor: Color = Color(0xFFFFC400),
    ringColor: Color = Color(0xFF00E676),
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

        detections.forEach { d ->
            drawRect(
                color = boxColor,
                topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                style = Stroke(width = strokeWidthPx),
            )
        }

        digits.forEach { d ->
            drawRect(
                color = digitColor,
                topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                style = Stroke(width = strokeWidthPx),
            )
        }

        if (centre != null && centre.method != CentreMethod.NONE) {
            // The fitted row lines are infinite; draw them long and clip to
            // the image area so they don't bleed into the letterbox bars.
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
