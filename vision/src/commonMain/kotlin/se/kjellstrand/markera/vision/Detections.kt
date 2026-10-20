package se.kjellstrand.markera.vision

// Plain data types for hole detection. Kept separate from HoleDetector.kt so
// the :eval module (plain JVM) can compile them and DetectionPostProcess.kt
// while excluding the expect/actual HoleDetector itself.

/** A model box in input-tensor pixels: centre, size and confidence. */
data class RawDetection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val conf: Float,
)

/** A hole box in source-image pixels. */
data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val conf: Float,
)
