package se.kjellstrand.markera.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

actual class HoleDetector actual constructor(
    modelBytes: ByteArray,
    inputSize: Int,
) {
    actual val inputSize: Int = inputSize

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> = withContext(Dispatchers.Default) {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape)
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<FloatArray>>
                // YOLOv8 1-class output: [1, 5, N] with channels (cx, cy, w, h, conf).
                val channels = out[0]
                val n = channels[0].size
                val list = ArrayList<RawDetection>(64)
                for (i in 0 until n) {
                    val conf = channels[4][i]
                    if (conf >= PREFILTER_CONFIDENCE) {
                        list += RawDetection(
                            cx = channels[0][i],
                            cy = channels[1][i],
                            w = channels[2][i],
                            h = channels[3][i],
                            conf = conf,
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
