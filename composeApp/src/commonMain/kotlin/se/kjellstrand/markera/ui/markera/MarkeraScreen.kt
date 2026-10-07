package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.byHand
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.time.TimeSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import se.kjellstrand.markera.ui.LocalToast
import se.kjellstrand.markera.vision.CentreMethod
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import se.kjellstrand.markera.series.SaveStatus
import kotlinx.coroutines.withContext
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
import se.kjellstrand.markera.ui.TotalBadge
import se.kjellstrand.markera.ui.PrimaryActionButton
import se.kjellstrand.markera.ui.SecondaryActionButton

/**
 * The free-marking screen ("Fri markering"): frame a target, scan it, adjust
 * the pickers. The detectors and frame source are hoisted by the caller (the
 * nav host) so they are shared with the competition wizard.
 *
 * With an [importQueue] (and an [ImportFrameSource]) it scans the picked images
 * instead, one per turn: Save or Skip moves on; in auto mode a scan that scored
 * is saved without stopping (see [importDecision]).
 */
@Composable
fun MarkeraScreen(
    frameSource: FrameSource = rememberFrameSource(),
    scanController: TargetScanController,
    onBack: (() -> Unit)? = null,
    importQueue: ImportQueue<PickedImage>? = null,
    signedIn: Boolean = true,
) {
    // An import keeps its own pair, so a frame frozen in free marking never shows up in it.
    val vmKey = importQueue?.let { "import" }
    val viewModel: MarkeraViewModelImpl = viewModel(key = vmKey) { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel(key = vmKey) { MarkeraSnapshotViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val permission = rememberCameraPermission(frameSource)

    val errorInference = stringResource(Res.string.markera_error_inference)
    // Off by default — the clean result view. Toggle in the top bar reveals the
    // raw detection overlay (hole/digit boxes, row lines) for diagnostics.
    var showDebug by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    var showingContinuousHelp by remember { mutableStateOf(false) }
    // Long press only asks; the dialog's confirm is what removes the hole.
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    // Any hand edit of a result pauses the continuous rescan, so an auto-scan
    // cannot wipe work the user just did. Cleared when the frame is left.
    var edited by remember { mutableStateOf(false) }
    val onSetScore: (Int, Int) -> Unit = { index, pick ->
        edited = true
        scanController.setScore(viewModel, snapshotVm.snapshot, index, pick)
    }
    // Off = photo + geometry only, no ONNX pass: the user taps the holes in
    // themselves. Session-only, like the debug toggle.
    var detectHoles by remember { mutableStateOf(true) }
    // Phone on a tripod: rescan by itself whenever the target changes, so the
    // scores follow the series shot by shot. Session-only, like the toggles above.
    var continuous by remember { mutableStateOf(false) }
    val onDetectClick: () -> Unit = {
        scanController.startScan(
            frameSource, snapshotVm, viewModel, coroutineScope, errorInference, detectHoles,
        )
    }
    // Editing the frozen frame: tap adds the hole the detector missed (the reach
    // keeps a mis-tap next to a marked hole from doubling it), drag moves one,
    // long press removes one. Each edit re-publishes the pending series.
    val recorder = LocalSeriesRecorder.current
    val toast = LocalToast.current
    val haptics = LocalHapticFeedback.current
    val seriesFull = stringResource(Res.string.markera_series_full)
    val editing = remember(scanController) {
        HoleEditing(
            add = { x, y, reach ->
                // A sixth tap would be dropped without a word: say why instead.
                if (viewModel.uiState.value.scores.size >= SCORE_PICKER_COUNT) {
                    toast(seriesFull)
                } else {
                    // Read at tap time, so the remembered lambda sees the current caliber.
                    val caliber = recorder?.caliber?.value ?: Caliber.NONE
                    edited = true
                    scanController.addHit(viewModel, snapshotVm.snapshot, x, y, reach, caliber)
                }
            },
            move = { i, x, y ->
                edited = true
                scanController.moveHit(viewModel, snapshotVm.snapshot, i, x, y)
            },
            remove = { i ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                edited = true
                pendingDeleteIndex = i
            },
        )
    }

    val onResumeLive: () -> Unit = {
        // Read the pickers before clearResults() wipes them.
        val picks = uiState.topScores
        val scored = uiState.scores.isNotEmpty()
        edited = false
        snapshotVm.clear()
        viewModel.clearResults()
        // Leaving the frozen frame is what saves the scan, with the edited scores.
        recorder?.commit(picks)
        frameSource.onResumeLive()
        importQueue?.next(if (scored && signedIn) ImportDecision.Save else ImportDecision.Skip)
    }
    // Detection went wrong (ring, centre, holes): drop the scan and start over, saving nothing.
    // In an import that is Skip: the next image.
    val discard: () -> Unit = {
        edited = false
        snapshotVm.clear()
        viewModel.clearResults()
        recorder?.clear()
        frameSource.onResumeLive()
        importQueue?.next(ImportDecision.Skip)
    }
    if (importQueue != null && frameSource is ImportFrameSource && recorder != null) {
        ImportRunner(importQueue, frameSource, scanController, viewModel, snapshotVm, recorder, signedIn, errorInference, onBack)
    }
    // Hand edits are work: throwing them away asks first.
    var confirmingReset by remember { mutableStateOf(false) }
    val onReset: () -> Unit = { if (edited) confirmingReset = true else discard() }

    // Sample a low-rate luma feed; only a new hole-sized spot on an otherwise
    // unchanged, still scene runs the scan the button runs. The snapshot-state
    // reads are current on every tick, so the loop never has to restart.
    // Detection off stops it too: its switch is hidden then, so nothing else could.
    val watching = continuous && detectHoles && frameSource.supportsContinuousScan
    // Debug builds only: what the watch loop saw on its last tick, drawn over the viewport.
    var watchDebug by remember { mutableStateOf<WatchDebug?>(null) }
    LaunchedEffect(watching, frameSource) {
        if (!watching) return@LaunchedEffect
        val watch = NewHoleWatch()
        var ticks = 0
        var busy = 0
        var noFrame = 0
        var fires = 0
        var movingStreak = 0
        var recorded = 0
        var remeters = 0
        var meteredAt = TimeSource.Monotonic.markNow()
        fun report(status: String) {
            if (isDebugBuild) {
                watchDebug = WatchDebug("tick $ticks · busy $busy · no frame $noFrame · fired $fires · rec $recorded · meter $remeters\n$status", watch.last)
            }
        }
        frameSource.setWatching(true)
        try {
            while (true) {
                delay(CHANGE_SAMPLE_MS)
                ticks++
                val phase = viewModel.uiState.value.phase
                if (phase != ScanPhase.IDLE || edited) {
                    busy++
                    report(if (edited) "paused: a result was edited" else "busy: scan phase $phase")
                    continue
                }
                if (remeterDue(meteredAt.elapsedNow().inWholeMilliseconds, watch.last)) {
                    report("calibrating: metering the light again")
                    frameSource.remeter()
                    // Drop the frame taken while it metered. The watch keeps its
                    // reference and previous sample: the light fit bridges the step.
                    frameSource.takeLuma()
                    // ... but the next clean sample starts the reference mean over.
                    watch.lightChanged()
                    meteredAt = TimeSource.Monotonic.markNow()
                    remeters++
                    continue
                }
                val sample = frameSource.takeLuma()
                if (sample == null) {
                    noFrame++
                    report("no luma frame from the camera")
                    continue
                }
                val fired = withContext(Dispatchers.Default) { watch.offer(sample) }
                if (fired) fires++
                // Debug builds: keep the frames behind every fire and veto, and every
                // 5th sample of a moving streak, for ContinuousScanReplayTest.
                val verdict = watch.last
                movingStreak = if (verdict?.outcome == WatchOutcome.MOVING) movingStreak + 1 else 0
                val keep = verdict?.outcome == WatchOutcome.FIRE || verdict?.outcome == WatchOutcome.VETO ||
                    (movingStreak > 0 && movingStreak % 5 == 0)
                if (isDebugBuild && keep && verdict != null) {
                    val saved = withContext(Dispatchers.Default) {
                        verdict.recording()?.also(frameSource::recordWatch) != null
                    }
                    if (saved) recorded++
                }
                report(watch.last?.reason.orEmpty())
                if (fired) {
                    watch.reset()
                    onDetectClick()
                }
            }
        } finally {
            frameSource.setWatching(false)
            watchDebug = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge draws under the status bar / nav bar; keep content
            // clear of both, while the preview stays full-bleed horizontally.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) {
        if (!permission.granted) {
            CameraPermissionPrompt(onGrantClick = permission.request)
        } else {
            val isFrozen = snapshotVm.snapshot != null
            // An auto import never stops at the results: they are not the user's to act on.
            val processing = uiState.phase != ScanPhase.IDLE || importQueue?.auto == true
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = importQueue?.let {
                        stringResource(Res.string.import_progress, minOf(it.index + 1, it.items.size), it.items.size)
                    } ?: stringResource(Res.string.app_name),
                    onBack = onBack,
                    menuItems = listOfNotNull(
                        MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) { showingHelp = true },
                        // Developer tool: no entry point in release builds, and with it off the
                        // overlay drops the 6/7 ring and the centre cross too.
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
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    TargetScanner(
                        frameSource = frameSource,
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        showDebug = showDebug,
                        onError = viewModel::setError,
                        editing = editing,
                        keepPreviewAlive = watching,
                        modifier = Modifier.fillMaxSize(),
                    )
                    watchDebug?.let { WatchDebugOverlay(it, Modifier.fillMaxSize()) }
                    // Over the photo, below the top bar it used to cover.
                    uiState.error?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
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
                    // Debug builds only until it is proven on a real range (the switch starts off,
                    // so hiding it keeps the watch off in release).
                    continuousAvailable = isDebugBuild && frameSource.supportsContinuousScan && detectHoles,
                    continuous = continuous,
                    onToggleContinuous = { continuous = it },
                    onContinuousHelp = { showingContinuousHelp = true },
                    importing = importQueue != null,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(Res.string.markera_reset_title)) },
            text = { Text(stringResource(Res.string.markera_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    discard()
                }) { Text(stringResource(Res.string.markera_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(Res.string.history_delete_cancel))
                }
            },
        )
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
            sections = listOfNotNull(
                Res.string.help_scan_scan to Res.string.help_scan_scan_body,
                Res.string.help_scan_edit to Res.string.help_scan_edit_body,
                Res.string.help_scan_score to Res.string.help_scan_score_body,
                Res.string.help_scan_caliber to Res.string.help_scan_caliber_body,
                Res.string.help_scan_save to Res.string.help_scan_save_body,
                if (isDebugBuild) Res.string.help_scan_continuous to Res.string.help_scan_continuous_body else null,
                // Its menu entry exists in debug builds only.
                if (isDebugBuild) Res.string.help_scan_debug to Res.string.help_scan_debug_body else null,
            ),
            onDismiss = { showingHelp = false },
        )
    }

    if (showingContinuousHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_scan_continuous),
            sections = listOf(
                Res.string.help_scan_continuous_how to Res.string.help_scan_continuous_body,
                Res.string.help_scan_continuous_setup to Res.string.help_scan_continuous_setup_body,
                Res.string.help_scan_continuous_limits to Res.string.help_scan_continuous_limits_body,
            ),
            onDismiss = { showingContinuousHelp = false },
        )
    }
}

