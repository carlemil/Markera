@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package se.kjellstrand.markera.vision

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
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
        val model = holeModel
        if (model == null) {
            println("HoleDetector: no HoleModel bound")
            return emptyList()
        }
        return withContext(Dispatchers.Default) {
            // NSData.create(bytes:length:) copies, so the pin can end here.
            val input = inputChw.usePinned {
                NSData.create(bytes = it.addressOf(0), length = (inputChw.size * 4).toULong())
            }
            val started = TimeSource.Monotonic.markNow()
            val out = model.run(input, inputSize)
            println("HoleDetector: inference took ${started.elapsedNow()}")

            val floats = out.bytes?.reinterpret<FloatVar>() ?: return@withContext emptyList<RawDetection>()
            parseNmsRows((out.length / 4uL).toInt()) { floats[it] }
        }
    }

    actual fun close() {}
}
