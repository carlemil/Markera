package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import se.kjellstrand.markera.vision.Detection
import kotlin.math.min

/**
 * Draws image-space [Detection] boxes scaled into this Composable's
 * canvas, using fit-centre letterboxing so the boxes line up with a
 * PreviewView / Image that uses the same scale type.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    boxColor: Color = Color(0xFF00E676),
    strokeWidthPx: Float = 4f,
) {
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0 || detections.isEmpty()) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f
        detections.forEach { d ->
            val left = d.left * scale + offsetX
            val top = d.top * scale + offsetY
            val w = (d.right - d.left) * scale
            val h = (d.bottom - d.top) * scale
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = strokeWidthPx),
            )
        }
    }
}
