package se.kjellstrand.markera.vision

/**
 * On-device YOLO bullet-hole detector. The Android actual is backed by
 * onnxruntime-android; the iOS actual is currently a compile-only stub
 * until an iOS host app exists and onnxruntime-objc can be wired in.
 *
 * The detector intentionally operates on a pre-normalised CHW float
 * tensor rather than on a platform-specific image type, so the
 * Android/iOS image-handling code stays at the call site and the
 * expect/actual surface stays minimal.
 *
 * The model is loaded from [modelPath] (a plain file on disk) so the
 * runtime can read the ~80 MB model natively instead of via a Java-heap
 * byte array, which OOMs small heaps.
 */
expect class HoleDetector(modelPath: String, inputSize: Int) {

    val inputSize: Int

    /**
     * Run the model on a `[1, 3, inputSize, inputSize]` float tensor laid
     * out in CHW order with values in 0..1. Returns raw boxes in
     * input-tensor coordinates (centre-x, centre-y, width, height) plus
     * confidence and predicted class id. Callers run [filterByConfidence] +
     * [nonMaxSuppression] + [mapToImageSpace] from [DetectionPostProcess] to
     * turn this into drawable [Detection]s.
     */
    suspend fun detect(inputChw: FloatArray): List<RawDetection>

    fun close()
}

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
