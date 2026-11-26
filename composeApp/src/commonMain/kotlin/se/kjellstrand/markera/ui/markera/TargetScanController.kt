package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.geometryDto
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HIT_SCORE_ORDER
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.centerSquare
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.fit67Ring
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.postProcess
import se.kjellstrand.markera.vision.refineGeometry
import se.kjellstrand.markera.vision.scoreHits
import se.kjellstrand.markera.vision.width

internal const val TAG = "Markera"

/**
 * Owns the detectors (one ~40 MB ONNX session for the whole app) and the
 * synchronous re-entry guard, and runs the two-phase scan pipeline on demand.
 * Free marking and Import images drive one shared
 * instance, held by [AppViewModel][se.kjellstrand.markera.ui.AppViewModel] above the
 * navigation, so it is never rebuilt per screen and outlives an activity recreation.
 */
@OptIn(ExperimentalAtomicApi::class)
class TargetScanController(private val engine: ScanEngine) {
    // Synchronous re-entry guard. uiState.phase is bridged from a flow via
    // collectAsState and lags a frame, so two rapid taps could both read IDLE
    // and launch concurrent ONNX runs — which crashes natively. This is set on
    // the main thread before launch, so a second tap is rejected immediately.
    private val detecting = AtomicBoolean(false)

    // close() arrived mid-scan: the scan's finally closes the detectors once
    // the native run has returned (closing the ORT session under a running
    // session.run is a use-after-free).
    private val closeRequested = AtomicBoolean(false)

    /**
     * Called with the scored holes, the frame they came from and the geometry
     * they were scored against (null when there was none) after every
     * successful scan, for the auto-save to the series backend. Set by the nav
     * host, so free marking and Import images both feed the same
     * recorder.
     */
    var onSeriesDetected: ((List<HitScore>, PlatformImage, GeometryDto?, List<HitScore>) -> Unit)? = null

    /** Called as a scan claims the detector, before anything of it is published. */
    var onScanStarted: (() -> Unit)? = null

    fun close() {
        closeRequested.store(true)
        // Idle: claim the guard for good (no scan can start again) and close
        // now. Mid-scan: the scan's finally closes instead.
        if (detecting.compareAndSet(false, true)) closeDetectors()
    }

    private fun closeDetectors() = engine.close()

    /**
     * Captures a frame, freezes it into [snapshotVm], and runs geometry + hole
     * detection, publishing into [viewModel]. Returns false when a scan is
     * already in flight.
     *
     * With [detectHoles] off the slow ONNX pass is skipped: the geometry still
     * runs, so the frozen frame keeps its centre and 6/7 ring and the user
     * places every hole by hand.
     */
    fun startScan(
        frameSource: FrameSource,
        snapshotVm: MarkeraSnapshotViewModel,
        viewModel: MarkeraViewModel,
        scope: CoroutineScope,
        errorMessage: String,
        detectHoles: Boolean = true,
    ): Boolean {
        // Claim the detector; reject re-entry until this pass finishes.
        if (!detecting.compareAndSet(false, true)) return false
        // A scan that ends up scoring nothing (no ring, no holes) must not leave the
        // previous series pending for the next commit to save with these pickers.
        onScanStarted?.invoke()
        viewModel.startDetect()
        scope.launch {
            try {
                // The still takes ~0.5 s, so the live preview stays up until it
                // lands — what the user framed is what gets analysed.
                val started = TimeSource.Monotonic.markNow()
                ErrorLog.breadcrumb("scan", "capture started")
                val frame = frameSource.capture()
                ErrorLog.breadcrumb("scan", "capture ${frame?.let { "${it.width}x${it.height}" } ?: "failed"} ${started.elapsedNow()}")
                if (frame == null) {
                    viewModel.setError(errorMessage)
                    return@launch
                }
                // Square the frame to match the FILL_CENTER live preview, so
                // what the user framed is exactly what gets analysed and shown
                // frozen.
                val snapshot = frame.centerSquare()
                snapshotVm.set(snapshot)
                runPipeline(snapshot, viewModel, detectHoles)
            } catch (e: CancellationException) {
                // The screen left mid-pass: the activity-scoped view models outlive it, and a
                // phase stuck at HOLES would greet its return with a spinner and no scan button.
                snapshotVm.clear()
                viewModel.clearResults()
                throw e
            } catch (t: Throwable) {
                println("$TAG: snapshot inference failed " + t.stackTraceToString())
                ErrorLog.report("scan", t)
                viewModel.setError(errorMessage)
            } finally {
                detecting.store(false)
                if (closeRequested.load() && detecting.compareAndSet(false, true)) closeDetectors()
            }
        }
        return true
    }

