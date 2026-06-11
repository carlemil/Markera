package se.kjellstrand.markera.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

actual class HoleDetector actual constructor(
    modelPath: String,
    inputSize: Int,
) {
    actual val inputSize: Int = inputSize

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Loading by path lets the native runtime read the model file directly,
    // keeping the ~80 MB model off the Java heap (readBytes() of the asset
    // peaked at 2-3x the model size and OOMed small heaps).
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> = withContext(Dispatchers.Default) {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape)
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<FloatArray>>
                // YOLOv8 export with embedded NMS: [1, 300, 6], each row
                // (x1, y1, x2, y2, confidence, classId) in input-tensor pixels.
                // Unused slots are zero-padded and fall below PREFILTER_CONFIDENCE.
                val dets = out[0]
                val list = ArrayList<RawDetection>(dets.size)
                for (det in dets) {
                    val conf = det[4]
                    if (conf >= PREFILTER_CONFIDENCE) {
                        val x1 = det[0]
                        val y1 = det[1]
                        val x2 = det[2]
                        val y2 = det[3]
                        list += RawDetection(
                            cx = (x1 + x2) * 0.5f,
                            cy = (y1 + y2) * 0.5f,
                            w = x2 - x1,
                            h = y2 - y1,
                            conf = conf,
                            cls = det[5].toInt(),
                        )
                    }
                }
                list
            }
        }
    }

    actual fun close() {
        session.close()
    }

    private companion object {
        // Drop obvious noise at the inference boundary so a frame doesn't
        // allocate 8400 RawDetection objects. The screen still applies a
        // higher user-facing threshold via filterByConfidence().
        const val PREFILTER_CONFIDENCE = 0.01f
    }
}
