@file:OptIn(ExperimentalForeignApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Bound once from `MainViewController`; a top-level var beats threading the
 * bridge through common code for the single consumer it has.
 */
var holeModel: HoleModel? = null

actual class HoleDetector actual constructor(
    @Suppress("UNUSED_PARAMETER") modelPath: String,
    inputSize: Int,
) {
    actual val inputSize: Int = inputSize

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> {
        // Throw rather than return nothing, so the scan shows its inference error.
        val model = holeModel ?: error("no HoleModel bound")
        require(inputChw.size == 3 * inputSize * inputSize) {
            "input is ${inputChw.size} floats, want 3x${inputSize}x$inputSize"
        }
        return withContext(Dispatchers.Default) {
            val started = TimeSource.Monotonic.markNow()
            // ORT reads the pinned array in place (no ~28 MB copy), so the pin
            // must outlive the whole native run.
            val out = inputChw.usePinned { model.run(it.addressOf(0), inputChw.size, inputSize) }
            println("HoleDetector: inference took ${started.elapsedNow()}")
            // The Swift side returns an empty Data() on any failure.
            if (out.length == 0uL) error("hole model failed")

            val floats = out.bytes?.reinterpret<FloatVar>() ?: error("hole model failed")
            parseNmsRows((out.length / 4uL).toInt()) { floats[it] }
        }
    }

    /**
     * Releases the host's ORT session. The model is shared by the process, so a
     * later detector (the controller is rebuilt when the tree is re-keyed) reopens
     * it on its first run. [TargetScanController] only closes once no scan is in
     * flight, and the host also queues the release behind any running inference.
     */
    actual fun close() {
        holeModel?.close()
    }
}
