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

            // YOLO export with embedded NMS: [1, 300, 6], each row
            // (x1, y1, x2, y2, confidence, classId) in input-tensor pixels.
            // Unused slots are zero-padded and fall below PREFILTER_CONFIDENCE.
            val floats = out.bytes?.reinterpret<FloatVar>() ?: return@withContext emptyList<RawDetection>()
            val rows = (out.length / 4uL).toInt() / 6
            val list = ArrayList<RawDetection>(rows)
            for (i in 0 until rows) {
                val base = i * 6
                val conf = floats[base + 4]
                if (conf >= PREFILTER_CONFIDENCE) {
                    val x1 = floats[base]
                    val y1 = floats[base + 1]
                    val x2 = floats[base + 2]
                    val y2 = floats[base + 3]
                    list += RawDetection(
                        cx = (x1 + x2) * 0.5f,
                        cy = (y1 + y2) * 0.5f,
                        w = x2 - x1,
                        h = y2 - y1,
                        conf = conf,
                        cls = floats[base + 5].toInt(),
                    )
                }
            }
            list
        }
    }

    actual fun close() {}

    private companion object {
        // Same prefilter as Android: drop obvious noise at the inference
        // boundary; the screen applies the user-facing threshold later.
        const val PREFILTER_CONFIDENCE = 0.01f
    }
}
