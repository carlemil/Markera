package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse

data class MarkeraUiState(
    val detections: List<Detection> = emptyList(),
    val digits: List<DigitDetection> = emptyList(),
    val centre: CentreEstimate? = null,
    /** Digit-predicted 6/7 boundary, snapped to the black->white edge. */
    val ring: FittedEllipse? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val phase: ScanPhase = ScanPhase.IDLE,
    val error: String? = null,
    /**
     * Top-hit scores in descending order. Each value is a picker
     * index in [0..11], where 0..10 are ring numbers and 11 represents
     * the inner-ten ("X"). Defaults to zeros so the pickers always have
     * something to display.
     */
    val topScores: List<Int> = List(SCORE_PICKER_COUNT) { 0 },
)

/**
 * The two-phase detect pipeline. [GEOMETRY] (digit OCR -> centre -> 6/7 ring)
 * runs first with no spinner; once it publishes, [HOLES] runs the slow ONNX
 * pass with the spinner orbiting the detected centre. [IDLE] otherwise.
 */
enum class ScanPhase { IDLE, GEOMETRY, HOLES }

const val SCORE_PICKER_COUNT = 5
const val SCORE_PICKER_INNER_TEN = 11
val SCORE_PICKER_LABELS: List<String> =
    (0..10).map { it.toString() } + "X"
