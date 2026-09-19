package eval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import se.kjellstrand.markera.vision.RawDetection
import se.kjellstrand.markera.vision.parseNmsRows
import java.nio.FloatBuffer

/**
 * Desktop ONNX Runtime stand-in for the app's `HoleDetector.android.kt`.
 * Same tensor contract — `[1, 3, inputSize, inputSize]` fp32 CHW values in
 * 0..1 in, the embedded-NMS `[1, 300, 6]` out — read by the same
 * [parseNmsRows], so detections match the device pipeline.
 */
class OnnxHoleDetector(modelPath: String, val inputSize: Int) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    fun detect(inputChw: FloatArray): List<RawDetection> =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape).use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                parseNmsRows(out.capacity()) { out.get(it) }
            }
        }

    override fun close() = session.close()
}
