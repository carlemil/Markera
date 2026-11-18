package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.MODEL_INPUT_SIZE
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.RawDetection

/**
 * The two platform detectors [TargetScanController] drives: digit OCR and the hole
 * model. Everything between them (centre, 6/7 ring, scoring) is the shared pipeline,
 * so a UI test swaps only these two and still scans for real.
 */
interface ScanEngine {
    /** The hole model's square input side, see [HoleDetector.inputSize]. */
    val inputSize: Int
    suspend fun digits(image: PlatformImage): List<DigitDetection>
    suspend fun holes(inputChw: FloatArray): List<RawDetection>
    fun close()
}

/** The device's own detectors: ML Kit / Vision OCR and the ONNX hole model at [modelPath]. */
class DeviceScanEngine(modelPath: String) : ScanEngine {
    private val holeDetector = HoleDetector(modelPath, inputSize = MODEL_INPUT_SIZE)
    private val digitDetector = DigitDetector()
    override val inputSize: Int get() = holeDetector.inputSize
    override suspend fun digits(image: PlatformImage) = digitDetector.detect(image)
    override suspend fun holes(inputChw: FloatArray) = holeDetector.detect(inputChw)
    override fun close() {
        holeDetector.close()
        digitDetector.close()
    }
}
