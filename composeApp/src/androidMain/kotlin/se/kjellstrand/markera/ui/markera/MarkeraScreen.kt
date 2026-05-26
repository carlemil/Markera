package se.kjellstrand.markera.ui.markera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.R
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.HoleDetector
import se.kjellstrand.markera.vision.TARGET_CARD_WIDTH_MM
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.computeHitScores
import se.kjellstrand.markera.vision.filterByConfidence
import se.kjellstrand.markera.vision.mapToImageSpace
import se.kjellstrand.markera.vision.nonMaxSuppression

private const val TAG = "Markera"
private const val SCORE_TAG = "MarkeraScore"
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.45f
private const val MODEL_ASSET = "best.onnx"
private const val MODEL_INPUT_SIZE = 640

private fun scoreDetections(
    detections: List<Detection>,
    width: Int,
    height: Int,
    calibration: TargetCalibration?,
): List<HitScore> =
    if (calibration != null) {
        computeHitScores(detections, calibration)
    } else {
        computeHitScores(
            detections,
            centerX = width / 2f,
            centerY = height / 2f,
            mmPerPx = TARGET_CARD_WIDTH_MM / width.toDouble(),
        )
    }

private fun logHitScores(scores: List<HitScore>, calibration: TargetCalibration?) {
    if (calibration != null) {
        Log.d(
            SCORE_TAG,
            "calibrated: centre=(${calibration.centerX.toInt()},${calibration.centerY.toInt()}) " +
                "semiMajor=${calibration.semiMajorPx.toInt()}px " +
                "semiMinor=${calibration.semiMinorPx.toInt()}px " +
                "θ=${"%.1f".format(calibration.rotationRad * 180.0 / kotlin.math.PI)}° " +
                "mmPerPx=${"%.3f".format(calibration.mmPerPx)} " +
                "conf=${"%.2f".format(calibration.confidence)}",
        )
    } else {
        Log.d(SCORE_TAG, "fallback calibration (no 7-ring blob found)")
    }
    val total = scores.sumOf { if (it.isInnerTen) 10 else it.ring }
    scores.forEachIndexed { i, s ->
        val ringStr = if (s.isInnerTen) "X" else s.ring.toString()
        Log.d(
            SCORE_TAG,
            "hit #${i + 1}: ring=$ringStr distance=${"%.1f".format(s.distanceMm)}mm " +
                "px=(${s.centerXpx.toInt()},${s.centerYpx.toInt()})",
        )
    }
    Log.d(SCORE_TAG, "total: ${scores.size} hits, score=$total")
}

/** Top [SCORE_PICKER_COUNT] hits (already sorted desc by [computeHitScores]) → picker indices, padded with 0. */
private fun topPickerValues(scores: List<HitScore>): List<Int> {
    val taken = scores.take(SCORE_PICKER_COUNT).map {
        if (it.isInnerTen) SCORE_PICKER_INNER_TEN else it.ring
    }
    return taken + List(SCORE_PICKER_COUNT - taken.size) { 0 }
}

private fun eccentricity(c: TargetCalibration): Float =
    if (c.semiMajorPx > 0f) 1f - c.semiMinorPx / c.semiMajorPx else 0f

/**
 * Result of choosing how to rectify the frozen snapshot. The [mode]
 * string is informational only (for logging) and reports the path
 * actually taken (`perspective(SRC) | affine | skipped`).
 */
private data class WarpResult(
    val bitmap: android.graphics.Bitmap,
    val calibration: TargetCalibration?,
    val mode: String,
)

private suspend fun warpForAnalysis(
    snapshot: android.graphics.Bitmap,
    calibration: TargetCalibration?,
    intrinsics: se.kjellstrand.markera.vision.CameraIntrinsics?,
): WarpResult {
    if (calibration == null) return WarpResult(snapshot, null, "skipped")
    val outputSize = min(snapshot.width, snapshot.height)
    // Try perspective first — only skip it on essentially-frontal shots
    // where the pose recovery is ill-conditioned and the affine warp is
    // already near-identity. 0.005 corresponds to ~6 deg tilt.
    if (intrinsics != null && eccentricity(calibration) > 0.005f) {
        val warped = withContext(Dispatchers.Default) {
            snapshot.unwarpToCirclePerspective(calibration, intrinsics, outputSize)
        }
        if (warped != null) {
            return WarpResult(
                warped,
                calibration.afterUnwarpPerspective(outputSize),
                "perspective(${intrinsics.source})",
            )
        }
    }
    // Fall back to affine on near-frontal shots, missing intrinsics, or
    // degenerate pose recovery.
    val warped = withContext(Dispatchers.Default) {
        snapshot.unwarpToCircle(calibration, outputSize)
    }
    return WarpResult(
        warped,
        calibration.afterUnwarpToCircle(outputSize),
        "affine",
    )
}

