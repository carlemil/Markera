package se.kjellstrand.markera.ui.markera

/** The camera still's square is about this; a bigger imported photo is brought down to it. */
internal const val IMPORT_SQUARE_SIDE = 3000

/** The largest power-of-two `inSampleSize` that keeps the shorter side at least [target]. */
internal fun importSampleSize(shorter: Int, target: Int = IMPORT_SQUARE_SIDE): Int {
    var sample = 1
    while (shorter / (sample * 2) >= target) sample *= 2
    return sample
}

/**
 * The size a decoded [width] x [height] image is scaled to: the shorter side exactly [target],
 * the aspect kept (the longer side rounded). An image already at or below [target] is left as is,
 * so the short side never drops below it.
 */
internal fun importScaledSize(width: Int, height: Int, target: Int = IMPORT_SQUARE_SIDE): Pair<Int, Int> {
    val shorter = minOf(width, height)
    if (shorter <= target) return width to height
    fun scaled(side: Int) = if (side == shorter) target else ((side.toLong() * target + shorter / 2) / shorter).toInt()
    return scaled(width) to scaled(height)
}
