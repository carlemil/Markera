package se.kjellstrand.markera.ui.markera

import kotlinx.coroutines.flow.StateFlow
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.TargetCalibration

interface MarkeraViewModel {
    val uiState: StateFlow<MarkeraUiState>

    /** Push the result of one inference pass on a frozen snapshot. */
    fun onFrameAnalysed(detections: List<Detection>, imageWidth: Int, imageHeight: Int)

    /**
     * Update the cached calibration. A non-null value replaces the
     * stored one; a null value keeps the last good calibration so the
     * overlay and scoring don't snap back to defaults the moment a
     * single noisy frame fails to detect the 7-ring.
     */
    fun setCalibration(fresh: TargetCalibration?)

    /** Clear detection overlay state (preserves cached calibration). */
    fun clearResults()

    fun setProcessing(isProcessing: Boolean)

    fun setError(message: String?)

    /** Replace the 5-slot top-scores list (each value 0..11; 11 == X). */
    fun setTopScores(values: List<Int>)

    /** Update one slot of the top-scores list (e.g. user edited a picker). */
    fun setTopScoreAt(index: Int, value: Int)
}
