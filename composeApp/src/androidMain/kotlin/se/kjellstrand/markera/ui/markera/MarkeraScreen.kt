package se.kjellstrand.markera.ui.markera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import se.kjellstrand.markera.R
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.filterByConfidence
import se.kjellstrand.markera.vision.mapToImageSpace
import se.kjellstrand.markera.vision.nonMaxSuppression

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
        if (uiState.isProcessing) return@onDetect
        val snapshot = frameSource.capture() ?: return@onDetect
        // Freeze the frame immediately so the user sees the static image
        // the model will analyse instead of the live preview.
        snapshotVm.set(snapshot)
        viewModel.setProcessing(true)
        coroutineScope.launch {
            try {
                // Hole detection (ONNX) and digit OCR (ML Kit) are independent,
                // so run them concurrently and join before publishing results.
                val holesJob = async {
                    // The raw frame goes straight to the model — no perspective
                    // warp or centre detection, only the model-input letterbox.
                    val input = withContext(Dispatchers.Default) {
                        snapshot.toModelInput(detector.inputSize)
                    }
                    val raws = detector.detect(input)
                    val kept = nonMaxSuppression(
                        filterByConfidence(raws, CONFIDENCE_THRESHOLD),
                        IOU_THRESHOLD,
                    )
                    raws.size to mapToImageSpace(
                        kept, detector.inputSize, snapshot.width, snapshot.height,
                    )
                }
                val digitsJob = async { digitDetector.detect(snapshot) }

                val (rawCount, detections) = holesJob.await()
                val digits = digitsJob.await()
                val centre = estimateCentre(digits, snapshot.width, snapshot.height)
                Log.d(
                    TAG,
                    "snapshot ${snapshot.width}x${snapshot.height}: " +
                        "raw=$rawCount kept=${detections.size} " +
                        "digits=${digits.size} centre=${centre.method}",
                )
                viewModel.onFrameAnalysed(
                    detections, digits, centre, snapshot.width, snapshot.height,
                )
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            imageWidth = uiState.imageWidth,
            imageHeight = uiState.imageHeight,
            modifier = Modifier.fillMaxSize(),
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