/**
 * Area below the viewport: a live hint before
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
    continuousAvailable: Boolean,
    continuous: Boolean,
    onToggleContinuous: (Boolean) -> Unit,
    onContinuousHelp: () -> Unit,
    importing: Boolean,
    modifier: Modifier = Modifier,
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
            // Between two images (or waiting for a caliber): no camera to scan with.
            !isFrozen && importing -> ScanChips()
            !isFrozen -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ScanChips()
                // Without hole detection the button only freezes the frame (and its
                // ring) for hand marking, so it says that instead of "Detect".
                PrimaryActionButton(
                    text = stringResource(
                        if (detectHoles) Res.string.markera_detect else Res.string.markera_mark,
                    ),
                    icon = Icons.Default.PhotoCamera,
                    onClick = onScan,
                )
                // Only the rows below scroll when the area runs short (a small phone,
                // the debug build's extra switch): the chips and Detect stay on screen.
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    LiveHint()
                    // The switches side by side, a divider between them.
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        DetectHolesToggle(checked = detectHoles, onCheckedChange = onToggleDetectHoles)
                        // With hole detection off there is nothing for a rescan to find.
                        if (continuousAvailable) {
                            VerticalDivider()
                            ScanToggle(
                                label = stringResource(Res.string.markera_continuous),
                                checked = continuous,
                                onCheckedChange = onToggleContinuous,
                                onHelp = onContinuousHelp,
                            )
                        }
                    }
                }
            }
            else -> ResultsContent(uiState, onResume, onReset, onSetScore, importing, Modifier.fillMaxSize())
        }
    }
}

/**
 * Caliber + tag, the same chips the results row carries, so both can be set
 * before a scan (and while one runs) instead of only after one.
 */
