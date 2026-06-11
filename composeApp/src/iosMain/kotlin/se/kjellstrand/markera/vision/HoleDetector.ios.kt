package se.kjellstrand.markera.vision

// TODO: swap this stub for an onnxruntime-objc-backed implementation once
// an iOS host app exists. The Kotlin/Native binding can be generated via a
// CocoaPods integration (`pod "onnxruntime-objc"`) or by checking in the
// pre-built framework and adding it to `binaries.framework { ... }`.
// The shape of detect() is identical to Android — only the inference glue
// changes.
actual class HoleDetector actual constructor(
    @Suppress("UNUSED_PARAMETER") modelPath: String,
    inputSize: Int,
) {
    actual val inputSize: Int = inputSize

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> = emptyList()

    actual fun close() {}
}
