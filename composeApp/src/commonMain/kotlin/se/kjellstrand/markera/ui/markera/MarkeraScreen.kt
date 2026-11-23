package se.kjellstrand.markera.ui.markera

import se.kjellstrand.markera.vision.byHand
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import se.kjellstrand.markera.series.SaveStatus
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
 * nav host) so they outlive the screen and are shared with Import images.
 *
 * With an [importQueue] (and an [ImportFrameSource]) it scans the picked images
 * instead, one per turn: Save or Skip moves on; in auto mode a scan that scored
 * is saved without stopping (see [importDecision]).
 */
/** The square photo/preview, for UI tests that tap the frozen frame. */
const val SCAN_VIEWPORT_TAG = "scan-viewport"

@Composable
fun MarkeraScreen(
    frameSource: FrameSource = rememberFrameSource(),
    scanController: TargetScanController,
    onBack: (() -> Unit)? = null,
    importQueue: ImportQueue<PickedImage>? = null,
    /** UI tests: the permission gate in place of the system's; fixed for the screen's lifetime. */
    cameraPermission: (@Composable () -> CameraPermissionState)? = null,
) {
    // An import keeps its own pair, so a frame frozen in free marking never shows up in it.
    val vmKey = importQueue?.let { "import" }
    val viewModel: MarkeraViewModelImpl = viewModel(key = vmKey) { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel(key = vmKey) { MarkeraSnapshotViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val permission = cameraPermission?.invoke() ?: rememberCameraPermission(frameSource)

    val errorInference = stringResource(Res.string.markera_error_inference)
    // Off by default — the clean result view. Toggle in the top bar reveals the
    // raw detection overlay (hole/digit boxes, row lines) for diagnostics.
    var showDebug by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // Long press only asks; the dialog's confirm is what removes the hole.
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    // Any hand edit of a result makes Reset ask first. Cleared when the frame is left.
    var edited by remember { mutableStateOf(false) }
    // A Save waiting for its local result (the JPEG encode and the write). The frame is frozen
    // until it answers: a second Save or a Reset would leave it (and move an import on) twice,
    // and an edit would replace the pending scan being saved.
    var saving by remember { mutableStateOf(false) }
    val onSetScore: (Int, Int) -> Unit = setScore@{ index, pick ->
        if (saving) return@setScore
        edited = true
        scanController.setScore(viewModel, snapshotVm.snapshot, index, pick)
    }
    // Off = photo + geometry only, no ONNX pass: the user taps the holes in
    // themselves. Session-only, like the debug toggle.
    var detectHoles by remember { mutableStateOf(true) }
    // The scan found no ring or centre and the user is fitting one by hand; null otherwise.
    var placing by remember { mutableStateOf<GeometryDraft?>(null) }
    val onDetectClick: () -> Unit = {
        placing = null
        scanController.startScan(
            frameSource, snapshotVm, viewModel, coroutineScope, errorInference, detectHoles,
        )
    }
    // Editing the frozen frame: tap adds the hole the detector missed (the reach
    // keeps a mis-tap next to a marked hole from doubling it), drag moves one,
    // long press removes one. Each edit re-publishes the pending series.
    val recorder = LocalSeriesRecorder.current
    val haptics = LocalHapticFeedback.current
    val editing = remember(scanController) {
        HoleEditing(
            add = add@{ x, y, reach ->
                if (saving) return@add
                // Read at tap time, so the remembered lambda sees the current caliber.
                val caliber = recorder?.caliber?.value ?: Caliber.NONE
                edited = true
                scanController.addHit(viewModel, snapshotVm.snapshot, x, y, reach, caliber)
            },
            move = move@{ i, x, y ->
                if (saving) return@move -1 // nothing moved
                edited = true
                scanController.moveHit(viewModel, snapshotVm.snapshot, i, x, y)
            },
            remove = remove@{ i ->
                if (saving) return@remove
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                edited = true
                pendingDeleteIndex = i
            },
        )
    }

    val onResumeLive: () -> Unit = resume@{
        if (saving) return@resume
        // Saving is what leaves the frozen frame, with the edited scores; the commit runs
        // before clearResults() wipes the pickers.
        val picks = uiState.topScores
        val scored = uiState.scores.isNotEmpty()
        val save = recorder?.commit(picks)
        saving = true
        coroutineScope.launch {
            val decision = try {
                // Without a recorder (previews) nothing is saved; the scan counts as it did.
                if (recorder == null || save == null) {
                    if (scored) ImportDecision.Save else ImportDecision.Skip
                } else {
                    afterSave(save.await(), recorder.retainedAfterFailure)
                }
            } finally {
                saving = false
            }
            // Not kept on the phone, but retained: the frame and results stay for Save again,
            // under the failure toast. An import stays on this image; Skip still moves on.
            if (decision == null) return@launch
            edited = false
            placing = null
            snapshotVm.clear()
            viewModel.clearResults()
            frameSource.onResumeLive()
            importQueue?.next(decision)
        }
    }
    // Detection went wrong (ring, centre, holes): drop the scan and start over, saving nothing.
    // In an import that is Skip: the next image.
    val discard: () -> Unit = discard@{
        if (saving) return@discard
        edited = false
        placing = null
        snapshotVm.clear()
        viewModel.clearResults()
        recorder?.clear()
        frameSource.onResumeLive()
        importQueue?.next(ImportDecision.Skip)
    }
    if (importQueue != null && frameSource is ImportFrameSource && recorder != null) {
        ImportRunner(importQueue, frameSource, scanController, viewModel, snapshotVm, recorder, errorInference, onBack)
    }
    // Hand edits are work: throwing them away asks first.
    var confirmingReset by remember { mutableStateOf(false) }
    val onReset: () -> Unit = { if (edited) confirmingReset = true else discard() }
    // A scan on screen that is not saved yet (scored, edited, or a ring being placed by hand):
    // leaving drops it, so Back (the top bar's and the system's) asks first. An auto import
    // never stops on a result, so it has nothing of the user's to lose.
    // Read only where it is used (the lambda, and the scope below that already redraws with the
    // scan): a read here would recompose the whole screen with every scan state change.
    var confirmingLeave by remember { mutableStateOf(false) }
    fun unsavedScan() = snapshotVm.snapshot != null && importQueue?.auto != true &&
        (uiState.scores.isNotEmpty() || edited || placing != null)
    val guardedBack: (() -> Unit)? = onBack?.let { back -> { if (unsavedScan()) confirmingLeave = true else back() } }

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
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = onBack != null && unsavedScan(),
                onBackCompleted = { confirmingLeave = true },
            )
            // An auto import never stops at the results: they are not the user's to act on.
            val processing = uiState.phase != ScanPhase.IDLE || importQueue?.auto == true
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = importQueue?.let {
                        stringResource(Res.string.import_progress, minOf(it.index + 1, it.items.size), it.items.size)
                    } ?: stringResource(Res.string.home_free_marking),
                    onBack = guardedBack,
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
                Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag(SCAN_VIEWPORT_TAG)) {
                    TargetScanner(
                        frameSource = frameSource,
                        snapshotVm = snapshotVm,
                        uiState = uiState,
                        showDebug = showDebug,
                        onError = viewModel::setError,
                        editing = editing,
                        modifier = Modifier.fillMaxSize(),
                    )
                    placing?.let { draft ->
                        GeometryPlacer(
                            draft = draft,
                            imageWidth = uiState.imageWidth,
                            imageHeight = uiState.imageHeight,
                            onChange = { placing = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // How to aim, on the live picture itself, so the controls below keep the room.
                    if (!isFrozen && !processing && placing == null && importQueue == null) {
                        LiveHint(Modifier.align(Alignment.BottomCenter).padding(12.dp))
                    }
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
                    importing = importQueue != null,
                    placing = placing != null,
                    onManualMark = { placing = initialGeometryDraft(uiState) },
                    canFinishPlacing = placing?.usable == true,
                    onPlaceDone = {
                        val d = placing
                        val ring = d?.ring
                        if (d != null && ring != null && d.usable) {
                            edited = true
                            scanController.applyManualGeometry(viewModel, snapshotVm.snapshot, d.centre, ring)
                            placing = null
                        }
                    },
                    onPlaceCancel = { placing = null },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(stringResource(Res.string.markera_leave_title)) },
            text = {
                Text(
                    stringResource(
                        if (importQueue != null) Res.string.markera_leave_import_message else Res.string.markera_leave_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    onBack?.invoke()
                }) { Text(stringResource(Res.string.markera_leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text(stringResource(Res.string.history_delete_cancel))
                }
            },
        )
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
                // Its menu entry exists in debug builds only.
                if (isDebugBuild) Res.string.help_scan_debug to Res.string.help_scan_debug_body else null,
            ),
            onDismiss = { showingHelp = false },
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
    importing: Boolean,
    placing: Boolean,
    canFinishPlacing: Boolean,
    onManualMark: () -> Unit,
    onPlaceDone: () -> Unit,
    onPlaceCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            placing -> PlacementContent(canFinishPlacing, onPlaceDone, onPlaceCancel)
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
                // The switch scrolls when the area runs short (a small phone):
                // the chips and Detect stay on screen.
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DetectHolesToggle(checked = detectHoles, onCheckedChange = onToggleDetectHoles)
                }
            }
            else -> ResultsContent(uiState, onResume, onReset, onSetScore, importing, onManualMark, Modifier.fillMaxSize())
        }
    }
}

