package se.kjellstrand.markera.vision

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.platform.Fp16Conversions
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

actual class HoleDetector actual constructor(
    modelPath: String,
    inputSize: Int,
) {
    actual val inputSize: Int = inputSize

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // Loading by path lets the native runtime read the model file directly,
    // keeping the ~40 MB model off the Java heap (readBytes() of the asset
    // peaked at 2-3x the model size and OOMed small heaps).
    private val session: OrtSession = env.createSession(modelPath, buildSessionOptions())
    private val inputName: String = session.inputNames.first()
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    // The exported model may declare its input as fp32 or fp16 (the current
    // export is FP16); the tensor we feed must match the graph exactly.
    private val inputIsFp16: Boolean =
        (session.inputInfo.getValue(inputName).info as TensorInfo).type == OnnxJavaType.FLOAT16

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        val tensor = createInputTensor(inputChw)
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                Log.d(TAG, "inference took ${SystemClock.elapsedRealtime() - started} ms")
                // YOLO export with embedded NMS: [1, 300, 6], each row
                // (x1, y1, x2, y2, confidence, classId) in input-tensor pixels.
                // Unused slots are zero-padded and fall below PREFILTER_CONFIDENCE.
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
                list
            }
        }
    }

    actual fun close() {
        session.close()
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

    private companion object {
        const val TAG = "HoleDetector"

        // Drop obvious noise at the inference boundary so a frame doesn't
        // allocate 8400 RawDetection objects. The screen still applies a
        // higher user-facing threshold via filterByConfidence().
        const val PREFILTER_CONFIDENCE = 0.01f

        // XNNPACK runs fp32 convolutions noticeably faster than the default
        // CPU EP on ARM. It brings its own thread pool, so the session's
        // intra-op pool is shrunk to one thread to avoid oversubscription.
        // Capped at 4 threads to stay on the big cores of big.LITTLE SoCs.
        fun buildSessionOptions(): OrtSession.SessionOptions {
            val opts = OrtSession.SessionOptions()
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                opts.setIntraOpNumThreads(1)
            } catch (e: OrtException) {
                Log.w(TAG, "XNNPACK unavailable, using default CPU provider", e)
            }
            return opts
        }
    }
}
