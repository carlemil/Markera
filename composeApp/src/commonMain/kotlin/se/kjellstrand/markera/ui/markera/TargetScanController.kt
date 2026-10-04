package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.geometryDto
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.centerSquare
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.fit67Ring
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.MODEL_INPUT_SIZE
import se.kjellstrand.markera.vision.postProcess
import se.kjellstrand.markera.vision.scoreHits
import se.kjellstrand.markera.vision.width

internal const val TAG = "Markera"

/**
 * Owns the detectors (one ~40 MB ONNX session for the whole app) and the
 * synchronous re-entry guard, and runs the two-phase scan pipeline on demand.
 * Both the free-marking screen and the competition wizard drive one shared
 * instance, created with [rememberTargetScanController] above the navigation
 * so it is never rebuilt per screen.
 */
@OptIn(ExperimentalAtomicApi::class)
class TargetScanController(
    val detector: HoleDetector,
    val digitDetector: DigitDetector,
) {
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
     * host, so free marking and the competition wizard both feed the same
     * recorder.
     */
    var onSeriesDetected: ((List<HitScore>, PlatformImage, GeometryDto?) -> Unit)? = null

    /** Called as a scan claims the detector, before anything of it is published. */
    var onScanStarted: (() -> Unit)? = null

    fun close() {
        closeRequested.store(true)
        // Idle: claim the guard for good (no scan can start again) and close
        // now. Mid-scan: the scan's finally closes instead.
        if (detecting.compareAndSet(false, true)) closeDetectors()
    }

    private fun closeDetectors() {
        detector.close()
        digitDetector.close()
    }

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
        val detection = manualDetection(x, y, state.detections, minGapPx, caliber.holeSidePx(ring))
            ?: return@editHoles false
        val hit = scoreHits(listOf(detection), centre, ring).firstOrNull()?.copy(manual = true)
            ?: return@editHoles false
        viewModel.addManualHit(detection, hit)
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
        val digits = digitDetector.detect(snapshot)
        ErrorLog.breadcrumb("scan", "digit OCR: ${digits.size} digits ${ocrStarted.elapsedNow()}")
        val centre = estimateCentre(digits, snapshot.width, snapshot.height)
        val ring = if (centre.method != CentreMethod.NONE) {
            val gray = withContext(Dispatchers.Default) { snapshot.toGrayscale() }
            val ringStarted = TimeSource.Monotonic.markNow()
            val fit = withContext(Dispatchers.Default) {
                fit67Ring(gray, snapshot.width, snapshot.height, digits, centre)
            }
            ErrorLog.breadcrumb("scan", "ring ${if (fit != null) "found" else "none"} ${ringStarted.elapsedNow()}")
            fit
        } else {
            null
        }
        // Publish the geometry and switch the spinner on: it now orbits
        // the detected centre, sized to the ring, while the holes run.
        viewModel.onGeometryReady(digits, centre, ring, snapshot.width, snapshot.height)

        // Detection off: stop after the geometry. Publishing empty holes is
        // what returns the phase to IDLE, which is what makes the frozen frame
        // editable — so hand-placed holes score against the ring just found.
        if (!detectHoles) {
            ErrorLog.breadcrumb("scan", "holes skipped (detection off)")
            viewModel.onHolesDetected(emptyList(), emptyList())
            return
        }

        // Phase 2 — holes: the slow ONNX pass, with the spinner up. The
        // raw frame goes straight to the model — only the input letterbox.
        // The last breadcrumb before an out-of-memory kill says which step was running.
        ErrorLog.breadcrumb("scan", "hole detection: letterboxing ${snapshot.width}x${snapshot.height} to ${detector.inputSize}")
        val input = withContext(Dispatchers.Default) {
            snapshot.toModelInput(detector.inputSize)
        }
        ErrorLog.breadcrumb("scan", "hole detection: inference started")
        val holesStarted = TimeSource.Monotonic.markNow()
        val raws = detector.detect(input)
        ErrorLog.breadcrumb("scan", "hole detection: ${raws.size} raw boxes ${holesStarted.elapsedNow()}")
        val detections = postProcess(raws, detector.inputSize, snapshot.width, snapshot.height)
        // Score each hole against the digit centre and the 6/7 ring.
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
        viewModel.onHolesDetected(detections, scores)
        if (scores.isNotEmpty()) {
            // The published state, not the local list: the pickers are saved by
            // position, so the series must carry the same order the view model
            // sorted the holes into.
            onSeriesDetected?.invoke(
                viewModel.uiState.value.scores,
                snapshot,
                ring?.let { geometryDto(centre, it) },
            )
        }
    }
}

/**
 * Creates the app's single [TargetScanController]. Call this once, above the
 * navigation, and pass the instance down — the ONNX session must not be
 * duplicated per screen, and must only close when the activity goes away
 * (never mid-scan on a screen switch).
 */
@Composable
fun rememberTargetScanController(modelPath: String): TargetScanController {
    val controller = remember {
        TargetScanController(
            detector = HoleDetector(modelPath, inputSize = MODEL_INPUT_SIZE),
            digitDetector = DigitDetector(),
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}