@Composable
fun MarkeraScreen() {
    val context = LocalContext.current
    val viewModel: MarkeraViewModelImpl = viewModel { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val detector: HoleDetector = remember {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        HoleDetector(bytes, inputSize = MODEL_INPUT_SIZE)
    }
    DisposableEffect(detector) {
        onDispose { detector.close() }
    }
    val coroutineScope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val errorInference = stringResource(R.string.markera_error_inference)

    val onDetectClick: () -> Unit = onDetect@{
        if (uiState.isProcessing) return@onDetect
        val snapshot = previewView.bitmap ?: return@onDetect
        // Freeze the frame immediately so the user sees the static image
        // the model will analyse instead of the live preview.
        snapshotVm.set(snapshot)
        viewModel.setProcessing(true)
        coroutineScope.launch {
            try {
                val freshCal = withContext(Dispatchers.Default) { snapshot.calibrate() }
                val intrinsics = snapshotVm.intrinsics
                    ?.scaledToBitmap(snapshot.width, snapshot.height)

                val warp = warpForAnalysis(snapshot, freshCal, intrinsics)
                val analysisBitmap = warp.bitmap
                val analysisCal = warp.calibration

                val input = withContext(Dispatchers.Default) {
                    analysisBitmap.toModelInput(detector.inputSize)
                }
                val raws = detector.detect(input)
                val kept = nonMaxSuppression(
                    filterByConfidence(raws, CONFIDENCE_THRESHOLD),
                    IOU_THRESHOLD,
                )
                val detections = mapToImageSpace(
                    kept, detector.inputSize, analysisBitmap.width, analysisBitmap.height,
                )
                Log.d(
                    TAG,
                    "snapshot ${snapshot.width}x${snapshot.height} -> " +
                        "analysis ${analysisBitmap.width}x${analysisBitmap.height}: " +
                        "raw=${raws.size} kept=${kept.size} warp=${warp.mode}",
                )

                val scores = scoreDetections(
                    detections, analysisBitmap.width, analysisBitmap.height, analysisCal,
                )
                logHitScores(scores, analysisCal)

                if (analysisBitmap !== snapshot) {
                    snapshotVm.set(analysisBitmap)
                    snapshot.recycle()
                }
                viewModel.setCalibration(analysisCal)
                viewModel.onFrameAnalysed(
                    detections, analysisBitmap.width, analysisBitmap.height,
                )
                viewModel.setTopScores(topPickerValues(scores))
            } catch (t: Throwable) {
                Log.w(TAG, "snapshot inference failed", t)
                viewModel.setError(errorInference)
            }
        }
    }

    val onResumeLive: () -> Unit = {
        snapshotVm.clear()
        viewModel.clearResults()
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
                        previewView = previewView,
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
                        previewView = previewView,
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
                    .padding(16.dp),
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
    previewView: PreviewView,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val frozen = snapshotVm.snapshot
        if (frozen != null) {
            Image(
                bitmap = frozen.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraPreview(
                previewView = previewView,
                onError = onError,
                modifier = Modifier.fillMaxSize(),
                onCameraReady = { camera -> snapshotVm.intrinsics = extractIntrinsics(camera) },
            )
        }
        CalibrationOverlay(
            calibration = uiState.calibration,
            imageWidth = uiState.imageWidth,
            imageHeight = uiState.imageHeight,
            modifier = Modifier.fillMaxSize(),
        )
        DetectionOverlay(
            detections = uiState.detections,
            imageWidth = uiState.imageWidth,
            imageHeight = uiState.imageHeight,
            modifier = Modifier.fillMaxSize(),
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
