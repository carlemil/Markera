package se.kjellstrand.markera.vision

/**
 * Re-declared here for the eval harness because the original
 * [RawDetection]/[Detection] live in HoleDetector.kt, which is an
 * expect/actual file and is excluded from this JVM module's sources.
 * Kept byte-for-byte identical to the commonMain definitions so the shared
 * DetectionPostProcess/HitScoring code compiles unchanged against them.
 */
data class RawDetection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val conf: Float,
    /** Predicted class index (0..11: Hole 0–10, Hole X). 0 when unknown. */
    val cls: Int = 0,
)

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val conf: Float,
)
