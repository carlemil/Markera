package se.kjellstrand.markera.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tuning knobs for [estimateCentre]. */
data class CentreConfig(
    /** A row must keep at least this many digits to be trusted. */
    val minDigitsPerRow: Int = 3,
    /** Perpendicular outlier band, as a fraction of the row's length. */
    val rowBandFraction: Float = 0.2f,
    /** Digits below this confidence are ignored. */
    val minConf: Float = 0.3f,
    /**
     * Only digits whose value falls in this range are used. The outer 6..9 are
     * printed at fixed positions along each row and align cleanly; the inner
     * 1..5 sit closer to the centre and may not line up with them, so they are
     * excluded by default to keep the fitted rows straight.
     */
    val digitValues: IntRange = 6..9,
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
    imageWidth: Int = 0,
    imageHeight: Int = 0,
    config: CentreConfig = CentreConfig(),
): CentreEstimate {
    val usable = digits.filter { it.value in config.digitValues && it.conf >= config.minConf }
    // Two digits per row is the absolute floor (the relaxed straddle case in
    // resolveRow); a normal fit wants minDigitsPerRow on each.
    if (usable.size < 2 * RELAXED_ROW_FLOOR) {
        return CentreEstimate(0f, 0f, CentreMethod.NONE)
    }

    val (horizontalRaw, verticalRaw) = splitRows(usable)
    val horizontal = resolveRow(horizontalRaw, AXIS_X, imageWidth / 2f, config)
    val vertical = resolveRow(verticalRaw, AXIS_Y, imageHeight / 2f, config)
    if (horizontal == null || vertical == null) {
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

/** Fewest digits a row may have and still yield a line (the straddle case). */
private const val RELAXED_ROW_FLOOR = 2

private data class Pt(val x: Float, val y: Float)

private val AXIS_X = Pt(1f, 0f)
private val AXIS_Y = Pt(0f, 1f)

/**
 * Number of usable digits on the (horizontal, vertical) rows, by the same
 * filter and orientation split [estimateCentre] uses. Exposed for diagnostics.
 */
fun rowDigitCounts(
    digits: List<DigitDetection>,
    config: CentreConfig = CentreConfig(),
): Pair<Int, Int> {
    val usable = digits.filter { it.value in config.digitValues && it.conf >= config.minConf }
    if (usable.isEmpty()) return 0 to 0
    val (h, v) = splitRows(usable)
    return h.size to v.size
}

/**
 * Orientation split: digits lying more along x belong to the horizontal row,
 * those more along y to the vertical row. Robust to row tilt up to ~45°. The
 * split pivots on the median (not the mean) so a single stray misread far from
 * the cross can't drag the pivot and flip a digit near the centre to the wrong
 * row.
 */
private fun splitRows(
    usable: List<DigitDetection>,
): Pair<List<DigitDetection>, List<DigitDetection>> {
    val mid = medianCentre(usable)
    val horizontal = usable.filter { abs(it.cx - mid.x) >= abs(it.cy - mid.y) }
    val vertical = usable.filter { abs(it.cy - mid.y) > abs(it.cx - mid.x) }
    return horizontal to vertical
}

private fun medianCentre(row: List<DigitDetection>): Pt =
    Pt(median(row.map { it.cx }), median(row.map { it.cy }))

private fun median(values: List<Float>): Float {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
}

/**
 * The digits to fit a row line through, or null if the row is too sparse.
 * A full row (>= [CentreConfig.minDigitsPerRow] after outlier removal) is used
 * directly. As a fallback, a bare pair of digits is accepted when they sit on
 * opposite sides of the image centre ([imageMid]) along this [axis]: the centre
 * then lies between them (interpolation) instead of beyond them, so the line is
 * far less sensitive to their pixel noise than a same-side pair would be.
 */
private fun resolveRow(
    raw: List<DigitDetection>,
    axis: Pt,
    imageMid: Float,
    config: CentreConfig,
): List<DigitDetection>? {
    val cleaned = cleanRow(raw, axis, config)
    if (cleaned.size >= config.minDigitsPerRow) return cleaned
    if (raw.size == RELAXED_ROW_FLOOR && imageMid > 0f) {
        val a = coordAlong(raw[0], axis)
        val b = coordAlong(raw[1], axis)
        if (min(a, b) < imageMid && max(a, b) > imageMid) return raw
    }
    return null
}

/** Position of [d]'s centre along [axis] (its x for AXIS_X, its y for AXIS_Y). */
private fun coordAlong(d: DigitDetection, axis: Pt): Float =
    d.cx * axis.x + d.cy * axis.y

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
