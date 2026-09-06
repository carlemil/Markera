package se.kjellstrand.markera.ui.markera

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import se.kjellstrand.markera.R
import se.kjellstrand.markera.series.SeriesRecorder

/**
 * The free-marking screen ("Fri markering"): frame a target, scan it, adjust
 * the pickers. The detectors and frame source are hoisted by the caller (the
 * nav host) so they are shared with the competition wizard; the defaults keep
 * the screen self-contained when composed alone.
 */
@Composable
fun MarkeraScreen(
    frameSource: FrameSource = rememberFrameSource(),
    scanController: TargetScanController = rememberTargetScanController(),
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val viewModel: MarkeraViewModelImpl = viewModel { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val permission = rememberCameraPermission(frameSource)

    val errorInference = stringResource(R.string.markera_error_inference)
    // Off by default — the clean result view. Toggle in the top bar reveals the
    // raw detection overlay (hole/digit boxes, row lines) for diagnostics.
    var showDebug by remember { mutableStateOf(false) }
    val comingSoon = stringResource(R.string.markera_coming_soon)
    val onStub: () -> Unit = { Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show() }

    val onDetectClick: () -> Unit = {
        scanController.startScan(frameSource, snapshotVm, viewModel, coroutineScope, errorInference)
    }

    val recorder = LocalSeriesRecorder.current
    val onResumeLive: () -> Unit = {
        snapshotVm.clear()
        viewModel.clearResults()
        // Leaving the frozen frame is what saves the scan.
        recorder?.commit()
        frameSource.onResumeLive()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge draws under the status bar / nav bar; keep content
            // clear of both, while the preview stays full-bleed horizontally.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) {
        val isPortrait = maxHeight >= maxWidth

        if (!permission.granted) {
            CameraPermissionPrompt(onGrantClick = permission.request)
        } else {
            val isFrozen = snapshotVm.snapshot != null
            val processing = uiState.phase != ScanPhase.IDLE
            Column(modifier = Modifier.fillMaxSize()) {
                MarkeraTopBar(
                    showDebug = showDebug,
                    onToggleDebug = { showDebug = !showDebug },
                    onBack = onBack,
                )
                if (isPortrait) {
                    TargetScanner(
                        frameSource = frameSource,
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        showDebug = showDebug,
                        onError = { viewModel.setError(it.message) },
                        onAutoDetect = onDetectClick,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    BottomArea(
                        isFrozen = isFrozen,
                        processing = processing,
                        uiState = uiState,
                        onValueChange = viewModel::setTopScoreAt,
                        onScan = onDetectClick,
                        onResume = onResumeLive,
                        onShare = onStub,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        BottomArea(
                            isFrozen = isFrozen,
                            processing = processing,
                            uiState = uiState,
                            onValueChange = viewModel::setTopScoreAt,
                            onScan = onDetectClick,
                            onResume = onResumeLive,
                            onShare = onStub,
                            landscape = true,
                            modifier = Modifier.fillMaxHeight().weight(1f),
                        )
                        TargetScanner(
                            frameSource = frameSource,
                            snapshotVm = snapshotVm,
                            uiState = uiState,
                            showDebug = showDebug,
                            onError = { viewModel.setError(it.message) },
                            onAutoDetect = onDetectClick,
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                        )
                    }
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

    }
    }
}

/** Slim top bar: app name + a toggle that reveals the raw detection overlay. */
@Composable
private fun MarkeraTopBar(
    showDebug: Boolean,
    onToggleDebug: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.markera_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleDebug) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = stringResource(R.string.markera_toggle_debug),
                tint = if (showDebug) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Area below (portrait) or beside (landscape) the viewport: a live hint before
 * the first scan, a "working" line while detecting, and the results (total +
 * editable score pickers + actions) once a frame has been scored.
 */
@Composable
private fun BottomArea(
    isFrozen: Boolean,
    processing: Boolean,
    uiState: MarkeraUiState,
    onValueChange: (index: Int, value: Int) -> Unit,
    onScan: () -> Unit,
    onResume: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            processing -> Text(
                text = stringResource(R.string.markera_analyzing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !isFrozen -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                LiveHint()
                PrimaryActionButton(
                    text = stringResource(R.string.markera_detect),
                    icon = Icons.Default.PhotoCamera,
                    onClick = onScan,
                )
            }
            else -> ResultsContent(uiState, onValueChange, onResume, onShare, landscape)
        }
    }
}

@Composable
private fun LiveHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.markera_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultsContent(
    uiState: MarkeraUiState,
    onValueChange: (index: Int, value: Int) -> Unit,
    onResume: () -> Unit,
    onShare: () -> Unit,
    landscape: Boolean,
) {
    val total = uiState.topScores.sumOf { if (it == SCORE_PICKER_INNER_TEN) 10 else it }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TotalBadge(total)
        if (landscape) {
            ScorePickerVerticalColumn(values = uiState.topScores, onValueChange = onValueChange)
        } else {
            ScorePickerHorizontalRow(values = uiState.topScores, onValueChange = onValueChange)
        }
        // New-shot action sits above Save/Share.
        PrimaryActionButton(
            text = stringResource(R.string.markera_resume_live),
            icon = Icons.Default.Refresh,
            onClick = onResume,
        )
        ActionRow(onShare)
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * The scored total, with the caliber chip beside it at the same height. Both
 * screens show their results through this, so the chip needs no other home.
 */
@Composable
fun TotalBadge(total: Int) {
    val recorder = LocalSeriesRecorder.current
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (recorder != null) CaliberChip(recorder, Modifier.fillMaxHeight())
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.markera_total_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = total.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** The caliber the next series is tagged with; tap to change it. */
@Composable
private fun CaliberChip(recorder: SeriesRecorder, modifier: Modifier = Modifier) {
    val caliber by recorder.caliber.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.clickable { recorder.openCaliberDialog() },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = caliber.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ActionRow(onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.markera_share))
        }
    }
}
