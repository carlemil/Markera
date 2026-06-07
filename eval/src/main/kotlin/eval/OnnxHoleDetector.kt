package eval

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import se.kjellstrand.markera.vision.RawDetection
import java.nio.FloatBuffer

/**
 * Desktop ONNX Runtime stand-in for the app's `HoleDetector.android.kt`.
 * Same tensor contract — `[1, 3, inputSize, inputSize]` CHW floats in 0..1,
 * YOLOv8 export with embedded NMS `[1, 300, 6]` rows (x1, y1, x2, y2, conf,
 * classId) — and the same 0.01 prefilter, so detections match the device
 * pipeline.
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
                // [1, 300, 6]: each row (x1, y1, x2, y2, conf, classId) in
                // input-tensor pixels; zero-padded rows fall below the prefilter.
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
                return list
            }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val PREFILTER_CONFIDENCE = 0.01f
    }
}
