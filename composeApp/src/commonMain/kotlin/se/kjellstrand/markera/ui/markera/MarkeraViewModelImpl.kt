package se.kjellstrand.markera.ui.markera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection

class MarkeraViewModelImpl : ViewModel(), MarkeraViewModel {

    private val _uiState = MutableStateFlow(MarkeraUiState())
    override val uiState: StateFlow<MarkeraUiState> = _uiState.asStateFlow()

    override fun onFrameAnalysed(
        detections: List<Detection>,
        digits: List<DigitDetection>,
        centre: CentreEstimate?,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        _uiState.update {
            it.copy(
                detections = detections,
                digits = digits,
                centre = centre,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                isProcessing = false,
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

    override fun setTopScoreAt(index: Int, value: Int) {
        if (index !in 0 until SCORE_PICKER_COUNT) return
        val clamped = value.coerceIn(0, SCORE_PICKER_INNER_TEN)
        _uiState.update {
            val updated = it.topScores.toMutableList().apply { this[index] = clamped }
            it.copy(topScores = updated)
        }
    }
}
