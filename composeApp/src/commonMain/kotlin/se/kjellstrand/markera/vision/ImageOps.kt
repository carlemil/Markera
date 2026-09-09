package se.kjellstrand.markera.vision

import kotlin.math.min

/**
 * Pack row-major `0xAARRGGBB` pixels into 8-bit luma (Rec. 601 weights), the
 * input the edge/ellipse routines expect. One byte per pixel, no padding.
 */
fun lumaFromArgb(argb: IntArray): ByteArray {
    val out = ByteArray(argb.size)
    for (i in argb.indices) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return out
}

/** The scaled size a [width] x [height] image letterboxes to inside [inputSize]. */
fun letterboxDims(width: Int, height: Int, inputSize: Int): Pair<Int, Int> {
    val scale = min(inputSize.toFloat() / width, inputSize.toFloat() / height)
    return Pair(
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * Centre-pad an already-scaled [newW] x [newH] ARGB block into the
 * `inputSize`² CHW float tensor, normalised to 0..1 with a black (0f) border.
 * Matches the inverse transform `DetectionPostProcess.mapToImageSpace` does.
 */
fun letterboxChw(argbScaled: IntArray, newW: Int, newH: Int, inputSize: Int): FloatArray {
    val padX = (inputSize - newW) / 2
    val padY = (inputSize - newH) / 2
    val plane = inputSize * inputSize
    val chw = FloatArray(3 * plane)
    for (y in 0 until newH) {
        val src = y * newW
        val dst = (y + padY) * inputSize + padX
        for (x in 0 until newW) {
            val p = argbScaled[src + x]
            val i = dst + x
            chw[i] = ((p shr 16) and 0xFF) / 255f
            chw[i + plane] = ((p shr 8) and 0xFF) / 255f
            chw[i + 2 * plane] = (p and 0xFF) / 255f
        }
    }
    return chw
}
