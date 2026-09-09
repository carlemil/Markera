package se.kjellstrand.markera.vision

import platform.Foundation.NSData

/**
 * The hole-detection inference call, implemented by the Swift host over ONNX
 * Runtime and handed in at [se.kjellstrand.markera.MainViewController].
 *
 * Kotlin/Native exports this to Objective-C/Swift as a protocol named
 * `HoleModel`, so the host conforms with `class OrtHoleModel: NSObject, HoleModel`.
 *
 * [input] is the `[1, 3, inputSize, inputSize]` fp32 tensor as raw
 * little-endian bytes; the return value is the `[1, 300, 6]` fp32 output in
 * the same raw form.
 */
interface HoleModel {
    fun run(input: NSData, inputSize: Int): NSData
}
