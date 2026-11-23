@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import platform.Foundation.NSData

/**
 * The hole-detection inference call, implemented by the Swift host over ONNX
 * Runtime and handed in at [se.kjellstrand.markera.MainViewController].
 *
 * Kotlin/Native exports this to Objective-C/Swift as a protocol named
 * `HoleModel`, so the host conforms with `class OrtHoleModel: NSObject, HoleModel`.
 */
interface HoleModel {
    /**
     * [input] points at the `[1, 3, inputSize, inputSize]` fp32 tensor,
     * [inputFloats] long; the caller keeps it pinned until this returns, and the
     * host hands it to ONNX Runtime without copying it. The return value is the
     * `[1, 300, 6]` fp32 output as raw bytes, empty on any failure.
     */
    fun run(input: CPointer<FloatVar>, inputFloats: Int, inputSize: Int): NSData

    /**
     * Releases the native session. Never overlaps a [run] (the host serialises
     * them; a native run cannot be aborted), and a later [run] opens it again.
     */
    fun close()
}
