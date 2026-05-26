package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import se.kjellstrand.markera.vision.CameraIntrinsics
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.buildImageToOutputHomography
import se.kjellstrand.markera.vision.recoverCirclePose

private const val MARGIN_RATIO_DEFAULT: Float = 1.25f

/**
 * True perspective rectification: recover the 3D pose of the target plane
 * from the fitted ellipse + camera intrinsics, then warp this bitmap into
 * a square `outputSize` × `outputSize` frontal view where the black ring
 * is a true circle. Returns null if pose recovery is degenerate (caller
 * should fall back to [unwarpToCircle]).
 */
fun Bitmap.unwarpToCirclePerspective(
    calibration: TargetCalibration,
    intrinsics: CameraIntrinsics,
    outputSize: Int,
    marginRatio: Float = MARGIN_RATIO_DEFAULT,
): Bitmap? {
    require(outputSize > 0) { "outputSize must be positive, got $outputSize" }
    require(marginRatio > 0f) { "marginRatio must be positive, got $marginRatio" }
    val pose = recoverCirclePose(calibration, intrinsics, TARGET_BLACK_RING_RADIUS_MM)
        ?: return null
    val h = buildImageToOutputHomography(
        pose, intrinsics, outputSize, marginRatio, TARGET_BLACK_RING_RADIUS_MM,
    ) ?: return null

    val matrix = Matrix().apply { setValues(h) }
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
 * Calibration in the coordinate system produced by [unwarpToCirclePerspective].
 * Identical shape to [afterUnwarpToCircle]: the warped ring is a centred
 * circle of radius `outputSize / (2 · marginRatio)`, so scoring sees a
 * trivial circular calibration.
 */
fun TargetCalibration.afterUnwarpPerspective(
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
