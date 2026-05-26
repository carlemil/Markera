package se.kjellstrand.markera.vision

/**
 * Camera intrinsics in pixel coordinates of the active sensor array,
 * stored in the **sensor's** native orientation (the X axis runs along
 * the long edge of the sensor). Use [scaledToBitmap] to obtain
 * intrinsics in the orientation and resolution of an upright bitmap as
 * produced by `PreviewView.bitmap`.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val sensorWidthPx: Float,
    val sensorHeightPx: Float,
    /** Rotation applied to the sensor image to get an upright display image. */
    val sensorRotationDeg: Int,
    val source: Source,
) {
    enum class Source { CALIBRATED, DERIVED, ASSUMED }

    /** Row-major 3x3 K with this intrinsic's units. */
    fun toK(): Mat3 = Mat3.of(
        fx.toDouble(), 0.0, cx.toDouble(),
        0.0, fy.toDouble(), cy.toDouble(),
        0.0, 0.0, 1.0,
    )

    /**
     * Returns intrinsics in the coordinate frame of an upright bitmap of
     * dimensions [bitmapWidth] x [bitmapHeight], assuming the bitmap was
     * produced by `PreviewView` with `ScaleType.FILL_CENTER` — i.e. the
     * rotated sensor was uniformly scaled by the larger of width/height
     * ratios so the bitmap is fully covered, with excess on the other
     * axis equally cropped. This is what `PreviewView.bitmap` returns
     * when the View aspect doesn't match the preview aspect.
     */
    fun scaledToBitmap(bitmapWidth: Int, bitmapHeight: Int): CameraIntrinsics? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val rotated = rotatedForDisplay() ?: return null
        val sw = rotated.sensorWidthPx
        val sh = rotated.sensorHeightPx
        val bw = bitmapWidth.toFloat()
        val bh = bitmapHeight.toFloat()
        // FILL_CENTER: pick the larger of the two scale ratios so the
        // scaled sensor covers the bitmap on both axes; the excess on
        // the other axis is cropped equally on both sides.
        val scale = maxOf(bw / sw, bh / sh)
        val cropX = (sw * scale - bw) / 2f
        val cropY = (sh * scale - bh) / 2f
        return rotated.copy(
            fx = rotated.fx * scale,
            fy = rotated.fy * scale,
            cx = rotated.cx * scale - cropX,
            cy = rotated.cy * scale - cropY,
            sensorWidthPx = bw,
            sensorHeightPx = bh,
            sensorRotationDeg = 0,
        )
    }

    /**
     * Rotate intrinsics from sensor orientation to upright display
     * orientation per [sensorRotationDeg]. Standard pinhole derivation:
     * for a 90 deg CW image rotation, fx <-> fy and the principal point
     * reflects through the new image axes.
     */
    private fun rotatedForDisplay(): CameraIntrinsics? {
        val w = sensorWidthPx
        val h = sensorHeightPx
        return when (((sensorRotationDeg % 360) + 360) % 360) {
            0 -> this
            90 -> copy(
                fx = fy, fy = fx,
                cx = h - 1f - cy, cy = cx,
                sensorWidthPx = h, sensorHeightPx = w,
                sensorRotationDeg = 0,
            )
            180 -> copy(
                cx = w - 1f - cx, cy = h - 1f - cy,
                sensorRotationDeg = 0,
            )
            270 -> copy(
                fx = fy, fy = fx,
                cx = cy, cy = w - 1f - cx,
                sensorWidthPx = h, sensorHeightPx = w,
                sensorRotationDeg = 0,
            )
            else -> null
        }
    }
}
