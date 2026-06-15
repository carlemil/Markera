package se.kjellstrand.markera.ui.markera

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import se.kjellstrand.markera.R
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.filterByConfidence
import se.kjellstrand.markera.vision.fit67RingFromDigits
import se.kjellstrand.markera.vision.mapToImageSpace
import se.kjellstrand.markera.vision.nonMaxSuppression
import se.kjellstrand.markera.vision.refine67ToEdge

private const val TAG = "Markera"
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.45f
private const val MODEL_ASSET = "best.onnx"
private const val MODEL_INPUT_SIZE = 1536

@Composable
fun MarkeraScreen() {
    val context = LocalContext.current
    val viewModel: MarkeraViewModelImpl = viewModel { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val detector: HoleDetector = remember {
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
        HoleDetector(modelFile.absolutePath, inputSize = MODEL_INPUT_SIZE)
    }
    val digitDetector: DigitDetector = remember { DigitDetector() }
    DisposableEffect(detector, digitDetector) {
        onDispose {
            detector.close()
            digitDetector.close()
        }
    }
    val coroutineScope = rememberCoroutineScope()
    // The active frame source is chosen at build time by the product flavor:
    // `camera` → live CameraX preview, `mock` → random images from disk.
    val frameSource = rememberFrameSource()
    val requiresPermission = frameSource.requiresCameraPermission

    // `cameraGranted` doubles as the "ready to use" gate; flavors that need
    // no camera (mock) report ready immediately.
    var cameraGranted by remember {
        mutableStateOf(
            !requiresPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (requiresPermission && !cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val errorInference = stringResource(R.string.markera_error_inference)

    val onDetectClick: () -> Unit = onDetect@{
        if (uiState.phase != ScanPhase.IDLE) return@onDetect
        // Square the frame to match the FILL_CENTER live preview, so what the
        // user framed is exactly what gets analysed and shown frozen.
        val snapshot = (frameSource.capture() ?: return@onDetect).centerSquare()
        // Freeze the frame immediately so the user sees the static image
        // the model will analyse instead of the live preview.
        snapshotVm.set(snapshot)
        viewModel.startDetect()
        coroutineScope.launch {
            try {
                // Phase 1 — geometry: digit OCR -> centre -> 6/7 ring. Runs
                // first and with no spinner (it's fast, and the spinner is
                // drawn from this geometry). Predict the boundary from the
                // labelled digits, then snap it to the black->white edge; the
                // q-sweep fit and grayscale edge scan are CPU-bound, so off-main.
                val digits = digitDetector.detect(snapshot)
                val centre = estimateCentre(digits, snapshot.width, snapshot.height)
                val ring = if (centre.method != CentreMethod.NONE) {
                    withContext(Dispatchers.Default) {
                        fit67RingFromDigits(digits, centre)?.let { predicted ->
                            refine67ToEdge(
                                snapshot.toGrayscale(), snapshot.width, snapshot.height, predicted,
                            )
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
                Log.d(
                    TAG,
                    "snapshot ${snapshot.width}x${snapshot.height}: " +
                        "raw=${raws.size} kept=${detections.size} " +
                        "digits=${digits.size} centre=${centre.method} ring=${ring != null}",
                )
                viewModel.onHolesDetected(detections)
            } catch (t: Throwable) {
                Log.w(TAG, "snapshot inference failed", t)
                viewModel.setError(errorInference)
            }
        }
    }

    val onResumeLive: () -> Unit = {
        snapshotVm.clear()
        viewModel.clearResults()
        frameSource.onResumeLive()
    }

    // Mock flavor: run detection automatically each time a fresh frame is
    // loaded from disk (the key changes), so no tap is needed. The camera
    // flavor reports a null key and stays manual.
    LaunchedEffect(frameSource.autoDetectKey) {
        if (frameSource.autoDetectKey != null) onDetectClick()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge draws under the status bar / camera notch; inset the
            // content down so the top row clears the cutout.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        val isPortrait = maxHeight >= maxWidth

        if (!cameraGranted) {
            PermissionPrompt(
                onGrantClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        } else {
            // Pickers and viewport live in disjoint slots so they never
            // overlap: portrait stacks them vertically, landscape places
            // pickers to the left of the viewport.
            if (isPortrait) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ScorePickerHorizontalRow(
                        values = uiState.topScores,
                        onValueChange = viewModel::setTopScoreAt,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(16.dp),
                    )
                    Viewport(
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        frameSource = frameSource,
                        onError = { viewModel.setError(it.message) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .align(Alignment.CenterHorizontally),
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    ScorePickerVerticalColumn(
                        values = uiState.topScores,
                        onValueChange = viewModel::setTopScoreAt,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(16.dp),
                    )
                    Viewport(
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        frameSource = frameSource,
                        onError = { viewModel.setError(it.message) },
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .align(Alignment.CenterVertically),
                    )
                }
            }
        }

        uiState.error?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(8.dp),
            )
        }

        if (cameraGranted) {
            val isFrozen = snapshotVm.snapshot != null
            FloatingActionButton(
                onClick = if (isFrozen) onResumeLive else onDetectClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 56.dp),
            ) {
                if (isFrozen) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.markera_resume_live),
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.markera_detect),
                    )
                }
            }
        }
    }
}

@Composable
private fun Viewport(
    snapshotVm: MarkeraSnapshotViewModel,
    uiState: MarkeraUiState,
    frameSource: FrameSource,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val frozen = snapshotVm.snapshot
        if (frozen != null) {
            Image(
                bitmap = frozen.asImageBitmap(),
                contentDescription = null,
                // Fit-centre so the DetectionOverlay boxes (also fit-centre)
                // line up with the holes in this non-square snapshot.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            frameSource.Preview(
                onError = onError,
                modifier = Modifier.fillMaxSize(),
            )
            // Framing guide on the live viewfinder only — a centred circle
            // (~70% of the viewport) with a small crosshair at its centre.
            ViewfinderGuide(modifier = Modifier.fillMaxSize())
        }
        DetectionOverlay(
            detections = uiState.detections,
            digits = uiState.digits,
            centre = uiState.centre,
            ring = uiState.ring,
            imageWidth = uiState.imageWidth,
            imageHeight = uiState.imageHeight,
            modifier = Modifier.fillMaxSize(),
        )
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
            val s = if (imageWidth > 0 && imageHeight > 0) {
                min(size.width / imageWidth, size.height / imageHeight)
            } else {
                1f
            }
            val offsetX = (size.width - imageWidth * s) / 2f
            val offsetY = (size.height - imageHeight * s) / 2f
            val pivot: Offset
            val a: Float // canvas semi-major
            val b: Float // canvas semi-minor
            val rot: Float // ellipse tilt, radians
            if (ring != null && imageWidth > 0 && imageHeight > 0) {
                pivot = Offset(ring.cx * s + offsetX, ring.cy * s + offsetY)
                a = ring.semiMajor * s
                b = ring.semiMinor * s
                rot = ring.rotationRad
            } else {
                val r = 0.42f * min(size.width, size.height)
                pivot = if (centre != null && centre.method != CentreMethod.NONE && imageWidth > 0) {
                    Offset(centre.x * s + offsetX, centre.y * s + offsetY)
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                a = r
                b = r
                rot = 0f
            }
            val rotDeg = (rot * 180.0 / PI).toFloat()
            val ct = cos(rot)
            val st = sin(rot)
            // Point on the tilted ellipse at parameter [t], at radius fraction [f].
            fun onEllipse(t: Float, f: Float = 1f): Offset {
                val lx = a * f * cos(t)
                val ly = b * f * sin(t)
                return Offset(pivot.x + lx * ct - ly * st, pivot.y + lx * st + ly * ct)
            }

            val faint = green.copy(alpha = 0.18f)
            // Concentric guide ellipses, tilted with the ring.
            for (i in 1..3) {
                val fa = a * i / 3f
                val fb = b * i / 3f
                rotate(rotDeg, pivot) {
                    drawOval(
                        color = faint,
                        topLeft = Offset(pivot.x - fa, pivot.y - fb),
                        size = Size(fa * 2f, fb * 2f),
                        style = Stroke(2f),
                    )
                }
            }
            // Crosshair along the ellipse's major and minor axes.
            drawLine(faint, onEllipse(0f), onEllipse(PI.toFloat()), 1.5f)
            drawLine(faint, onEllipse((PI / 2.0).toFloat()), onEllipse((3.0 * PI / 2.0).toFloat()), 1.5f)
            // Rotating trail: a filled sweep gradient transformed into ellipse
            // space (a fill, so the anisotropic scale doesn't distort strokes).
            // rotate(angle) before scale spins it in circle space, so its bright
            // leading edge lands on the same ellipse parameter as the sweep line.
            withTransform({
                translate(pivot.x, pivot.y)
                rotate(rotDeg, Offset.Zero)
                scale(a, b, Offset.Zero)
                rotate(angle, Offset.Zero)
            }) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.85f to Color.Transparent,
                        1f to green.copy(alpha = 0.45f),
                        center = Offset.Zero,
                    ),
                    radius = 1f,
                    center = Offset.Zero,
                )
            }
            // Leading sweep line, from centre out to the ellipse edge.
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
private fun PermissionPrompt(onGrantClick: () -> Unit) {
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
                text = stringResource(R.string.markera_camera_permission_required),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGrantClick) {
                Text(stringResource(R.string.markera_grant_permission))
            }
        }
    }
}