/** While the user fits the 6/7 ring by hand: how, and Done (only while the points make an ellipse) / Cancel. */
@Composable
private fun PlacementContent(canFinish: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(Res.string.markera_place_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryActionButton(
                text = stringResource(Res.string.dialog_cancel),
                icon = Icons.Default.Close,
                onClick = onCancel,
            )
            PrimaryActionButton(
                text = stringResource(Res.string.markera_place_done),
                icon = Icons.Default.Check,
                onClick = onDone,
                enabled = canFinish,
                modifier = Modifier.weight(1f),
            )
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
    ScanToggle(
        stringResource(Res.string.markera_detect_holes),
        stringResource(Res.string.markera_detect_holes_body),
        checked,
        onCheckedChange,
    )
}

/** A full-width switch row: label, a line on what it does, the switch. */
@Composable
private fun ScanToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Label and switch are one control: tapping either toggles, and it is read out as one.
        Row(
            Modifier.weight(1f).toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** How to aim, as a see-through pill over the bottom of the live picture. */
@Composable
private fun LiveHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.markera_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ResultsContent(
    uiState: MarkeraUiState,
    onResume: () -> Unit,
    onReset: () -> Unit,
    onSetScore: (index: Int, pick: Int) -> Unit,
    importing: Boolean,
    onManualMark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // In an import a rescan would find the same: throwing the scan away is skipping the image.
    val resetText = stringResource(if (importing) Res.string.import_skip else Res.string.markera_reset)
    // No ring or centre: nothing was scored, so say so and offer a rescan, or placing
    // the ring and centre by hand (the holes the scan found are then scored against them).
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
            // Only a frame that actually froze can be marked (a failed capture has none).
            if (uiState.imageWidth > 0 && uiState.imageHeight > 0) {
                PrimaryActionButton(
                    text = stringResource(Res.string.markera_manual_mark),
                    icon = Icons.Default.Edit,
                    onClick = onManualMark,
                )
            }
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

/** The tag the next series is saved with; tap to change it. Untagged offers to add one. */
@Composable
private fun TagChip(recorder: SeriesRecorder) {
    val tag by recorder.tag.collectAsState()
    AppChip(
        tag != null,
        recorder::openTagDialog,
        tag ?: stringResource(Res.string.badge_add_tag),
        Modifier.widthIn(max = 120.dp),
    )
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
    errorMessage: String,
    onBack: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    LaunchedEffect(queue.pass, queue.index) {
        val item = queue.current
        if (item == null) {
            // ponytail: waits for the last save only, so an earlier one landing late can still toast after this.
            withTimeoutOrNull(10_000) { recorder.status.first { it != SaveStatus.Saving } }
            toast(
                getString(
                    Res.string.import_summary,
                    getPluralString(Res.plurals.import_summary_saved, queue.saved, queue.saved),
                    getPluralString(Res.plurals.import_summary_skipped, queue.skipped, queue.skipped),
                ),
            )
            onBack?.invoke()
            return@LaunchedEffect
        }
        // A commit that waits for the caliber dialog would be dropped by the next scan: ask up front.
        if (recorder.caliber.value == Caliber.NONE) {
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
        var decision = importDecision(true, state.scores.isNotEmpty(), recorder.caliber.value)
        // Counted saved only once its row and photo are durably kept; otherwise reviewed by hand.
        if (decision == ImportDecision.Save && !recorder.commit(state.topScores).await()) decision = ImportDecision.Review
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
