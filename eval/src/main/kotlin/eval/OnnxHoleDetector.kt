package eval

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.platform.Fp16Conversions
import se.kjellstrand.markera.vision.RawDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Desktop ONNX Runtime stand-in for the app's `HoleDetector.android.kt`.
 * Same tensor contract — `[1, 3, inputSize, inputSize]` CHW values in 0..1,
 * fed as fp16 or fp32 to match the model's declared input type, YOLO export
 * with embedded NMS `[1, 300, 6]` rows (x1, y1, x2, y2, conf, classId) — and
 * the same 0.01 prefilter, so detections match the device pipeline.
 */
class OnnxHoleDetector(modelPath: String, val inputSize: Int) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())
    private val inputIsFp16: Boolean =
        (session.inputInfo.getValue(inputName).info as TensorInfo).type == OnnxJavaType.FLOAT16

    fun detect(inputChw: FloatArray): List<RawDetection> {
        createInputTensor(inputChw).use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                // [1, 300, 6]: each row (x1, y1, x2, y2, conf, classId) in
                // input-tensor pixels; zero-padded rows fall below the prefilter.
                // getFloatBuffer() (unlike getValue()) converts fp16 outputs
                // to floats, so one path serves fp16 and fp32 models.
                val out = (result[0] as OnnxTensor).floatBuffer
                val rows = out.capacity() / 6
                val list = ArrayList<RawDetection>(rows)
                for (i in 0 until rows) {
                    val base = i * 6
                    val conf = out.get(base + 4)
                    if (conf >= PREFILTER_CONFIDENCE) {
                        val x1 = out.get(base)
                        val y1 = out.get(base + 1)
                        val x2 = out.get(base + 2)
                        val y2 = out.get(base + 3)
                        list += RawDetection(
                            cx = (x1 + x2) * 0.5f,
                            cy = (y1 + y2) * 0.5f,
                            w = x2 - x1,
                            h = y2 - y1,
                            conf = conf,
                            cls = out.get(base + 5).toInt(),
                        )
                    }
                }
                return list
            }
        }
    }

    private fun createInputTensor(inputChw: FloatArray): OnnxTensor {
        if (!inputIsFp16) {
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape)
        }
        // ORT 1.19's Java API has no ShortBuffer+type overload, so fp16
        // tensors are built from the raw half-precision bits in a direct
        // ByteBuffer tagged FLOAT16.
        val bytes = ByteBuffer.allocateDirect(inputChw.size * 2).order(ByteOrder.nativeOrder())
        val halves = bytes.asShortBuffer()
        for (v in inputChw) halves.put(Fp16Conversions.floatToFp16(v))
        return OnnxTensor.createTensor(env, bytes, inputShape, OnnxJavaType.FLOAT16)
    }

    override fun close() = session.close()

    private companion object {
        const val PREFILTER_CONFIDENCE = 0.01f
    }
}