    /**
     * The user tapped a hole the detector missed, at [x],[y] in source-image px.
     * Scores that point with the same geometry as the scan and adds it as a
     * manual hole, its box sized by [caliber] (the median hole for
     * [Caliber.NONE]). A tap within [minGapPx] (image px) of an existing hole is a
     * mis-tap and adds nothing.
     */
    fun addHit(
        viewModel: MarkeraViewModel,
        snapshot: PlatformImage?,
        x: Float,
        y: Float,
        minGapPx: Float,
        caliber: Caliber,
    ) = editHoles(viewModel, snapshot) { state, centre, ring ->
        val detection = manualDetection(x, y, state.detections, minGapPx, caliber.holeBoxPx(x, y, centre, ring))
            ?: return@editHoles false
        val hit = scoreHits(listOf(detection), centre, ring).firstOrNull()?.copy(manual = true)
            ?: return@editHoles false
        viewModel.addManualHit(detection, hit)
        true
    }

    /**
     * The user dragged hole [index] to [x],[y] (source-image px). The hole keeps
     * its box size and is rescored against the scan's own geometry; a detected
     * hole keeps what the detector said about it in [HitScore.original], so it
     * still saves its `detected*` values. Returns the hole's index in the
     * re-sorted results, or -1 when nothing moved.
     */
    fun moveHit(
        viewModel: MarkeraViewModel,
        snapshot: PlatformImage?,
        index: Int,
        x: Float,
        y: Float,
    ): Int {
        // Where the hole ends up: rescoring re-sorts, so the drag has to follow it.
        var landed = -1
        editHoles(viewModel, snapshot) { state, centre, ring ->
            val old = state.scores.getOrNull(index) ?: return@editHoles false
            val moved = state.detections.getOrNull(index)?.movedTo(x, y) ?: return@editHoles false
            val hit = scoreHits(listOf(moved), centre, ring).firstOrNull() ?: return@editHoles false
            landed = viewModel.moveHit(index, moved, hit.movedFrom(old))
            landed >= 0
        }
        return landed
    }

    /**
     * The user tapped hole [index]'s score box and picked [pick] (picker index):
     * the hole keeps its place and takes that score, the detector's own staying
     * in [se.kjellstrand.markera.vision.HitScore.original].
     */
    fun setScore(viewModel: MarkeraViewModel, snapshot: PlatformImage?, index: Int, pick: Int) =
        editHoles(viewModel, snapshot) { state, _, _ ->
            val old = state.scores.getOrNull(index) ?: return@editHoles false
            if (state.topScores.getOrNull(index) == pick) return@editHoles false
            viewModel.setScore(index, old.withTypedScore(pick))
            true
        }

    /** The user long-pressed hole [index]: drop it, detected or hand-placed. */
    fun removeHit(viewModel: MarkeraViewModel, snapshot: PlatformImage?, index: Int) =
        editHoles(viewModel, snapshot) { _, _, _ ->
            viewModel.removeHit(index)
            true
        }

    /**
     * The scan found no usable ring or centre and the user placed both by hand
     * ([GeometryDraft]). The holes the scan did find are scored against them, and
     * the frame then edits and saves like any scored scan. Ignored without a
     * frozen frame or mid-scan.
     */
    fun applyManualGeometry(
        viewModel: MarkeraViewModel,
        snapshot: PlatformImage?,
        centre: CentreEstimate,
        ring: FittedEllipse,
    ) {
        val state = viewModel.uiState.value
        if (snapshot == null || state.phase != ScanPhase.IDLE || ring.semiMinor <= 0f) return
        ErrorLog.breadcrumb("scan", "manual geometry, ${state.detections.size} holes")
        viewModel.onManualGeometry(centre, ring, scoreHits(state.detections, centre, ring))
        // Published even with no holes yet: hand-placed ones re-publish as they come.
        onSeriesDetected?.invoke(
            viewModel.uiState.value.scores,
            snapshot,
            geometryDto(centre, ring),
            viewModel.uiState.value.removedDetections,
        )
    }

    /**
     * Runs one hole edit against the frozen scan's geometry and re-publishes the
     * series, so the pending save carries the edit. Ignored without geometry or
     * mid-scan; [edit] returns false when it changed nothing.
     */
    private fun editHoles(
        viewModel: MarkeraViewModel,
        snapshot: PlatformImage?,
        edit: (MarkeraUiState, CentreEstimate, FittedEllipse) -> Boolean,
    ) {
        val state = viewModel.uiState.value
        if (snapshot == null || state.phase != ScanPhase.IDLE) return
        val centre = state.centre?.takeIf { it.method != CentreMethod.NONE } ?: return
        val ring = state.ring ?: return
        if (!edit(state, centre, ring)) return
        onSeriesDetected?.invoke(
            viewModel.uiState.value.scores,
            snapshot,
            geometryDto(centre, ring),
            viewModel.uiState.value.removedDetections,
        )
    }

