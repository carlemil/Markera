package se.kjellstrand.markera.vision

import kotlin.math.abs

/** Tuning knobs for [estimateCentre]. */
data class CentreConfig(
    /** A row must keep at least this many digits to be trusted. */
    val minDigitsPerRow: Int = 3,
    /** Perpendicular outlier band, as a fraction of the row's length. */
    val rowBandFraction: Float = 0.2f,
    /** Digits below this confidence are ignored. */
    val minConf: Float = 0.3f,
)

/**
 * Estimate the true centre of a precision target from recognised digit boxes.
 *
 * Targets print 1..9 then 9..1 along a horizontal row and the same along a
 * vertical row, with the true centre between the two innermost 9s on each axis.
 *
 * Digits are split into a horizontal and a vertical row by orientation about
 * their overall centroid, perpendicular outliers are dropped, and each row
 * yields a 2-D centre point: preferably the midpoint of its two innermost 9s,
 * otherwise the mean of its symmetric equal-value pair midpoints. The returned
 * centre is the average of the available per-row points. Midpoints are
 * rotation-invariant, so a tilted target still resolves exactly.
 *
 * All coordinates are in source-image pixels (the same space as [Detection]).
 */
fun estimateCentre(
    digits: List<DigitDetection>,
    config: CentreConfig = CentreConfig(),
): CentreEstimate {
    val usable = digits.filter { it.value in 1..9 && it.conf >= config.minConf }
    if (usable.size < config.minDigitsPerRow) {
        return CentreEstimate(0f, 0f, CentreMethod.NONE)
    }

    val centroid = centroidOf(usable)
    // Orientation split: digits lying more along x belong to the horizontal
    // row, those more along y to the vertical row. Robust to row tilt up to ~45°.
    val horizontalRaw = usable.filter { abs(it.cx - centroid.x) >= abs(it.cy - centroid.y) }
    val verticalRaw = usable.filter { abs(it.cy - centroid.y) > abs(it.cx - centroid.x) }

    val horizontal = cleanRow(horizontalRaw, AXIS_X, config)
    val vertical = cleanRow(verticalRaw, AXIS_Y, config)

    val rows = listOfNotNull(
        rowCentre(horizontal, AXIS_X, config),
        rowCentre(vertical, AXIS_Y, config),
    )
    if (rows.isEmpty()) return CentreEstimate(0f, 0f, CentreMethod.NONE)

    val x = rows.map { it.centre.x }.average().toFloat()
    val y = rows.map { it.centre.y }.average().toFloat()
    val method =
        if (rows.all { it.method == RowMethod.INNER_NINES }) CentreMethod.INNER_NINES
        else CentreMethod.LINE_FIT_FALLBACK
    return CentreEstimate(x, y, method)
}

private data class Pt(val x: Float, val y: Float)

private val AXIS_X = Pt(1f, 0f)
private val AXIS_Y = Pt(0f, 1f)

private enum class RowMethod { INNER_NINES, FALLBACK }

private data class RowResult(val centre: Pt, val method: RowMethod)

/**
 * Drop digits whose perpendicular offset from the row's [axis] line is large.
 * A fixed axis (rather than a PCA fit) keeps a stray off-row misread from
 * tilting the row and corrupting the inner-9 left/right split.
 */
private fun cleanRow(row: List<DigitDetection>, axis: Pt, config: CentreConfig): List<DigitDetection> {
    if (row.size < 2) return row
    val c = centroidOf(row)
    val span = row.map { proj(it, c, axis) }.let { it.max() - it.min() }
    if (span <= 0f) return row
    val band = config.rowBandFraction * span
    return row.filter { perpDist(it, c, axis) <= band }
}

private fun rowCentre(row: List<DigitDetection>, axis: Pt, config: CentreConfig): RowResult? {
    if (row.size < config.minDigitsPerRow) return null
    innerNinesCentre(row, axis)?.let { return RowResult(it, RowMethod.INNER_NINES) }
    symmetricPairCentre(row, axis)?.let { return RowResult(it, RowMethod.FALLBACK) }
    return null
}

/** Midpoint of the two 9s nearest the row centre (one on each side of it). */
private fun innerNinesCentre(row: List<DigitDetection>, axis: Pt): Pt? {
    val c = centroidOf(row)
    val nines = row.filter { it.value == 9 }
    val left = nines.filter { proj(it, c, axis) < 0f }.maxByOrNull { proj(it, c, axis) }
    val right = nines.filter { proj(it, c, axis) >= 0f }.minByOrNull { proj(it, c, axis) }
    if (left == null || right == null) return null
    return Pt((left.cx + right.cx) / 2f, (left.cy + right.cy) / 2f)
}

/** Mean of the midpoints of each symmetric equal-value pair (the two 1s, two 2s, …). */
private fun symmetricPairCentre(row: List<DigitDetection>, axis: Pt): Pt? {
    val c = centroidOf(row)
    val mids = mutableListOf<Pt>()
    for (v in 1..9) {
        val group = row.filter { it.value == v }
        if (group.size < 2) continue
        val left = group.filter { proj(it, c, axis) < 0f }.maxByOrNull { proj(it, c, axis) }
        val right = group.filter { proj(it, c, axis) >= 0f }.minByOrNull { proj(it, c, axis) }
        if (left != null && right != null) {
            mids.add(Pt((left.cx + right.cx) / 2f, (left.cy + right.cy) / 2f))
        }
    }
    if (mids.isEmpty()) return null
    return Pt(mids.map { it.x }.average().toFloat(), mids.map { it.y }.average().toFloat())
}

/** Signed position of [d] along [axis], relative to centroid [c]. */
private fun proj(d: DigitDetection, c: Pt, axis: Pt): Float =
    (d.cx - c.x) * axis.x + (d.cy - c.y) * axis.y

/** Distance of [d] from the [axis] line through [c] (along the axis normal). */
private fun perpDist(d: DigitDetection, c: Pt, axis: Pt): Float =
    abs((d.cx - c.x) * (-axis.y) + (d.cy - c.y) * axis.x)

private fun centroidOf(row: List<DigitDetection>): Pt =
    Pt(row.map { it.cx }.average().toFloat(), row.map { it.cy }.average().toFloat())
