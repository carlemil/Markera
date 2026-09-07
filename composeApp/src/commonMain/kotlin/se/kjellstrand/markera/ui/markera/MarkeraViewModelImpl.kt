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

    override fun addManualHit(detection: Detection, hit: HitScore) {
        _uiState.update {
            val scores = it.scores + hit
            // Positional, exactly like onHolesDetected: hole i is picker slot i.
            val slot = scores.size - 1
            val pick = if (hit.isInnerTen) SCORE_PICKER_INNER_TEN else hit.ring
            it.copy(
                detections = it.detections + detection,
                scores = scores,
                topScores = if (slot < SCORE_PICKER_COUNT) {
                    it.topScores.toMutableList().apply { this[slot] = pick }
                } else {
                    it.topScores
                },
            )
        }
    }

    override fun moveHit(index: Int, detection: Detection, hit: HitScore) {
        _uiState.update { state ->
            if (index !in state.scores.indices || index !in state.detections.indices) {
                return@update state
            }
            val pick = if (hit.isInnerTen) SCORE_PICKER_INNER_TEN else hit.ring
            state.copy(
                detections = state.detections.toMutableList().apply { this[index] = detection },
                scores = state.scores.toMutableList().apply { this[index] = hit },
                topScores = if (index < SCORE_PICKER_COUNT) {
                    state.topScores.toMutableList().apply { this[index] = pick }
                } else {
                    state.topScores
                },
            )
        }
    }

    override fun removeHit(index: Int) {
        _uiState.update { state ->
            if (index !in state.scores.indices || index !in state.detections.indices) {
                return@update state
            }
            val scores = state.scores.toMutableList().apply { removeAt(index) }
            val topScores = if (index < SCORE_PICKER_COUNT) {
                // Shift the picks (not the scores) left, so user edits in the
                // later slots survive, and fill the freed last slot from the
                // hole that just moved into picker range.
                val entering = scores.getOrNull(SCORE_PICKER_COUNT - 1)
                val pick = when {
                    entering == null -> 0
                    entering.isInnerTen -> SCORE_PICKER_INNER_TEN
                    else -> entering.ring
                }
                state.topScores.toMutableList().apply {
                    removeAt(index)
                    add(pick)
                }
            } else {
                state.topScores
            }
            state.copy(
                detections = state.detections.toMutableList().apply { removeAt(index) },
                scores = scores,
                topScores = topScores,
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
