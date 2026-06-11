package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import kotlin.math.min

/**
 * Draws image-space [Detection] boxes, recognised [digits], and the estimated
 * [centre] crosshair scaled into this Composable's canvas, using fit-centre
 * letterboxing so everything lines up with a PreviewView / Image that uses the
 * same scale type.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    digits: List<DigitDetection> = emptyList(),
    centre: CentreEstimate? = null,
    boxColor: Color = Color(0xFF00E676),
    digitColor: Color = Color(0xFF00B0FF),
    centreColor: Color = Color(0xFFFF1744),
    strokeWidthPx: Float = 4f,
) {
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f

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
