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
     * Phase 2 done: publish the holes and their [scores] (highest first), fill
     * the score pickers from the top hits, and return to idle.
     */
    fun onHolesDetected(detections: List<Detection>, scores: List<HitScore>)

    /**
     * A hole the user tapped on the frozen frame: added to the results and the
     * pickers, in score order, like a detected one.
     */
    fun addManualHit(detection: Detection, hit: HitScore)

    /**
     * The user dragged hole [index] somewhere else: replace its box and score
     * (rescored against the same geometry). Returns where the hole ended up in
     * the re-sorted list, or -1 when there was no such hole.
     */
    fun moveHit(index: Int, detection: Detection, hit: HitScore): Int

    /** The user long-pressed a hole: drop hole [index] and its score. */
    fun removeHit(index: Int)

    /** Clear detection overlay state. */
    fun clearResults()

    fun setError(message: String?)
}
