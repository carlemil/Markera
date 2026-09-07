package se.kjellstrand.markera.ui.markera

import kotlinx.coroutines.flow.StateFlow
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore

interface MarkeraViewModel {
    val uiState: StateFlow<MarkeraUiState>

    /** Begin a detect pass: clear the stale overlay and enter the geometry phase. */
    fun startDetect()

    /**
     * Phase 1 done: publish the centre and 6/7 ring (and the digits behind
     * them) and enter the holes phase, where the spinner runs.
     */
    fun onGeometryReady(
        digits: List<DigitDetection>,
        centre: CentreEstimate?,
        ring: FittedEllipse?,
        imageWidth: Int,
        imageHeight: Int,
    )

    /**
     * Phase 2 done: publish the holes and their [scores], auto-fill the score
     * pickers from the top hits, and return to idle.
     */
    fun onHolesDetected(detections: List<Detection>, scores: List<HitScore>)

    /**
     * A hole the user tapped on the frozen frame: append it to the results and
     * fill the next free picker slot with its score, like a detected one.
     */
    fun addManualHit(detection: Detection, hit: HitScore)

    /**
     * The user tapped a hand-placed hole again: drop hole [index] and its score,
     * shifting the picks in the later slots one step left (user edits included)
     * and pulling the hole that now reaches the pickers into the freed slot.
     */
    fun removeManualHit(index: Int)

    /** Clear detection overlay state. */
    fun clearResults()

    fun setError(message: String?)

    /** Update one slot of the top-scores list (user edited a picker). */
    fun setTopScoreAt(index: Int, value: Int)
}
