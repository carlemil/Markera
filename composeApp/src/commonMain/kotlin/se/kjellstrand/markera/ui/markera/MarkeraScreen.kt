package se.kjellstrand.markera.ui.markera

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.ui.AppChip
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.MenuItem
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Tune
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.history.DeleteHoleDialog

/**
 * The free-marking screen ("Fri markering"): frame a target, scan it, adjust
 * the pickers. The detectors and frame source are hoisted by the caller (the
 * nav host) so they are shared with the competition wizard.
 */
@Composable
fun MarkeraScreen(
    frameSource: FrameSource = rememberFrameSource(),
    scanController: TargetScanController,
    onBack: (() -> Unit)? = null,
) {
    val viewModel: MarkeraViewModelImpl = viewModel { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel { MarkeraSnapshotViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val permission = rememberCameraPermission(frameSource)

    val errorInference = stringResource(Res.string.markera_error_inference)
    // Off by default — the clean result view. Toggle in the top bar reveals the
    // raw detection overlay (hole/digit boxes, row lines) for diagnostics.
    var showDebug by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // Long press only asks; the dialog's confirm is what removes the hole.
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    val onSetScore: (Int, Int) -> Unit = { index, pick ->
        scanController.setScore(viewModel, snapshotVm.snapshot, index, pick)
    }
    // Off = photo + geometry only, no ONNX pass: the user taps the holes in
    // themselves. Session-only, like the debug toggle.
    var detectHoles by remember { mutableStateOf(true) }
    val onDetectClick: () -> Unit = {
        scanController.startScan(
            frameSource, snapshotVm, viewModel, coroutineScope, errorInference, detectHoles,
        )
    }
    // Editing the frozen frame: tap adds the hole the detector missed (the reach
    // keeps a mis-tap next to a marked hole from doubling it), drag moves one,
    // long press removes one. Each edit re-publishes the pending series.
    val recorder = LocalSeriesRecorder.current
    val editing = remember(scanController) {
        HoleEditing(
            add = { x, y, reach ->
                // Read at tap time, so the remembered lambda sees the current caliber.
                val caliber = recorder?.caliber?.value ?: Caliber.NONE
                scanController.addHit(viewModel, snapshotVm.snapshot, x, y, reach, caliber)
            },
            move = { i, x, y -> scanController.moveHit(viewModel, snapshotVm.snapshot, i, x, y) },
            remove = { i -> pendingDeleteIndex = i },
        )
    }

    val onResumeLive: () -> Unit = {
        // Read the pickers before clearResults() wipes them.
        val picks = uiState.topScores
        snapshotVm.clear()
        viewModel.clearResults()
        // Leaving the frozen frame is what saves the scan, with the edited scores.
        recorder?.commit(picks)
        frameSource.onResumeLive()
    }
    // Detection went wrong (ring, centre, holes): drop the scan and start over, saving nothing.
    val onReset: () -> Unit = {
        snapshotVm.clear()
        viewModel.clearResults()
        recorder?.clear()
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
                AppTopBar(
                    title = stringResource(Res.string.app_name),
                    onBack = onBack,
                    menuItems = listOfNotNull(
                        MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) { showingHelp = true },
                        // Developer tool: no entry point in release builds (the overlay itself stays).
                        if (isDebugBuild) {
                            MenuItem(
                                if (showDebug) Icons.Default.Tune else Icons.Outlined.Tune,
                                stringResource(Res.string.markera_toggle_debug),
                            ) { showDebug = !showDebug }
                        } else {
                            null
                        },
                    ),
                )
                if (isPortrait) {
                    TargetScanner(
                        frameSource = frameSource,
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        showDebug = showDebug,
                        onError = { viewModel.setError(it.message) },
                        editing = editing,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    BottomArea(
                        isFrozen = isFrozen,
                        processing = processing,
                        uiState = uiState,
                        onScan = onDetectClick,
                        onResume = onResumeLive,
                        onReset = onReset,
                        onSetScore = onSetScore,
                        detectHoles = detectHoles,
                        onToggleDetectHoles = { detectHoles = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        BottomArea(
                            isFrozen = isFrozen,
                            processing = processing,
                            uiState = uiState,
                            onScan = onDetectClick,
                            onResume = onResumeLive,
                            onReset = onReset,
                            onSetScore = onSetScore,
                            detectHoles = detectHoles,
                            onToggleDetectHoles = { detectHoles = it },
                            landscape = true,
                            modifier = Modifier.fillMaxHeight().weight(1f),
                        )
                        TargetScanner(
                            frameSource = frameSource,
                            snapshotVm = snapshotVm,
                            uiState = uiState,
                            showDebug = showDebug,
                            onError = { viewModel.setError(it.message) },
                            editing = editing,
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

    // `removeHit` ignores an out-of-range index, so no extra guard is needed here.
    pendingDeleteIndex?.let { index ->
        DeleteHoleDialog(
            onDismiss = { pendingDeleteIndex = null },
            onConfirm = {
                pendingDeleteIndex = null
                scanController.removeHit(viewModel, snapshotVm.snapshot, index)
            },
        )
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_scan_title),
            sections = listOf(
                Res.string.help_scan_scan to Res.string.help_scan_scan_body,
                Res.string.help_scan_edit to Res.string.help_scan_edit_body,
                Res.string.help_scan_score to Res.string.help_scan_score_body,
                Res.string.help_scan_caliber to Res.string.help_scan_caliber_body,
                Res.string.help_scan_save to Res.string.help_scan_save_body,
                Res.string.help_scan_debug to Res.string.help_scan_debug_body,
            ),
            onDismiss = { showingHelp = false },
        )
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
    onScan: () -> Unit,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onSetScore: (index: Int, pick: Int) -> Unit,
    detectHoles: Boolean,
    onToggleDetectHoles: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            // The chips stay up while the detector runs: caliber and tag are
            // read when the series is saved, so changing them mid-scan is safe.
            processing -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ScanChips()
                Text(
                    text = stringResource(Res.string.markera_analyzing),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !isFrozen -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ScanChips()
                LiveHint()
                DetectHolesToggle(checked = detectHoles, onCheckedChange = onToggleDetectHoles)
                // Without hole detection the button only freezes the frame (and its
                // ring) for hand marking, so it says that instead of "Detect".
                PrimaryActionButton(
                    text = stringResource(
                        if (detectHoles) Res.string.markera_detect else Res.string.markera_mark,
                    ),
                    icon = Icons.Default.PhotoCamera,
                    onClick = onScan,
                )
            }
            else -> ResultsContent(uiState, onResume, onReset, onSetScore, landscape, Modifier.fillMaxSize())
        }
    }
}

/**
 * Caliber + tag, the same chips the results row carries, so both can be set
 * before a scan (and while one runs) instead of only after one.
 */
@Composable
private fun ScanChips() {
    val recorder = LocalSeriesRecorder.current ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CaliberChip(recorder)
        TagChip(recorder)
    }
}

/** Off: the scan stops after the geometry and the holes are placed by hand. */
@Composable
private fun DetectHolesToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.markera_detect_holes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            text = stringResource(Res.string.markera_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultsContent(
    uiState: MarkeraUiState,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onSetScore: (index: Int, pick: Int) -> Unit,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val total = uiState.topScores.sumOf { if (it == SCORE_PICKER_INNER_TEN) 10 else it }
    val manual = uiState.scores.map { it.manual }
    Column(modifier) {
        // Badge + boxes centred while they fit, scrolled when they don't (landscape);
        // the action bar below never scrolls away.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TotalBadge(total)
                if (landscape) {
                    ScorePickerVerticalColumn(
                        values = uiState.topScores,
                        letteredCount = uiState.scores.size,
                        manual = manual,
                    )
                } else {
                    // A box with a hole takes a typed score (the hole stays put); empty slots just display.
                    ScorePickerHorizontalRow(
                        values = uiState.topScores,
                        onValueChange = onSetScore,
                        letteredCount = uiState.scores.size,
                        editableCount = uiState.scores.size,
                        manual = manual,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryActionButton(
                text = stringResource(Res.string.markera_reset),
                icon = Icons.Default.Refresh,
                onClick = onReset,
            )
            PrimaryActionButton(
                text = stringResource(Res.string.markera_resume_live),
                icon = Icons.Default.Save,
                onClick = onResume,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * [PrimaryActionButton]'s icon-only outlined sibling, left of it; [text] is the
 * accessibility label. The primary beside it takes the rest of the row (`weight(1f)`).
 */
@Composable
fun SecondaryActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedIconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = text)
    }
}

/**
 * The scored total with the pending series' caliber and tag, tappable to open the
 * recorder's dialogs. The scan screen and the wizard's Confirm step use this.
 */
@Composable
fun TotalBadge(total: Int) {
    val recorder = LocalSeriesRecorder.current
        ?: return TotalBadge(total, caliber = null, tag = null)
    val caliber by recorder.caliber.collectAsState()
    val tag by recorder.tag.collectAsState()
    TotalBadge(total, caliber.label, tag, recorder::openCaliberDialog, recorder::openTagDialog)
}

/**
 * The one result header: caliber | tag | total in one pill, shared by the scan
 * screen, wizard, Serie page and Historik. A null [caliber] leaves only the total;
 * a null click leaves that segment untappable; [compact] is the list-row size.
 */
@Composable
fun TotalBadge(
    total: Int,
    caliber: String?,
    tag: String?,
    onCaliberClick: (() -> Unit)? = null,
    onTagClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (caliber != null) {
                PillSegment(caliber.takeUnless { it.isBlank() || it == Caliber.NONE.label } ?: "–", onCaliberClick, compact)
                VerticalDivider()
                // Capped: a 32-character tag must not push the total off the row.
                PillSegment(tag?.takeUnless { it.isBlank() } ?: "–", onTagClick, compact, if (compact) 96.dp else 120.dp)
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 2.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = total.toString(),
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The caliber the next series is tagged with; tap to change it. Filled once one is set. */
@Composable
private fun CaliberChip(recorder: SeriesRecorder) {
    val caliber by recorder.caliber.collectAsState()
    val label = if (caliber == Caliber.NONE) "–" else caliber.label
    AppChip(caliber != Caliber.NONE, recorder::openCaliberDialog, label)
}

/** The tag the next series is saved with; tap to change it. Untagged shows –. */
@Composable
private fun TagChip(recorder: SeriesRecorder) {
    val tag by recorder.tag.collectAsState()
    AppChip(tag != null, recorder::openTagDialog, tag ?: "–", Modifier.widthIn(max = 120.dp))
}

/** A caliber or tag section of the [TotalBadge] pill; tappable when [onClick] is set. */
@Composable
private fun PillSegment(label: String, onClick: (() -> Unit)?, compact: Boolean, maxWidth: Dp = Dp.Unspecified) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = maxWidth)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (compact) 8.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
