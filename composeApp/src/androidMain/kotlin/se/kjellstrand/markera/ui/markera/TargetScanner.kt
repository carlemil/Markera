package se.kjellstrand.markera.ui.markera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.series.geometryDto
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.filterByConfidence
import se.kjellstrand.markera.vision.fit67RingFromDigits
import se.kjellstrand.markera.vision.mapToImageSpace
import se.kjellstrand.markera.vision.nonMaxSuppression
import se.kjellstrand.markera.vision.refine67ToEdge
import se.kjellstrand.markera.vision.scoreHits

private const val TAG = "Markera"
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.45f
private const val MODEL_ASSET = "best.onnx"
private const val MODEL_INPUT_SIZE = 1536

/**
 * Owns the detectors (one ~80 MB ONNX session for the whole app) and the
 * synchronous re-entry guard, and runs the two-phase scan pipeline on demand.
 * Both the free-marking screen and the competition wizard drive one shared
 * instance, created with [rememberTargetScanController] above the navigation
 * so it is never rebuilt per screen.
 */
class TargetScanController(
    val detector: HoleDetector,
    val digitDetector: DigitDetector,
) {
    // Synchronous re-entry guard. uiState.phase is bridged from a flow via
    // collectAsState and lags a frame, so two rapid taps could both read IDLE
    // and launch concurrent ONNX runs — which crashes natively. This is set on
    // the main thread before launch, so a second tap is rejected immediately.
    private val detecting = AtomicBoolean(false)

    /**
     * Called with the scored holes, the frame they came from and the geometry
     * they were scored against (null when there was none) after every
     * successful scan, for the auto-save to the series backend. Set by the nav
     * host, so free marking and the competition wizard both feed the same
     * recorder.
     */
    var onSeriesDetected: ((List<HitScore>, PlatformImage, GeometryDto?) -> Unit)? = null

    fun close() {
        detector.close()
        digitDetector.close()
    }

    /**
     * Captures a frame, freezes it into [snapshotVm], and runs geometry + hole
     * detection, publishing into [viewModel]. Returns false when a scan is
     * already in flight.
     */
    fun startScan(
        frameSource: FrameSource,
        snapshotVm: MarkeraSnapshotViewModel,
        viewModel: MarkeraViewModel,
        scope: CoroutineScope,
        errorMessage: String,
    ): Boolean {
        // Claim the detector; reject re-entry until this pass finishes.
        if (!detecting.compareAndSet(false, true)) return false
        viewModel.startDetect()
        scope.launch {
            try {
                // The still takes ~0.5 s, so the live preview stays up until it
                // lands — what the user framed is what gets analysed.
                val started = SystemClock.elapsedRealtime()
                val frame = frameSource.capture()
                Log.i(TAG, "capture ${SystemClock.elapsedRealtime() - started} ms")
                if (frame == null) {
                    viewModel.setError(errorMessage)
                    return@launch
                }
                // Square the frame to match the FILL_CENTER live preview, so
                // what the user framed is exactly what gets analysed and shown
                // frozen.
                val snapshot = frame.centerSquare()
                snapshotVm.set(snapshot)
                runPipeline(snapshot, viewModel)
            } catch (t: Throwable) {
                Log.w(TAG, "snapshot inference failed", t)
                viewModel.setError(errorMessage)
            } finally {
                detecting.set(false)
            }
        }
        return true
    }

    /**
     * The user tapped a hole the detector missed, at [x],[y] in source-image px.
     * Scores that point with the same geometry as the scan and adds it as a
     * manual hole. A tap within [minGapPx] (image px) of an existing hole is a
     * mis-tap and adds nothing.
     */
    fun addHit(
        viewModel: MarkeraViewModel,
        snapshot: Bitmap?,
        x: Float,
        y: Float,
        minGapPx: Float,
    ) = editHoles(viewModel, snapshot) { state, centre, ring ->
        val detection = manualDetection(x, y, state.detections, minGapPx) ?: return@editHoles false
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
        snapshot: Bitmap?,
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
            landed = viewModel.moveHit(
                index,
                moved,
                hit.copy(
                    manual = old.manual,
                    original = if (old.manual) null else (old.original ?: old),
                ),
            )
            landed >= 0
        }
        return landed
    }

    /** The user long-pressed hole [index]: drop it, detected or hand-placed. */
    fun removeHit(viewModel: MarkeraViewModel, snapshot: Bitmap?, index: Int) =
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
        snapshot: Bitmap?,
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

    private suspend fun runPipeline(snapshot: Bitmap, viewModel: MarkeraViewModel) {
        // Phase 1 — geometry: digit OCR -> centre -> 6/7 ring. Runs
        // first and with no spinner (it's fast, and the spinner is
        // drawn from this geometry). The digits give a circle seed at
        // the centre; refine snaps it to the black->white edge. The
        // seed fit and grayscale edge scan are CPU-bound, so off-main.
        val ocrStarted = SystemClock.elapsedRealtime()
        val digits = digitDetector.detect(snapshot)
        Log.i(TAG, "digit OCR ${SystemClock.elapsedRealtime() - ocrStarted} ms")
        val centre = estimateCentre(digits, snapshot.width, snapshot.height)
        val ring = if (centre.method != CentreMethod.NONE) {
            withContext(Dispatchers.Default) {
                fit67RingFromDigits(digits, centre)?.let { seed ->
                    refine67ToEdge(snapshot.toGrayscale(), snapshot.width, snapshot.height, seed)
                }
            }
        } else {
            null
        }
        // Publish the geometry and switch the spinner on: it now orbits
        // the detected centre, sized to the ring, while the holes run.
        viewModel.onGeometryReady(digits, centre, ring, snapshot.width, snapshot.height)

        // Phase 2 — holes: the slow ONNX pass, with the spinner up. The
        // raw frame goes straight to the model — only the input letterbox.
        val input = withContext(Dispatchers.Default) {
            snapshot.toModelInput(detector.inputSize)
        }
        val raws = detector.detect(input)
        val detections = mapToImageSpace(
            nonMaxSuppression(
                filterByConfidence(raws, CONFIDENCE_THRESHOLD),
                IOU_THRESHOLD,
            ),
            detector.inputSize, snapshot.width, snapshot.height,
        )
        // Score each hole against the digit centre and the 6/7 ring.
        val scores = if (centre.method != CentreMethod.NONE && ring != null) {
            scoreHits(detections, centre, ring)
        } else {
            emptyList()
        }
        Log.d(
            TAG,
            "snapshot ${snapshot.width}x${snapshot.height}: " +
                "raw=${raws.size} kept=${detections.size} " +
                "digits=${digits.size} centre=${centre.method} ring=${ring != null} " +
                "scores=${scores.map { if (it.isInnerTen) "X" else it.ring.toString() }}",
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
fun rememberTargetScanController(): TargetScanController {
    val context = LocalContext.current
    val controller = remember {
        // Stream the asset to a plain file once and hand ONNX Runtime the
        // path: the native runtime reads the ~40 MB model directly, instead
        // of readBytes() staging it on the Java heap (which OOMed small heaps).
        // Re-copied after each app update (the asset may have changed); the
        // temp-file + rename keeps an interrupted copy from being trusted.
        val modelFile = java.io.File(context.filesDir, MODEL_ASSET)
        val apkTime = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!modelFile.exists() || modelFile.lastModified() < apkTime) {
            val tmp = java.io.File(context.filesDir, "$MODEL_ASSET.tmp")
            context.assets.open(MODEL_ASSET).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            check(tmp.renameTo(modelFile)) { "could not move $tmp into place" }
        }
        TargetScanController(
            detector = HoleDetector(modelFile.absolutePath, inputSize = MODEL_INPUT_SIZE),
            digitDetector = DigitDetector(),
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

/** Camera permission gate shared by every scanning screen. */
class CameraPermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberCameraPermission(frameSource: FrameSource): CameraPermissionState {
    val context = LocalContext.current
    val requiresPermission = frameSource.requiresCameraPermission
    // `granted` doubles as the "ready to use" gate.
    var granted by remember {
        mutableStateOf(
            !requiresPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (requiresPermission && !granted) launcher.launch(Manifest.permission.CAMERA)
    }
    return CameraPermissionState(granted) { launcher.launch(Manifest.permission.CAMERA) }
}

/**
 * Editing the holes on the frozen frame: add one where the user taps, move the
 * one they drag, remove the one they long-press. All coordinates are
 * source-image px. Null on screens that don't offer editing (then the frame
 * doesn't zoom either).
 */
class HoleEditing(
    val add: (x: Float, y: Float, reach: Float) -> Unit,
    val move: (index: Int, x: Float, y: Float) -> Int,
    val remove: (index: Int) -> Unit,
)

/** How close a touch has to land to a hole to grab it. */
private val GRAB_RADIUS = 24.dp

/**
 * The scanning viewport: live preview (or frozen snapshot) + detection
 * overlays.
 *
 * With [editing] set, a frozen frame that has been scored also pinch-zooms and
 * takes hole edits (see [HoleEditing]); the live preview never does.
 */
@Composable
fun TargetScanner(
    frameSource: FrameSource,
    snapshotVm: MarkeraSnapshotViewModel,
    uiState: MarkeraUiState,
    showDebug: Boolean,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    editing: HoleEditing? = null,
) {
    val frozen = snapshotVm.snapshot
    // Editing (and with it the zoom) only on a frozen frame that has been scored.
    val editable = editing != null && frozen != null &&
        uiState.centre != null && uiState.ring != null && uiState.phase == ScanPhase.IDLE
    // The gesture loop is not recomposed, so it must read these through the
    // latest-value states rather than the values captured when it started.
    val state by rememberUpdatedState(uiState)
    val edit by rememberUpdatedState(editing)
    // A new frame (or clearing one) starts unzoomed.
    val zoomPan = rememberZoomPan(frozen)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }
    Box(
        modifier = if (editable) {
            modifier.photoGestures(
                t = zoomPan,
                imageW = uiState.imageWidth,
                imageH = uiState.imageHeight,
                grabPx = grabPx,
                // Keyed on the frame, not the lambdas: a lambda key would
                // restart the gesture loop on every recomposition.
                key = frozen,
                holeAt = { x, y, reach ->
                    nearestDetectionIndex(x, y, state.detections, reach)
                },
                onMove = { i, x, y -> edit?.move(i, x, y) ?: -1 },
                onAdd = { x, y, reach -> edit?.add(x, y, reach) },
                onRemove = { i -> edit?.remove(i) },
            )
        } else {
            modifier
        },
    ) {
        // The preview stays outside the zoom layer: it is a SurfaceView, which
        // ignores a graphicsLayer transform anyway, and it never zooms.
        if (frozen == null) {
            frameSource.Preview(
                onError = onError,
                modifier = Modifier.fillMaxSize(),
            )
            // Framing guide on the live viewfinder only — a centred circle
            // (~70% of the viewport) with a small crosshair at its centre.
            ViewfinderGuide(modifier = Modifier.fillMaxSize())
        }
        Box(modifier = Modifier.fillMaxSize().zoomPan(zoomPan)) {
            if (frozen != null) {
                Image(
                    bitmap = frozen.asImageBitmap(),
                    contentDescription = null,
                    // Fit-centre so the DetectionOverlay boxes (also fit-centre)
                    // line up with the holes in this non-square snapshot.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DetectionOverlay(
                detections = uiState.detections,
                digits = uiState.digits,
                centre = uiState.centre,
                ring = uiState.ring,
                scores = uiState.scores,
                showDebug = showDebug,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (uiState.phase == ScanPhase.HOLES) {
            ScanningOverlay(
                centre = uiState.centre,
                ring = uiState.ring,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The auto-save recorder, provided by the nav host so the shared viewport can
 * show the caliber chip without every screen plumbing it through. Null when
 * there is nothing to save to (e.g. a preview).
 */
val LocalSeriesRecorder = staticCompositionLocalOf<SeriesRecorder?> { null }

/**
 * Radar-sweep "working" animation shown over the frozen frame while hole
 * detection runs. The sweep orbits the detected [centre] and is sized to the
 * detected 6/7 [ring], so it scans exactly the target the geometry phase found;
 * with no centre/ring it falls back to the viewport centre and a fixed radius.
 * A green sweep line drags a fading trail; fixed blips flash as it passes.
 * Indeterminate — it loops until the [ScanPhase.HOLES] phase clears.
 */
@Composable
private fun ScanningOverlay(
    centre: CentreEstimate?,
    ring: FittedEllipse?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    val sweep = rememberInfiniteTransition(label = "scan")
    val angle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    val green = Color(0xFF00E676)
    // Fixed targets the sweep "discovers": (angle°, radius fraction).
    val blips = remember {
        listOf(35f to 0.8f, 110f to 0.55f, 200f to 0.9f, 255f to 0.4f, 320f to 0.68f)
    }
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Anchor the radar to the detected 6/7 ellipse — its centre, both
            // semi-axes and tilt — using the same fit-centre letterbox as the
            // DetectionOverlay, so the sweep traces the green ellipse drawn
            // underneath. With no ring, fall back to a circle at the detected
            // centre; with no geometry at all, the viewport centre.
            val hasImage = imageWidth > 0 && imageHeight > 0
            val s = if (hasImage) min(size.width / imageWidth, size.height / imageHeight) else 1f
            val offsetX = (size.width - imageWidth * s) / 2f
            val offsetY = (size.height - imageHeight * s) / 2f
            // Pivot the sweep on the true target centre — the digit-row line
            // intersection — falling back to the ring centre, then the viewport.
            val pivot = when {
                centre != null && centre.method != CentreMethod.NONE && hasImage ->
                    Offset(centre.x * s + offsetX, centre.y * s + offsetY)
                ring != null && hasImage ->
                    Offset(ring.cx * s + offsetX, ring.cy * s + offsetY)
                else -> Offset(size.width / 2f, size.height / 2f)
            }
            // The ellipse-based elements (rotating tip, guide rings, trail,
            // blips) ride the *detected* ellipse, centred on the ring centre —
            // so the sweep's outer point traces the green 6/7 ellipse exactly.
            // The sweep line still springs from the digit-centre pivot.
            val ec = if (ring != null && hasImage) {
                Offset(ring.cx * s + offsetX, ring.cy * s + offsetY)
            } else {
                pivot
            }
            // Outer ellipse shape = the detected 6/7 ring (same semi-axes + tilt);
            // a plain circle when there's no ring yet.
            val a: Float // canvas semi-major
            val b: Float // canvas semi-minor
            val rot: Float // ellipse tilt, radians
            if (ring != null && hasImage) {
                a = ring.semiMajor * s
                b = ring.semiMinor * s
                rot = ring.rotationRad
            } else {
                val r = 0.42f * min(size.width, size.height)
                a = r
                b = r
                rot = 0f
            }
            val rotDeg = (rot * 180.0 / PI).toFloat()
            val ct = cos(rot)
            val st = sin(rot)
            // Point on the detected (tilted) ellipse at parameter [t], radius
            // fraction [f] — centred on the ring centre [ec].
            fun onEllipse(t: Float, f: Float = 1f): Offset {
                val lx = a * f * cos(t)
                val ly = b * f * sin(t)
                return Offset(ec.x + lx * ct - ly * st, ec.y + lx * st + ly * ct)
            }

            val faint = green.copy(alpha = 0.18f)
            // Concentric guide ellipses, tilted with the ring.
            for (i in 1..3) {
                val fa = a * i / 3f
                val fb = b * i / 3f
                rotate(rotDeg, ec) {
                    drawOval(
                        color = faint,
                        topLeft = Offset(ec.x - fa, ec.y - fb),
                        size = Size(fa * 2f, fb * 2f),
                        style = Stroke(2f),
                    )
                }
            }
            // Crosshair along the ellipse's major and minor axes.
            drawLine(faint, onEllipse(0f), onEllipse(PI.toFloat()), 1.5f)
            drawLine(faint, onEllipse((PI / 2.0).toFloat()), onEllipse((3.0 * PI / 2.0).toFloat()), 1.5f)
            // Fading trail: a wedge swept from the pivot (the red crosshair) out
            // to the detected ellipse, brightest at the leading sweep line and
            // fading behind it. Built from the same onEllipse() points, so it
            // springs from the crosshair yet its outer edge rides the ellipse.
            val trailSpanDeg = 70f
            val trailSteps = 28
            for (i in 0 until trailSteps) {
                val t0 = ((angle - trailSpanDeg * (1f - i / trailSteps.toFloat())) * PI / 180.0).toFloat()
                val t1 = ((angle - trailSpanDeg * (1f - (i + 1) / trailSteps.toFloat())) * PI / 180.0).toFloat()
                val p0 = onEllipse(t0)
                val p1 = onEllipse(t1)
                val wedge = Path().apply {
                    moveTo(pivot.x, pivot.y)
                    lineTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    close()
                }
                drawPath(wedge, color = green.copy(alpha = 0.45f * (i + 1) / trailSteps.toFloat()))
            }
            // Leading sweep line, from the crosshair out to the ellipse edge.
            val rad = (angle * PI / 180.0).toFloat()
            drawLine(green, start = pivot, end = onEllipse(rad), strokeWidth = 3f)
            // Each blip flares as the sweep passes, then fades over ~70°.
            blips.forEach { (blipAngle, r) ->
                val since = ((angle - blipAngle) % 360f + 360f) % 360f
                val alpha = (1f - since / 70f).coerceIn(0f, 1f)
                if (alpha > 0f) {
                    val p = onEllipse((blipAngle * PI / 180.0).toFloat(), r)
                    drawCircle(green.copy(alpha = alpha), radius = 5f + 5f * alpha, center = p)
                }
            }
        }
        Text(
            text = "SCANNING TARGET…",
            color = green,
            style = MaterialTheme.typography.titleMedium.copy(
                // Strong black drop shadow so the text reads over the target.
                shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 10f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/**
 * Static aiming guide drawn over the live preview: a centred circle whose
 * diameter is ~70% of the smaller viewport dimension, plus a crosshair at its
 * centre. Purely a framing aid — it does not affect detection.
 */
@Composable
private fun ViewfinderGuide(modifier: Modifier = Modifier) {
    val guideColor = Color(0xCCFFFFFF)
    Canvas(modifier = modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = 0.35f * min(size.width, size.height)
        val stroke = 3.dp.toPx()
        drawCircle(
            color = guideColor,
            radius = radius,
            center = centre,
            style = Stroke(width = stroke),
        )
        val arm = 16.dp.toPx()
        drawLine(
            color = guideColor,
            start = Offset(centre.x - arm, centre.y),
            end = Offset(centre.x + arm, centre.y),
            strokeWidth = stroke,
        )
        drawLine(
            color = guideColor,
            start = Offset(centre.x, centre.y - arm),
            end = Offset(centre.x, centre.y + arm),
            strokeWidth = stroke,
        )
    }
}

@Composable
fun CameraPermissionPrompt(onGrantClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(Res.string.markera_camera_permission_required),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGrantClick) {
                Text(stringResource(Res.string.markera_grant_permission))
            }
        }
    }
}
