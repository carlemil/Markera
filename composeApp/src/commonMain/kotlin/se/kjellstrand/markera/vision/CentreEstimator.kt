package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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
 * vertical row, crossing at the true centre. Digits are split into the two
 * rows by orientation about their overall centroid, perpendicular outliers are
 * dropped, a best-fit line is drawn through each row, and the intersection of
 * the two lines is the centre. Both fitted lines are returned so the UI can
 * draw them.
 *
 * All coordinates are in source-image pixels (the same space as [Detection]).
 */
fun estimateCentre(
    digits: List<DigitDetection>,
    config: CentreConfig = CentreConfig(),
): CentreEstimate {
    val usable = digits.filter { it.value in 1..9 && it.conf >= config.minConf }
    if (usable.size < 2 * config.minDigitsPerRow) {
        return CentreEstimate(0f, 0f, CentreMethod.NONE)
    }

    val centroid = centroidOf(usable)
    // Orientation split: digits lying more along x belong to the horizontal
    // row, those more along y to the vertical row. Robust to row tilt up to ~45°.
    val horizontalRaw = usable.filter { abs(it.cx - centroid.x) >= abs(it.cy - centroid.y) }
    val verticalRaw = usable.filter { abs(it.cy - centroid.y) > abs(it.cx - centroid.x) }

    val horizontal = cleanRow(horizontalRaw, AXIS_X, config)
    val vertical = cleanRow(verticalRaw, AXIS_Y, config)
    if (horizontal.size < config.minDigitsPerRow || vertical.size < config.minDigitsPerRow) {
        return CentreEstimate(0f, 0f, CentreMethod.NONE)
    }

    val hLine = fitLine(horizontal)
    val vLine = fitLine(vertical)

    // 2-D cross product of the unit directions = sin of the angle between the
    // lines; refuse a near-parallel pair, whose intersection is unstable.
    val cross = hLine.dx * vLine.dy - hLine.dy * vLine.dx
    if (abs(cross) < MIN_INTERSECTION_SIN) {
        return CentreEstimate(0f, 0f, CentreMethod.NONE)
    }
    val t = ((vLine.px - hLine.px) * vLine.dy - (vLine.py - hLine.py) * vLine.dx) / cross
    return CentreEstimate(
        x = hLine.px + t * hLine.dx,
        y = hLine.py + t * hLine.dy,
        method = CentreMethod.LINE_INTERSECTION,
        horizontalLine = hLine,
        verticalLine = vLine,
    )
}

/** Lines closer than ~10° to parallel are rejected. */
private const val MIN_INTERSECTION_SIN = 0.17f

private data class Pt(val x: Float, val y: Float)

private val AXIS_X = Pt(1f, 0f)
private val AXIS_Y = Pt(0f, 1f)

/**
 * Drop digits whose perpendicular offset from the row's [axis] line is large.
 * A fixed axis (rather than the fitted line) keeps a stray off-row misread
 * from tilting the row and surviving its own outlier check.
 */
private fun cleanRow(row: List<DigitDetection>, axis: Pt, config: CentreConfig): List<DigitDetection> {
    if (row.size < 2) return row
    val c = centroidOf(row)
    val span = row.map { proj(it, c, axis) }.let { it.max() - it.min() }
    if (span <= 0f) return row
    val band = config.rowBandFraction * span
    return row.filter { perpDist(it, c, axis) <= band }
}

/**
 * Total-least-squares line through the row's digit centres: passes through the
 * centroid along the principal (largest-variance) axis.
 */
private fun fitLine(row: List<DigitDetection>): TargetLine {
    val c = centroidOf(row)
    var sxx = 0.0
    var syy = 0.0
    var sxy = 0.0
    for (d in row) {
        val dx = (d.cx - c.x).toDouble()
        val dy = (d.cy - c.y).toDouble()
        sxx += dx * dx
        syy += dy * dy
        sxy += dx * dy
    }
    val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
    return TargetLine(c.x, c.y, cos(theta).toFloat(), sin(theta).toFloat())
}

/** Signed position of [d] along [axis], relative to centroid [c]. */
private fun proj(d: DigitDetection, c: Pt, axis: Pt): Float =
    (d.cx - c.x) * axis.x + (d.cy - c.y) * axis.y

/** Distance of [d] from the [axis] line through [c] (along the axis normal). */
private fun perpDist(d: DigitDetection, c: Pt, axis: Pt): Float =
    abs((d.cx - c.x) * (-axis.y) + (d.cy - c.y) * axis.x)

private fun centroidOf(row: List<DigitDetection>): Pt =
    Pt(row.map { it.cx }.average().toFloat(), row.map { it.cy }.average().toFloat())
