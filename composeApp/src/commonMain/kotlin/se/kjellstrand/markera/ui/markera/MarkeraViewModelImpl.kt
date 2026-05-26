package se.kjellstrand.markera.ui.markera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.TargetCalibration

class MarkeraViewModelImpl : ViewModel(), MarkeraViewModel {

    private val _uiState = MutableStateFlow(MarkeraUiState())
    override val uiState: StateFlow<MarkeraUiState> = _uiState.asStateFlow()

    override fun onFrameAnalysed(detections: List<Detection>, imageWidth: Int, imageHeight: Int) {
        _uiState.update {
            it.copy(
                detections = detections,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                isProcessing = false,
                error = null,
            )
        }
    }

    override fun setCalibration(fresh: TargetCalibration?) {
        if (fresh == null) return
        _uiState.update { it.copy(calibration = fresh) }
    }

    override fun clearResults() {
        _uiState.update {
            it.copy(
                detections = emptyList(),
                imageWidth = 0,
                imageHeight = 0,
                isProcessing = false,
                error = null,
                topScores = List(SCORE_PICKER_COUNT) { 0 },
            )
        }
    }

    override fun setProcessing(isProcessing: Boolean) {
        _uiState.update { it.copy(isProcessing = isProcessing) }
    }

    override fun setError(message: String?) {
        _uiState.update { it.copy(error = message, isProcessing = false) }
    }

    override fun setTopScores(values: List<Int>) {
        val normalised = values
            .map { it.coerceIn(0, SCORE_PICKER_INNER_TEN) }
            .take(SCORE_PICKER_COUNT)
            .let { it + List(SCORE_PICKER_COUNT - it.size) { 0 } }
        _uiState.update { it.copy(topScores = normalised) }
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
