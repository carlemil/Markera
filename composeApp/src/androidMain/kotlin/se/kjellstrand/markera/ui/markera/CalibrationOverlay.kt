package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import se.kjellstrand.markera.vision.TargetCalibration
import kotlin.math.PI
import kotlin.math.min

/**
 * Draws the calibrated target centre as a crosshair plus the fitted
 * 7-ring as an ellipse (rotated to match perspective foreshortening).
 * Same FIT_CENTER mapping as [DetectionOverlay] so the markings land on
 * top of the same image the user sees.
 */
@Composable
fun CalibrationOverlay(
    calibration: TargetCalibration?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    color: Color = Color(0x9900E5FF), // cyan, ~60% alpha
    strokeWidthPx: Float = 3f,
    crosshairHalfPx: Float = 18f,
) {
    Canvas(modifier = modifier) {
        if (calibration == null || imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f
        val cx = calibration.centerX * scale + offsetX
        val cy = calibration.centerY * scale + offsetY
        val a = calibration.semiMajorPx * scale
        val b = calibration.semiMinorPx * scale

        // Crosshair arms align with the ellipse's principal axes and the
        // minor-axis arm is shortened by b / a so the cross looks like a
        // symmetric "+" on the target plane projected through the same
        // perspective as the ring.
        val minorArm = if (a > 0f) crosshairHalfPx * (b / a) else crosshairHalfPx
        rotate(degrees = (calibration.rotationRad * 180.0 / PI).toFloat(), pivot = Offset(cx, cy)) {
            drawOval(
                color = color,
                topLeft = Offset(cx - a, cy - b),
                size = Size(2f * a, 2f * b),
                style = Stroke(width = strokeWidthPx),
            )
            drawLine(
                color = color,
                start = Offset(cx - crosshairHalfPx, cy),
                end = Offset(cx + crosshairHalfPx, cy),
                strokeWidth = strokeWidthPx,
            )
            drawLine(
                color = color,
                start = Offset(cx, cy - minorArm),
                end = Offset(cx, cy + minorArm),
                strokeWidth = strokeWidthPx,
            )
        }
    }
}
