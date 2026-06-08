package se.kjellstrand.markera.ui.markera

import kotlinx.coroutines.flow.StateFlow
import se.kjellstrand.markera.vision.Detection

interface MarkeraViewModel {
    val uiState: StateFlow<MarkeraUiState>

    /** Push the result of one inference pass on a frozen snapshot. */
    fun onFrameAnalysed(detections: List<Detection>, imageWidth: Int, imageHeight: Int)

    /** Clear detection overlay state. */
    fun clearResults()

    fun setProcessing(isProcessing: Boolean)

    fun setError(message: String?)

    /** Update one slot of the top-scores list (user edited a picker). */
    fun setTopScoreAt(index: Int, value: Int)
}
