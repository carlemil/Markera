package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Black 7-ring outer radius in mm — used to convert pixels to mm. */
const val TARGET_BLACK_RING_RADIUS_MM: Double = 100.0

/**
 * Recovered ellipse fit of the projected black 7-ring. Carrying the full
 * ellipse (not just a circle) lets the scoring code undo perspective
 * foreshortening: a tilted camera projects the circular 7-ring to an
 * ellipse, and stretching back along the minor axis recovers the
 * frontal-plane distances we actually want to score.
 *
 * [centerX, centerY] is the ellipse centroid. Strictly speaking, under a
 * full perspective projection the projected centre of the 3D circle is
 * offset from the ellipse centroid toward the far side. Recovering that
 * offset would need either the camera's focal length or a manual tap;
 * for now we accept the centroid as the best automatic estimate.
 */
data class TargetCalibration(
    val centerX: Float,
    val centerY: Float,
    val semiMajorPx: Float,
    val semiMinorPx: Float,
    /** Angle of the major axis from +x, radians. */
    val rotationRad: Float,
    val mmPerPx: Double,
    val confidence: Float,
)

/**
 * Otsu → connected components → blob selection → ellipse fit. Coordinates
 * are in the [gray] buffer's own pixel space; the Android adapter scales
 * them back to original Bitmap pixels.
 */
fun calibrateFromGrayscale(gray: ByteArray, width: Int, height: Int): TargetCalibration? {
    if (width <= 0 || height <= 0 || gray.size < width * height) return null

    val threshold = otsuThreshold(gray, width, height)
    if (threshold <= 0) return null

    val minAxis = min(width, height)
    val minArea = (PI * (0.05 * minAxis) * (0.05 * minAxis)).toInt().coerceAtLeast(16)
    val cc = findDarkBlobs(gray, width, height, threshold, minArea)
    if (cc.blobs.isEmpty()) return null

    val imageCx = width / 2f
    val imageCy = height / 2f
    val halfAxis = minAxis / 2f
    val maxRadius = 0.45f * minAxis
    val minRadius = 0.05f * minAxis

    data class Scored(val blob: Blob, val score: Double, val radiusEstimate: Double, val solidity: Double)
    val candidates = cc.blobs.mapNotNull { b ->
        val rEst = sqrt(b.pixelCount.toDouble() / PI)
        if (rEst < minRadius || rEst > maxRadius) return@mapNotNull null
        val bboxW = b.width.toDouble()
        val bboxH = b.height.toDouble()
        val aspect = max(bboxW / bboxH, bboxH / bboxW)
        // Letterbox bars from PreviewView.getBitmap clock in ≥ 2.25 : 1.
        if (aspect > 2.0) return@mapNotNull null
        val bboxSolidity = b.pixelCount.toDouble() / (bboxW * bboxH)
        if (bboxSolidity < 0.55) return@mapNotNull null
        val dx = b.centroidX - imageCx
        val dy = b.centroidY - imageCy
        val dist = sqrt(dx * dx + dy * dy)
        val centralness = (1.0 - dist / halfAxis).coerceIn(0.0, 1.0)
        Scored(b, centralness * bboxSolidity, rEst, bboxSolidity)
    }
    val pick = candidates.maxByOrNull { it.score } ?: return null

    val ellipse = fitEllipseFromBlob(
        labels = cc.labels,
        width = width,
        labelTarget = pick.blob.label,
        bboxMinX = pick.blob.minX,
        bboxMaxX = pick.blob.maxX,
        bboxMinY = pick.blob.minY,
        bboxMaxY = pick.blob.maxY,
    ) ?: return null
    if (ellipse.semiMajor < minRadius || ellipse.semiMajor > maxRadius) return null

    // Confidence: solidity * mild eccentricity penalty. We still accept
    // moderately squashed ellipses (camera tilt is the use case!) — only
    // pathologically flat ones get knocked.
    val eccPenalty = (ellipse.semiMinor / ellipse.semiMajor).coerceIn(0.3f, 1f)
    val confidence = (pick.solidity.toFloat() * eccPenalty).coerceIn(0f, 1f)

    return TargetCalibration(
        centerX = ellipse.cx,
        centerY = ellipse.cy,
        semiMajorPx = ellipse.semiMajor,
        semiMinorPx = ellipse.semiMinor,
        rotationRad = ellipse.rotationRad,
        mmPerPx = TARGET_BLACK_RING_RADIUS_MM / ellipse.semiMajor,
        confidence = confidence,
    )
}
