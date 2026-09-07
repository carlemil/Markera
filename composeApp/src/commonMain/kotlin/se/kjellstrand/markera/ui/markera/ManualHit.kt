package se.kjellstrand.markera.ui.markera

import kotlin.math.min
import kotlin.math.sqrt
import se.kjellstrand.markera.vision.Detection

/** Side of a hand-placed hole box when the detector found nothing to size it from. */
const val MANUAL_HOLE_FALLBACK_PX = 20f

/**
 * Inverse of the fit-centre letterbox the viewport and `DetectionOverlay` use:
 * a tap at [x],[y] in a [viewW]x[viewH] viewport back to source-image pixels.
 * Null when the tap lands on a letterbox bar (outside the image).
 */
fun viewportToImage(
    x: Float,
    y: Float,
    viewW: Float,
    viewH: Float,
    imageW: Int,
    imageH: Int,
): Pair<Float, Float>? {
    if (imageW <= 0 || imageH <= 0 || viewW <= 0f || viewH <= 0f) return null
    val scale = min(viewW / imageW, viewH / imageH)
    val ix = (x - (viewW - imageW * scale) / 2f) / scale
    val iy = (y - (viewH - imageH * scale) / 2f) / scale
    return if (ix < 0f || iy < 0f || ix > imageW || iy > imageH) null else ix to iy
}

/**
 * The box for a hole the user tapped at [x],[y] (image px): a square the size
 * of a typical detected hole (median side, or [MANUAL_HOLE_FALLBACK_PX] with
 * nothing to measure), so [se.kjellstrand.markera.vision.scoreHits] gauges its
 * edge like any other. Null when the tap is within [minGapPx] of an existing
 * hole in [existing] — that is a mis-tap, not a second hole.
 */
fun manualDetection(
    x: Float,
    y: Float,
    existing: List<Detection>,
    minGapPx: Float,
): Detection? {
    val tooClose = existing.any {
        val dx = x - (it.left + it.right) / 2f
        val dy = y - (it.top + it.bottom) / 2f
        sqrt(dx * dx + dy * dy) < minGapPx
    }
    if (tooClose) return null
    val sides = existing.map { ((it.right - it.left) + (it.bottom - it.top)) / 2f }.sorted()
    val half = (sides.getOrNull(sides.size / 2) ?: MANUAL_HOLE_FALLBACK_PX) / 2f
    return Detection(x - half, y - half, x + half, y + half, conf = 1f)
}

/**
 * Index of the hole a touch at [x],[y] (image px) grabs: the nearest one within
 * [maxDist], or -1 when nothing is in reach. Detected and hand-placed holes are
 * equally grabbable — dragging moves them, a long press removes them.
 */
fun nearestDetectionIndex(x: Float, y: Float, detections: List<Detection>, maxDist: Float): Int {
    var best = -1
    var bestDist = maxDist
    detections.forEachIndexed { i, d ->
        val dx = x - (d.left + d.right) / 2f
        val dy = y - (d.top + d.bottom) / 2f
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < bestDist) {
            best = i
            bestDist = dist
        }
    }
    return best
}

/** The same hole box re-centred on [x],[y] (image px) — a dragged hole keeps its size. */
fun Detection.movedTo(x: Float, y: Float): Detection {
    val halfW = (right - left) / 2f
    val halfH = (bottom - top) / 2f
    return Detection(x - halfW, y - halfH, x + halfW, y + halfH, conf)
}
