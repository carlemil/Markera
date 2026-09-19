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
     * confidence, read by [parseNmsRows]. Callers run [postProcess] to turn
     * this into drawable [Detection]s.
     */
    suspend fun detect(inputChw: FloatArray): List<RawDetection>

    fun close()
}
