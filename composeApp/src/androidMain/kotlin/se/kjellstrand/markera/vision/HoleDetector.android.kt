package se.kjellstrand.markera.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import android.util.Log
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
    // keeping the ~40 MB model off the Java heap (readBytes() of the asset
    // peaked at 2-3x the model size and OOMed small heaps).
    private val session: OrtSession = buildSessionOptions().use { env.createSession(modelPath, it) }
    private val inputName: String = session.inputNames.first()
    private val inputShape: LongArray = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

    actual suspend fun detect(inputChw: FloatArray): List<RawDetection> = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        // The model's inputs and outputs are fp32 (scripts/cast_model_io.py).
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputChw), inputShape)
        tensor.use { t ->
            session.run(mapOf(inputName to t)).use { result ->
                Log.d(TAG, "inference took ${SystemClock.elapsedRealtime() - started} ms")
                val out = (result[0] as OnnxTensor).floatBuffer
                parseNmsRows(out.capacity()) { out.get(it) }
            }
        }
    }

    actual fun close() {
        session.close()
    }

    private companion object {
        const val TAG = "HoleDetector"

        // Default CPU execution provider. XNNPACK was faster on paper but
        // caused intermittent native crashes in OrtSession.run on-device
        // (libonnxruntime, fp16 path); the plain CPU EP is stable. Intra-op
        // pool capped at 4 threads to stay on the big cores of big.LITTLE SoCs.
        fun buildSessionOptions(): OrtSession.SessionOptions {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            return opts
        }
    }
}
