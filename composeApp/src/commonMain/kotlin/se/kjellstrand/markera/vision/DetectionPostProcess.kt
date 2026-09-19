package se.kjellstrand.markera.vision

import kotlin.math.max
import kotlin.math.min

/** Side of the square tensor the hole model runs on. */
const val MODEL_INPUT_SIZE = 1536
const val HOLE_CONFIDENCE_THRESHOLD = 0.35f
const val HOLE_IOU_THRESHOLD = 0.45f

/** A series is five shots: the most confident five are kept. */
const val SHOTS_PER_SERIES = 5

/** Drops obvious noise at the inference boundary (zero-padded NMS slots). */
const val PREFILTER_CONFIDENCE = 0.01f

/**
 * Read the YOLO export's embedded-NMS output `[1, 300, 6]`: [count] floats
 * read through [get], rows (x1, y1, x2, y2, conf, classId) in input-tensor
 * pixels. Rows below [PREFILTER_CONFIDENCE] (the zero-padded slots) are
 * dropped, as is a trailing partial row.
 */
fun parseNmsRows(count: Int, get: (Int) -> Float): List<RawDetection> {
    val list = ArrayList<RawDetection>()
    for (i in 0 until count / 6) {
        val base = i * 6
        val conf = get(base + 4)
        if (conf < PREFILTER_CONFIDENCE) continue
        val x1 = get(base)
        val y1 = get(base + 1)
        val x2 = get(base + 2)
        val y2 = get(base + 3)
        list += RawDetection(
            cx = (x1 + x2) * 0.5f,
            cy = (y1 + y2) * 0.5f,
            w = x2 - x1,
            h = y2 - y1,
            conf = conf,
        )
    }
    return list
}

/**
 * The whole post-model pipeline, shared by the app and `:eval`: confidence
 * filter, NMS, keep the top [SHOTS_PER_SERIES], map back to image pixels.
 */
fun postProcess(raws: List<RawDetection>, inputSize: Int, srcWidth: Int, srcHeight: Int): List<Detection> =
    mapToImageSpace(
        nonMaxSuppression(filterByConfidence(raws, HOLE_CONFIDENCE_THRESHOLD), HOLE_IOU_THRESHOLD)
            .take(SHOTS_PER_SERIES),
        inputSize, srcWidth, srcHeight,
    )

/**
 * Drop detections with a confidence below [threshold].
 */
fun filterByConfidence(raws: List<RawDetection>, threshold: Float): List<RawDetection> =
    raws.filter { it.conf >= threshold }

/**
 * Greedy non-maximum suppression. Sort by confidence (descending) and
 * keep a detection iff its IoU with every previously-kept detection is
 * below [iouThreshold].
 */
fun nonMaxSuppression(raws: List<RawDetection>, iouThreshold: Float): List<RawDetection> {
    if (raws.size <= 1) return raws
    val sorted = raws.sortedByDescending { it.conf }
    val kept = ArrayList<RawDetection>(sorted.size)
    for (candidate in sorted) {
        var suppressed = false
        for (k in kept) {
            if (iou(candidate, k) >= iouThreshold) {
                suppressed = true
                break
            }
        }
        if (!suppressed) kept += candidate
    }
    return kept
}

/**
 * Map raw input-tensor-space boxes back into pixels of the original
 * (un-letterboxed) source image. Mirrors the letterbox preprocessing
 * that scales the longer side to [inputSize] and pads the shorter side
 * with zeros centred in the tensor.
 */
fun mapToImageSpace(
    raws: List<RawDetection>,
    inputSize: Int,
    srcWidth: Int,
    srcHeight: Int,
): List<Detection> {
    if (srcWidth <= 0 || srcHeight <= 0 || inputSize <= 0) return emptyList()
    val scale = min(inputSize.toFloat() / srcWidth, inputSize.toFloat() / srcHeight)
    val padX = (inputSize - srcWidth * scale) / 2f
    val padY = (inputSize - srcHeight * scale) / 2f
    return raws.map { r ->
        val left = (r.cx - r.w / 2f - padX) / scale
        val top = (r.cy - r.h / 2f - padY) / scale
        val right = (r.cx + r.w / 2f - padX) / scale
        val bottom = (r.cy + r.h / 2f - padY) / scale
        Detection(
            left = left.coerceIn(0f, srcWidth.toFloat()),
            top = top.coerceIn(0f, srcHeight.toFloat()),
            right = right.coerceIn(0f, srcWidth.toFloat()),
            bottom = bottom.coerceIn(0f, srcHeight.toFloat()),
            conf = r.conf,
        )
    }
}

private fun iou(a: RawDetection, b: RawDetection): Float {
    val aLeft = a.cx - a.w / 2f
    val aTop = a.cy - a.h / 2f
    val aRight = a.cx + a.w / 2f
    val aBottom = a.cy + a.h / 2f
    val bLeft = b.cx - b.w / 2f
    val bTop = b.cy - b.h / 2f
    val bRight = b.cx + b.w / 2f
    val bBottom = b.cy + b.h / 2f

    val interLeft = max(aLeft, bLeft)
    val interTop = max(aTop, bTop)
    val interRight = min(aRight, bRight)
    val interBottom = min(aBottom, bBottom)
    val interW = max(0f, interRight - interLeft)
    val interH = max(0f, interBottom - interTop)
    val inter = interW * interH
    if (inter <= 0f) return 0f

    val areaA = max(0f, aRight - aLeft) * max(0f, aBottom - aTop)
    val areaB = max(0f, bRight - bLeft) * max(0f, bBottom - bTop)
    val union = areaA + areaB - inter
    if (union <= 0f) return 0f
    return inter / union
}
