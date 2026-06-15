package se.kjellstrand.markera.ui.markera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore

class MarkeraViewModelImpl : ViewModel(), MarkeraViewModel {

    private val _uiState = MutableStateFlow(MarkeraUiState())
    override val uiState: StateFlow<MarkeraUiState> = _uiState.asStateFlow()

    override fun startDetect() {
        _uiState.update {
            it.copy(
                detections = emptyList(),
                digits = emptyList(),
                centre = null,
                ring = null,
                scores = emptyList(),
                imageWidth = 0,
                imageHeight = 0,
                phase = ScanPhase.GEOMETRY,
                error = null,
            )
        }
    }

    override fun onGeometryReady(
        digits: List<DigitDetection>,
        centre: CentreEstimate?,
        ring: FittedEllipse?,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        _uiState.update {
            it.copy(
                digits = digits,
                centre = centre,
                ring = ring,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                detections = emptyList(),
                phase = ScanPhase.HOLES,
                error = null,
            )
        }
    }

    override fun onHolesDetected(detections: List<Detection>, scores: List<HitScore>) {
        // Auto-fill the pickers from the top hits (inner-X -> index 11, else the
        // ring value), padded to the picker count. Still user-editable afterwards.
        val picks = scores.take(SCORE_PICKER_COUNT)
            .map { if (it.isInnerTen) SCORE_PICKER_INNER_TEN else it.ring }
        val topScores = List(SCORE_PICKER_COUNT) { i -> picks.getOrElse(i) { 0 } }
        _uiState.update {
            it.copy(
                detections = detections,
                scores = scores,
                topScores = topScores,
                phase = ScanPhase.IDLE,
                error = null,
            )
        }
    }

    override fun clearResults() {
        _uiState.update {
            it.copy(
                detections = emptyList(),
                digits = emptyList(),
                centre = null,
                ring = null,
                scores = emptyList(),
                imageWidth = 0,
                imageHeight = 0,
                phase = ScanPhase.IDLE,
                error = null,
                topScores = List(SCORE_PICKER_COUNT) { 0 },
            )
        }
    }

    override fun setError(message: String?) {
        _uiState.update { it.copy(error = message, phase = ScanPhase.IDLE) }
    }

    override fun setTopScoreAt(index: Int, value: Int) {
        if (index !in 0 until SCORE_PICKER_COUNT) return
        val clamped = value.coerceIn(0, SCORE_PICKER_INNER_TEN)
        _uiState.update {
            val updated = it.topScores.toMutableList().apply { this[index] = clamped }
            it.copy(topScores = updated)
        }
    }
}