@Composable
internal fun ScanChips() {
    val recorder = LocalSeriesRecorder.current ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipTitle(stringResource(Res.string.badge_caliber)) { CaliberChip(recorder) }
        ChipTitle(stringResource(Res.string.badge_tag)) { TagChip(recorder) }
    }
}

/** A small title above a scan chip, as the result pill labels the same values. */
@Composable
private fun ChipTitle(title: String, chip: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        chip()
    }
}

/** Off: the scan stops after the geometry and the holes are placed by hand. */
@Composable
private fun DetectHolesToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ScanToggle(stringResource(Res.string.markera_detect_holes), checked, onCheckedChange)
}

/** A switch with its label above it, in the pre-scan row of switches. */
@Composable
private fun ScanToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onHelp: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        // Label and switch are one control: tapping either toggles, and it is read out as one.
        Column(
            Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        // The same (?) as the menu's help entry, opening this switch's own HelpDialog.
        if (onHelp != null) {
            IconButton(onClick = onHelp) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = stringResource(Res.string.help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiveHint() {
    Text(
        text = stringResource(Res.string.markera_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ResultsContent(
    uiState: MarkeraUiState,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onSetScore: (index: Int, pick: Int) -> Unit,
    importing: Boolean,
    modifier: Modifier = Modifier,
) {
    // In an import a rescan would find the same: throwing the scan away is skipping the image.
    val resetText = stringResource(if (importing) Res.string.import_skip else Res.string.markera_reset)
    // No ring or centre: nothing was scored and nothing can be, so say so and offer only a rescan.
    if (uiState.ring == null || uiState.centre?.method.let { it == null || it == CentreMethod.NONE }) {
        Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(Res.string.markera_no_ring),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            PrimaryActionButton(
                text = if (importing) resetText else stringResource(Res.string.markera_rescan),
                icon = Icons.Default.PhotoCamera,
                onClick = onReset,
            )
        }
        return
    }
    val total = uiState.topScores.sumOf { if (it == SCORE_PICKER_INNER_TEN) 10 else it }
    // Orange for every hole the user placed, moved or retyped, as on the photo.
    val manual = uiState.scores.map { it.byHand }
    Column(modifier) {
        // Badge + boxes centred while they fit, scrolled when they don't;
        // the action bar below never scrolls away.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TotalBadge(total)
                // A box with a hole takes a typed score (the hole stays put); empty slots just display.
                ScorePickerHorizontalRow(
                    values = uiState.topScores,
                    onValueChange = onSetScore,
                    letteredCount = uiState.scores.size,
                    editableCount = uiState.scores.size,
                    manual = manual,
                )
                // The photo's gestures have no other visible cue.
                Text(
                    text = stringResource(Res.string.markera_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            // Wide apart: Reset throws the scan away, Save keeps it.
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryActionButton(
                text = resetText,
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

/** The continuous-scan loop's last tick: counters + status, and the watch's verdict. */
private class WatchDebug(val text: String, val verdict: WatchVerdict?)

/**
 * Debug builds: the watch loop's status over the viewport, plus every changed
 * blob of the last comparison — green hole-sized, red a veto, grey a speck.
 * Draw-only, so the frozen frame's gestures still reach the scanner beneath.
 */
@Composable
private fun WatchDebugOverlay(debug: WatchDebug, modifier: Modifier) {
    val v = debug.verdict
    Box(modifier) {
        if (v != null) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 4.dp.toPx()
                val stroke = Stroke(2.dp.toPx())
                for (b in v.blobs) {
                    val a = v.upright(b.x, b.y)
                    val c = v.upright(b.x + b.width, b.y + b.height)
                    val color = when {
                        b.area < HOLE_MIN_AREA -> Color.LightGray
                        b.holeSized -> Color.Green
                        else -> Color.Red
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(minOf(a.x, c.x) * size.width - pad, minOf(a.y, c.y) * size.height - pad),
                        size = Size(abs(c.x - a.x) * size.width + 2 * pad, abs(c.y - a.y) * size.height + 2 * pad),
                        style = stroke,
                    )
                }
            }
        }
        val frame = v?.let { "\nframe ${it.frameWidth}x${it.frameHeight} rot ${it.rotation}° · ${it.blobs.size} blobs" }.orEmpty()
        Text(
            text = debug.text + frame,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(4.dp),
        )
    }
}

/** A frame-pixel point turned upright, as 0..1 of the square viewport. */
private fun WatchVerdict.upright(x: Int, y: Int): Offset {
    val u = x.toFloat() / frameWidth
    val w = y.toFloat() / frameHeight
    return when (rotation) {
        90 -> Offset(1 - w, u)
        180 -> Offset(1 - u, 1 - w)
        270 -> Offset(w, 1 - u)
        else -> Offset(u, w)
    }
}

/**
 * Works [queue] through: each image on its turn is loaded, dated (its EXIF date
 * becomes the series time) and scanned. In auto mode the result is then decided
 * here, saved or held for review, and the next image follows; otherwise the
 * results wait for Save or Skip. When the queue runs out: one summary, and back.
 */
@Composable
private fun ImportRunner(
    queue: ImportQueue<PickedImage>,
    source: ImportFrameSource,
    scanController: TargetScanController,
    viewModel: MarkeraViewModelImpl,
    snapshotVm: MarkeraSnapshotViewModel,
    recorder: SeriesRecorder,
    signedIn: Boolean,
    errorMessage: String,
    onBack: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    LaunchedEffect(queue.items, queue.index) {
        val item = queue.current
        if (item == null) {
            // ponytail: waits for the last save only, so an earlier one landing late can still toast after this.
            withTimeoutOrNull(10_000) { recorder.status.first { it != SaveStatus.Saving } }
            toast(getString(Res.string.import_summary, queue.saved, queue.skipped))
            onBack?.invoke()
            return@LaunchedEffect
        }
        // A commit that waits for the caliber dialog would be dropped by the next scan: ask up front.
        if (signedIn && recorder.caliber.value == Caliber.NONE) {
            recorder.openCaliberDialog()
            recorder.caliber.first { it != Caliber.NONE }
        }
        val loaded = item.load()
        if (loaded == null) {
            queue.next(ImportDecision.Skip)
            return@LaunchedEffect
        }
        recorder.takenAt = loaded.takenAt
        source.image = loaded.image
        // A scan another screen left running still holds the detector.
        while (!scanController.startScan(source, snapshotVm, viewModel, scope, errorMessage)) delay(100)
        val state = viewModel.uiState.first { it.phase == ScanPhase.IDLE }
        if (!queue.auto) return@LaunchedEffect
        val decision = importDecision(true, state.scores.isNotEmpty(), recorder.caliber.value, signedIn)
        if (decision == ImportDecision.Save) recorder.commit(state.topScores)
        snapshotVm.clear()
        viewModel.clearResults()
        source.image = null
        queue.next(decision)
    }
    // Leaving drops what is left, the frame on screen included.
    DisposableEffect(queue) {
        onDispose {
            recorder.takenAt = null
            recorder.clear()
            snapshotVm.clear()
            viewModel.clearResults()
        }
    }
}
