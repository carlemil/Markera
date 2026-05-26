package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetCalibration
import kotlin.math.PI

private const val MARGIN_RATIO_DEFAULT: Float = 1.25f

/**
 * Affine unwarp: produce a square bitmap of side [outputSize] in which
 * the projected 7-ring ellipse from [calibration] becomes a true circle
 * of radius `outputSize / (2 * marginRatio)`, centred in the output.
 * With one ellipse the homography is under-determined, so affine is the
 * correct closed-form choice for "make this ellipse a circle".
 */
fun Bitmap.unwarpToCircle(
    calibration: TargetCalibration,
    outputSize: Int,
    marginRatio: Float = MARGIN_RATIO_DEFAULT,
): Bitmap {
    require(outputSize > 0) { "outputSize must be positive, got $outputSize" }
    require(marginRatio > 0f) { "marginRatio must be positive, got $marginRatio" }
    val a = calibration.semiMajorPx
    val b = calibration.semiMinorPx
    require(a > 0f && b > 0f) { "calibration axes must be positive (a=$a, b=$b)" }

    val rOut = outputSize / (2f * marginRatio)
    val uniformScale = rOut / a
    val thetaDeg = (calibration.rotationRad.toDouble() * 180.0 / PI).toFloat()
    val half = outputSize / 2f

    val matrix = Matrix().apply {
        postTranslate(-calibration.centerX, -calibration.centerY)
        postRotate(-thetaDeg)
        postScale(1f, a / b)
        postRotate(thetaDeg)
        postScale(uniformScale, uniformScale)
        postTranslate(half, half)
    }

    val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }
    canvas.drawBitmap(this, matrix, paint)
    return out
}

/**
 * Calibration in the coordinate system produced by [unwarpToCircle]:
 * the ring is a circle at the output centre with radius
 * `outputSize / (2 * marginRatio)`. Eccentricity is gone so the major /
 * minor axis distinction collapses; we report `rotationRad = 0` since
 * a circle has no defined principal axis.
 */
fun TargetCalibration.afterUnwarpToCircle(
    outputSize: Int,
    marginRatio: Float = MARGIN_RATIO_DEFAULT,
): TargetCalibration {
    val rOut = outputSize / (2f * marginRatio)
    return TargetCalibration(
        centerX = outputSize / 2f,
        centerY = outputSize / 2f,
        semiMajorPx = rOut,
        semiMinorPx = rOut,
        rotationRad = 0f,
        mmPerPx = TARGET_BLACK_RING_RADIUS_MM / rOut.toDouble(),
        confidence = confidence,
    )
}
