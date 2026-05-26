package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.TargetCalibration

data class MarkeraUiState(
    val detections: List<Detection> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val calibration: TargetCalibration? = null,
    val isProcessing: Boolean = false,
    val error: String? = null,
    /**
     * Top-hit scores in descending order. Each value is a picker
     * index in [0..11], where 0..10 are ring numbers and 11 represents
     * the inner-ten ("X"). Defaults to zeros so the pickers always have
     * something to display.
     */
    val topScores: List<Int> = List(SCORE_PICKER_COUNT) { 0 },
)

const val SCORE_PICKER_COUNT = 6
const val SCORE_PICKER_INNER_TEN = 11
val SCORE_PICKER_LABELS: List<String> =
    (0..10).map { it.toString() } + "X"