    private suspend fun runPipeline(
        snapshot: PlatformImage,
        viewModel: MarkeraViewModel,
        detectHoles: Boolean = true,
    ) {
        // Phase 1 — geometry: digit OCR -> centre -> 6/7 ring. Runs
        // first and with no spinner (it's fast, and the spinner is
        // drawn from this geometry). Probe disks settle on the black->white
        // rim; an implausible probe ellipse means no ring. The grayscale
        // conversion and probes are CPU-bound, so off-main.
        val ocrStarted = TimeSource.Monotonic.markNow()
        val digits = engine.digits(snapshot)
        ErrorLog.breadcrumb("scan", "digit OCR: ${digits.size} digits ${ocrStarted.elapsedNow()}")
        val digitCentre = estimateCentre(digits, snapshot.width, snapshot.height)
        val gray = if (digitCentre.method != CentreMethod.NONE) {
            withContext(Dispatchers.Default) { snapshot.toGrayscale() }
        } else {
            null
        }
        val probeRing = gray?.let {
            val ringStarted = TimeSource.Monotonic.markNow()
            val fit = withContext(Dispatchers.Default) {
                fit67Ring(it, snapshot.width, snapshot.height, digits, digitCentre)
            }
            ErrorLog.breadcrumb("scan", "ring ${if (fit != null) "found" else "none"} ${ringStarted.elapsedNow()}")
            fit
        }
        // Publish the geometry and switch the spinner on: it now orbits
        // the detected centre, sized to the ring, while the holes run.
        viewModel.onGeometryReady(digits, digitCentre, probeRing, snapshot.width, snapshot.height)

        // Meanwhile the black's edge and the white lines inside it pin the centre and ring down
        // tighter, alongside the hole model; when the photo doesn't back that, the digit centre and
        // probe ring stay. The holes are scored against whichever geometry this settles on.
        val refining = if (gray != null && probeRing != null) {
            // A child of this pass, so cancelling the pass cancels it too.
            CoroutineScope(currentCoroutineContext()).async(Dispatchers.Default) {
                val refineStarted = TimeSource.Monotonic.markNow()
                refineGeometry(gray, snapshot.width, snapshot.height, digitCentre, probeRing).also { refined ->
                    ErrorLog.breadcrumb(
                        "scan",
                        "ring refine ${refined?.let { "moved centre ${(it.centreMoveMm * 100).toInt() / 100.0} mm, inliers ${(it.inlierShare * 100).toInt()}%" } ?: "kept"} ${refineStarted.elapsedNow()}",
                    )
                }
            }
        } else {
            null
        }
        suspend fun settledGeometry(): Pair<CentreEstimate, FittedEllipse?> {
            val refined = refining?.await() ?: return digitCentre to probeRing
            viewModel.onGeometryReady(digits, refined.centre, refined.ring, snapshot.width, snapshot.height)
            return refined.centre to refined.ring
        }

        // Detection off: stop after the geometry. Publishing empty holes is
        // what returns the phase to IDLE, which is what makes the frozen frame
        // editable — so hand-placed holes score against the ring just found.
        if (!detectHoles) {
            settledGeometry()
            ErrorLog.breadcrumb("scan", "holes skipped (detection off)")
            viewModel.onHolesDetected(emptyList(), emptyList())
            return
        }

        // Phase 2 — holes: the slow ONNX pass, with the spinner up. The
        // raw frame goes straight to the model — only the input letterbox.
        // The last breadcrumb before an out-of-memory kill says which step was running.
        ErrorLog.breadcrumb("scan", "hole detection: letterboxing ${snapshot.width}x${snapshot.height} to ${engine.inputSize}")
        val input = withContext(Dispatchers.Default) {
            snapshot.toModelInput(engine.inputSize)
        }
        ErrorLog.breadcrumb("scan", "hole detection: inference started")
        val holesStarted = TimeSource.Monotonic.markNow()
        val raws = engine.holes(input)
        ErrorLog.breadcrumb("scan", "hole detection: ${raws.size} raw boxes ${holesStarted.elapsedNow()}")
        val detections = postProcess(raws, engine.inputSize, snapshot.width, snapshot.height)
        val (centre, ring) = settledGeometry()
        // Score each hole against the (refined) centre and 6/7 ring.
        val scores = if (centre.method != CentreMethod.NONE && ring != null) {
            scoreHits(detections, centre, ring)
        } else {
            emptyList()
        }
        println(
            "$TAG: snapshot ${snapshot.width}x${snapshot.height}: " +
                "raw=${raws.size} kept=${detections.size} " +
                "digits=${digits.size} centre=${centre.method} ring=${ring != null} " +
                "scores=${scores.map { if (it.isInnerTen) "X" else it.ring.toString() }}"
        )
        if (scores.isNotEmpty()) {
            // Before IDLE is published: Import's auto pass commits the moment it sees IDLE, and its
            // collector may run on another thread. Sorted as the view model sorts them (both stable),
            // since the pickers are saved by position.
            onSeriesDetected?.invoke(
                scores.sortedWith(HIT_SCORE_ORDER),
                snapshot,
                ring?.let { geometryDto(centre, it) },
                emptyList(),
            )
        }
        viewModel.onHolesDetected(detections, scores)
    }
}
