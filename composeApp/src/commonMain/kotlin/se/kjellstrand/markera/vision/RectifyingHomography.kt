package se.kjellstrand.markera.vision

/**
 * Build the 3×3 image → output homography for a rectifying warp.
 *
 * Output image is a square of side [outputSize]. The world-plane circle
 * of radius [worldRadiusMm] (the target's black 7-ring) appears in the
 * output as a true circle of radius `outputSize / (2 · marginRatio)`,
 * centred at the output centre.
 *
 * The returned 9-floats array is row-major and ready for
 * `android.graphics.Matrix.setValues(...)` followed by
 * `Canvas.drawBitmap(src, matrix, paint)` (Android Canvas honours the
 * perspective row [6,7,8]).
 *
 * Returns null when `H_wi = K · [r1 | r2 | c0]` is singular (won't happen
 * for any geometrically valid pose, but cheap to guard).
 */
fun buildImageToOutputHomography(
    pose: CirclePose,
    intrinsics: CameraIntrinsics,
    outputSize: Int,
    marginRatio: Float = 1.25f,
    worldRadiusMm: Double = TARGET_BLACK_RING_RADIUS_MM,
): FloatArray? {
    require(outputSize > 0)
    require(marginRatio > 0f)
    val rOut = outputSize.toDouble() / (2.0 * marginRatio)
    val mmToPx = rOut / worldRadiusMm
    val half = outputSize / 2.0

    val K = intrinsics.toK()
    val Hwi = mat3Multiply(K, Mat3.fromColumns(pose.r1, pose.r2, pose.c0))
    val Hiw = mat3Inverse(Hwi) ?: return null

    // World-mm (origin at circle centre) → output-pixel.
    val S = Mat3.of(
        mmToPx, 0.0, half,
        0.0, mmToPx, half,
        0.0, 0.0, 1.0,
    )

    val Hio = mat3Multiply(S, Hiw)
    return Hio.toFloatArrayRowMajor()
}
