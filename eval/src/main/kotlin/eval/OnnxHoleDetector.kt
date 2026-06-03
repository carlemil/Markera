package eval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import se.kjellstrand.markera.vision.RawDetection
import java.nio.FloatBuffer

/**
 * Desktop ONNX Runtime stand-in for the app's `HoleDetector.android.kt`.
 * Same tensor contract — `[1, 3, inputSize, inputSize]` CHW floats in 0..1,
 * YOLOv8 1-class output `[1, 5, N]` with channels (cx, cy, w, h, conf) — and
 * the same 0.01 prefilter, so detections match the device pipeline.
 */
class OnnxHoleDetector(modelPath: String, val inputSize: Int) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    fun detect(inputChw: FloatArray): List<RawDetection> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape).use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<FloatArray>>
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
                return list
            }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val PREFILTER_CONFIDENCE = 0.01f
    }
}
