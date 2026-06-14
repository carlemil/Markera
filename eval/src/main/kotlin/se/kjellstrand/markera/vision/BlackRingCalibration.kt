package se.kjellstrand.markera.vision

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tightened black-7-ring calibration. Same Otsu -> connected-components ->
 * ellipse-fit core as [calibrateFromGrayscale], but blob selection is anchored
 * to a prior: the app frames the target inside the viewfinder guide circle, so
 * the black ring should be roughly [expectedRadiusPx] and centred in the frame.
 * Blobs are scored by solidity x radius-prior x centre-prior, which suppresses
 * the small, very-central paster-cluster blobs the generic selector locked
 * onto. (A digit-line-centre seed was tried and dropped: it never differed from
 * the image centre on framed-centred targets.)
 */
fun calibrateBlackRing(
    gray: ByteArray,
    width: Int,
    height: Int,
    expectedRadiusPx: Float,
): TargetCalibration? {
    if (width <= 0 || height <= 0 || gray.size < width * height) return null
    val threshold = otsuThreshold(gray, width, height)
    if (threshold <= 0) return null

    val minAxis = min(width, height)
    // Accept only blobs near the guide-circle size: rejects tiny paster
    // clusters and frame-spanning shadows outright.
    val minRadius = max(0.05f * minAxis, 0.5f * expectedRadiusPx)
    val maxRadius = min(0.48f * minAxis, 1.7f * expectedRadiusPx)
    val minArea = (PI * minRadius * minRadius).toInt().coerceAtLeast(16)
    val cc = findDarkBlobs(gray, width, height, threshold, minArea)
    if (cc.blobs.isEmpty()) return null

    val radiusSigma = 0.4f * expectedRadiusPx
    val centreSigma = 0.6f * expectedRadiusPx

    data class Scored(val blob: Blob, val score: Double, val solidity: Double)
    val candidates = cc.blobs.mapNotNull { b ->
        val rEst = sqrt(b.pixelCount.toDouble() / PI).toFloat()
        if (rEst < minRadius || rEst > maxRadius) return@mapNotNull null
        val bboxW = b.width.toDouble()
        val bboxH = b.height.toDouble()
        val aspect = max(bboxW / bboxH, bboxH / bboxW)
        if (aspect > 2.0) return@mapNotNull null
        val solidity = b.pixelCount.toDouble() / (bboxW * bboxH)
        if (solidity < 0.5) return@mapNotNull null
        val rz = (rEst - expectedRadiusPx) / radiusSigma
        val radiusWeight = exp(-(rz * rz).toDouble())
        val dx = b.centroidX - width / 2f
        val dy = b.centroidY - height / 2f
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val cz = dist / centreSigma
        val centreWeight = exp(-(cz * cz).toDouble())
        Scored(b, solidity * radiusWeight * centreWeight, solidity)
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
