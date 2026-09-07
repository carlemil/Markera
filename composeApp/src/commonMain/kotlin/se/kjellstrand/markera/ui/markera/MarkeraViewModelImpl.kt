package se.kjellstrand.markera.ui.markera

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HIT_SCORE_ORDER
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
        _uiState.update {
            it.withHoles(detections, scores).copy(phase = ScanPhase.IDLE, error = null)
        }
    }

    override fun addManualHit(detection: Detection, hit: HitScore) {
        _uiState.update {
            // A series is five shots: past the picker count a tap adds nothing.
            if (it.scores.size >= SCORE_PICKER_COUNT) return@update it
            it.withHoles(it.detections + detection, it.scores + hit)
        }
    }

    override fun moveHit(index: Int, detection: Detection, hit: HitScore): Int {
        if (index !in _uiState.value.scores.indices) return -1
        val state = _uiState.updateAndGet {
            it.withHoles(
                it.detections.toMutableList().apply { this[index] = detection },
                it.scores.toMutableList().apply { this[index] = hit },
            )
        }
        // Re-scoring re-sorts, so the hole the user is dragging has moved in the
        // list; the caller needs to know where to, to keep dragging it.
        return state.scores.indexOfFirst { it === hit }
    }

    override fun removeHit(index: Int) {
        _uiState.update { state ->
            if (index !in state.scores.indices || index !in state.detections.indices) {
                return@update state
            }
            state.withHoles(
                state.detections.toMutableList().apply { removeAt(index) },
                state.scores.toMutableList().apply { removeAt(index) },
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
}

/**
 * Publish holes and scores in score order — the order a fresh detection comes in
 * — and refill the pickers from them, since a score now only ever comes from
 * where its hole sits. Hole `i` stays picker slot `i` (what `withPicks` saves
 * by). Detections without scores (no geometry) are left as the model found them.
 */
private fun MarkeraUiState.withHoles(
    detections: List<Detection>,
    scores: List<HitScore>,
): MarkeraUiState {
    val order = scores.indices.sortedWith(compareBy(HIT_SCORE_ORDER) { scores[it] })
    val sorted = order.map { scores[it] }
    return copy(
        detections = if (detections.size == scores.size) order.map { detections[it] } else detections,
        scores = sorted,
        topScores = List(SCORE_PICKER_COUNT) { i ->
            sorted.getOrNull(i)?.let { if (it.isInnerTen) SCORE_PICKER_INNER_TEN else it.ring } ?: 0
        },
    )
}
