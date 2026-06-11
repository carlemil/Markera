package se.kjellstrand.markera.vision

/**
 * On-device OCR detector for the printed digits on a precision-shooting
 * target. The Android actual is backed by ML Kit Text Recognition; the iOS
 * actual is a compile-only stub until an iOS host app exists and Apple Vision
 * can be wired in.
 *
 * Unlike [HoleDetector] (which needs a normalised CHW tensor), OCR runs on the
 * platform's native image, so [detect] takes a [PlatformImage]. The returned
 * boxes are already in that image's pixel space — the same space [Detection]
 * uses — so they need no letterbox inverse to overlay.
 */
expect class DigitDetector() {

    /**
     * Recognise single digit characters '1'..'9' on [image]. Returns one
     * [DigitDetection] per recognised digit, in image pixel space.
     */
    suspend fun detect(image: PlatformImage): List<DigitDetection>

    fun close()
}

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
)

/** How [estimateCentre] arrived at a centre, for logging/diagnostics. */
enum class CentreMethod {
    /** Midpoint of the two innermost 9s on at least one axis. */
    INNER_NINES,

    /** A 9 was missing on an axis; symmetric digit pairs were used instead. */
    LINE_FIT_FALLBACK,

    /** Not enough digits to commit to a centre — caller should draw nothing. */
    NONE,
}
