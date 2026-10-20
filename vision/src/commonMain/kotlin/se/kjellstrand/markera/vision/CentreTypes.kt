package se.kjellstrand.markera.vision

// Plain data types for digit detection and centre estimation. Kept separate
// from DigitDetector.kt so the :eval module (plain JVM) can compile them and
// CentreEstimator.kt while excluding the expect/actual DigitDetector itself.

/** A single recognised digit box, in source-image pixel space. */
data class DigitDetection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** The recognised digit value, 1..9. */
    val value: Int,
    /** Recognition confidence 0..1; 1f when the engine reports none (ML Kit). */
    val conf: Float,
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

/** Estimated true centre of the target, in source-image pixel space. */
data class CentreEstimate(
    val x: Float,
    val y: Float,
    val method: CentreMethod,
    /** Best-fit line through the horizontal digit row, when resolved. */
    val horizontalLine: TargetLine? = null,
    /** Best-fit line through the vertical digit row, when resolved. */
    val verticalLine: TargetLine? = null,
)

/**
 * An infinite line in source-image pixel space: a point on the line plus a
 * unit direction vector.
 */
data class TargetLine(
    val px: Float,
    val py: Float,
    val dx: Float,
    val dy: Float,
)

/** How [estimateCentre] arrived at a centre, for logging/diagnostics. */
enum class CentreMethod {
    /** Intersection of the lines fitted through the two digit rows. */
    LINE_INTERSECTION,

    /** Not enough digits to commit to a centre — caller should draw nothing. */
    NONE,
}
