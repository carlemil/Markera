package se.kjellstrand.markera.ui.competition

import se.kjellstrand.markera.ui.AppTopBar
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import se.kjellstrand.markera.ui.StateMessage
import se.kjellstrand.markera.ui.stats.SectionHeader
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.markera.CameraPermissionPrompt
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.MarkeraSnapshotViewModel
import se.kjellstrand.markera.ui.markera.MarkeraViewModelImpl
import se.kjellstrand.markera.ui.markera.PrimaryActionButton
import se.kjellstrand.markera.ui.markera.SecondaryActionButton
import se.kjellstrand.markera.ui.markera.ScanPhase
import se.kjellstrand.markera.ui.markera.ScorePickerHorizontalRow
import se.kjellstrand.markera.ui.markera.ScoreMiniRow
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.TargetScanner
import se.kjellstrand.markera.ui.markera.TotalBadge
import se.kjellstrand.markera.ui.markera.rememberCameraPermission
import se.kjellstrand.markera.webshooter.MarkingLogic
import se.kjellstrand.markera.webshooter.ShotMapping
import se.kjellstrand.markera.webshooter.WebshooterServices
import se.kjellstrand.markera.webshooter.api.dto.ResultDto

/**
 * The competition marking wizard: for each lane in the group, scan the target
 * with the camera, present the auto-scored series as editable defaults, save
 * to webshooter, and advance — series by series — mirroring the web's mobile
 * scoring flow.
 */
@Composable
fun MarkingWizardScreen(
    services: WebshooterServices,
    frameSource: FrameSource,
    scanController: TargetScanController,
    competitionId: Int,
    groupGuid: String,
    groupName: String,
    onExit: () -> Unit,
) {
    val wizardVm = remember(groupGuid) {
        MarkingWizardViewModel(
            scoringRepository = services.scoringRepository,
            sessionRepository = services.sessionRepository,
            competitionId = competitionId,
            groupGuid = groupGuid,
            nowIso = {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())
            },
            clientNonce = {
                "mv2-${System.currentTimeMillis()}-${Random.nextLong().toString(36).takeLast(8)}"
            },
        )
    }
    DisposableEffect(wizardVm) {
        onDispose { wizardVm.dispose() }
    }

    val state by wizardVm.uiState.collectAsState()

    // Shared scanner state (same instances as free marking; flows are serial).
    val markeraVm: MarkeraViewModelImpl = viewModel { MarkeraViewModelImpl() }
    val snapshotVm: MarkeraSnapshotViewModel = viewModel { MarkeraSnapshotViewModel() }
    val markeraState by markeraVm.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val permission = rememberCameraPermission(frameSource)
    val errorInference = stringResource(Res.string.markera_error_inference)

    val recorder = LocalSeriesRecorder.current
    // Back to a live viewfinder; [save] the scan we are leaving behind (moving
    // on to another lane) or drop it (a rescan of this one).
    val resetScanner = { save: Boolean ->
        // Read the pickers before clearResults() wipes them.
        val picks = markeraState.topScores
        snapshotVm.clear()
        markeraVm.clearResults()
        if (save) recorder?.commit(picks) else recorder?.clear()
        frameSource.onResumeLive()
    }
    val startScan = {
        scanController.startScan(frameSource, snapshotVm, markeraVm, scope, errorInference)
        Unit
    }

    // A fresh lane (or an explicit rescan) always starts from a live viewfinder.
    LaunchedEffect(state.stationIndex, state.laneIndex) { resetScanner(true) }
    LaunchedEffect(state.step) {
        if (state.step is LaneStep.Entering && snapshotVm.snapshot != null) resetScanner(false)
    }

    // A scan is complete when the pipeline returns to IDLE with a frozen frame
    // and no error — hand the auto-scored pickers to the wizard as defaults.
    LaunchedEffect(markeraState.phase, snapshotVm.snapshot != null) {
        if (state.step is LaneStep.Entering &&
            markeraState.phase == ScanPhase.IDLE &&
            snapshotVm.snapshot != null &&
            markeraState.error == null &&
            markeraState.imageWidth > 0
        ) {
            wizardVm.onScanComplete(markeraState.topScores)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            val stationCount = state.context?.stations?.size ?: 0
            AppTopBar(
                title = groupName,
                subtitle = state.station?.let {
                    stringResource(Res.string.wizard_series, it.sortorder, stationCount)
                },
                onBack = onExit,
            )
            when {
                state.loading -> StateMessage(loading = true)

                state.loadError -> StateMessage(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(Res.string.state_error_title),
                    hint = stringResource(Res.string.error_network),
                    actionLabel = stringResource(Res.string.retry),
                    onAction = wizardVm::load,
                )

                state.notActive -> NotActiveContent(onRetry = wizardVm::load)

                state.unsupportedShots != null -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    state.unsupportedShots?.let { shots ->
                        Text(
                            text = stringResource(Res.string.wizard_shots_unsupported, shots),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                state.step is LaneStep.StationDone -> StationSummaryContent(
                    state = state,
                    onGoToLane = wizardVm::backToLanes,
                    onNextStation = wizardVm::nextStation,
                )

                !permission.granted -> CameraPermissionPrompt(onGrantClick = permission.request)

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    LaneStrip(state = state, onLaneClick = wizardVm::goToLane)
                    state.resumeHint?.let { hint ->
                        ResumeHintBanner(
                            hint = hint,
                            onGo = wizardVm::goToResumeHint,
                            onDismiss = wizardVm::dismissResumeHint,
                        )
                    }
                    LaneHeader(state = state)
                    // The viewport stays up for the scan-centred steps so the
                    // frozen frame with its overlay is visible behind confirm.
                    val step = state.step
                    if (step is LaneStep.Entering || step is LaneStep.Confirm ||
                        step is LaneStep.Saving || step is LaneStep.Saved
                    ) {
                        TargetScanner(
                            frameSource = frameSource,
                            snapshotVm = snapshotVm,
                            uiState = markeraState,
                            showDebug = false,
                            onError = { markeraVm.setError(it.message) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                    markeraState.error?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(8.dp),
                        )
                    }
                    StepContent(
                        state = state,
                        wizardVm = wizardVm,
                        processing = markeraState.phase != ScanPhase.IDLE,
                        onScan = startScan,
                        onRescan = {
                            resetScanner(false)
                            wizardVm.rescan()
                        },
                    )
                }
            }
        }
    }
}

// ---- Lane strip -----------------------------------------------------------

/** One chip per lane: done / claimed / current / open at a glance. */
@Composable
private fun LaneStrip(state: WizardUiState, onLaneClick: (Int) -> Unit) {
    val context = state.context ?: return
    val station = state.station ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        context.lanes.forEachIndexed { index, entry ->
            val scored = MarkingLogic.isScored(
                MarkingLogic.resultFor(entry.signup, station.sortorder)
            )
            val claimed = state.claims.containsKey(entry.lane)
            val current = index == state.laneIndex
            val containerColor = when {
                scored -> MaterialTheme.colorScheme.primaryContainer
                claimed -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                onClick = { onLaneClick(index) },
                color = containerColor,
                shape = MaterialTheme.shapes.small,
                modifier = if (current) {
                    Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small,
                    )
                } else {
                    Modifier
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = entry.lane.toString(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (scored) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---- Lane header ----------------------------------------------------------

@Composable
private fun LaneHeader(state: WizardUiState) {
    val entry = state.laneEntry ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = entry.lane.toString(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = entry.signup.user?.fullname ?: "?",
                style = MaterialTheme.typography.titleMedium,
            )
            val details = listOfNotNull(
                entry.signup.weaponclass?.classname,
                entry.signup.club?.name,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Step content ---------------------------------------------------------

@Composable
private fun StepContent(
    state: WizardUiState,
    wizardVm: MarkingWizardViewModel,
    processing: Boolean,
    onScan: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.duplicateBy?.let { name ->
            Text(
                text = stringResource(Res.string.wizard_duplicate, name),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (val step = state.step) {
            is LaneStep.Entering -> {
                if (processing) {
                    Text(
                        text = stringResource(Res.string.markera_analyzing),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PrimaryActionButton(
                        text = stringResource(Res.string.wizard_scan),
                        icon = Icons.Default.PhotoCamera,
                        onClick = onScan,
                    )
                }
            }

            is LaneStep.Confirm -> {
                val total = ShotMapping.points(step.shots)
                TotalBadge(total)
                ScorePickerHorizontalRow(
                    values = step.shots,
                    onValueChange = wizardVm::updateShot,
                )
                if (state.saveError) {
                    Text(
                        text = stringResource(Res.string.wizard_save_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryActionButton(
                        text = stringResource(Res.string.wizard_rescan),
                        icon = Icons.Default.Refresh,
                        onClick = onRescan,
                    )
                    PrimaryActionButton(
                        text = stringResource(Res.string.wizard_save),
                        icon = Icons.Default.Check,
                        onClick = wizardVm::save,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is LaneStep.Saving -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(Res.string.wizard_saving),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is LaneStep.Saved -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(Res.string.wizard_saved, step.lane, step.points, step.xCount),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            is LaneStep.Locked -> LockedContent(
                result = step.result,
                onUnlock = wizardVm::unlockForEdit,
                onSkip = wizardVm::skipToNextOpenLane,
            )

            is LaneStep.Self -> {
                Text(
                    text = stringResource(Res.string.wizard_self),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(Res.string.wizard_self_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                PrimaryActionButton(
                    text = stringResource(Res.string.wizard_skip),
                    icon = Icons.Default.ChevronRight,
                    onClick = wizardVm::skipToNextOpenLane,
                )
            }

            is LaneStep.ClaimedByOther -> {
                Text(
                    text = stringResource(Res.string.wizard_claimed, step.holder.name ?: "?"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryActionButton(
                        text = stringResource(Res.string.wizard_claim_retry),
                        icon = Icons.Default.Refresh,
                        onClick = wizardVm::retryLane,
                    )
                    PrimaryActionButton(
                        text = stringResource(Res.string.wizard_skip),
                        icon = Icons.Default.ChevronRight,
                        onClick = wizardVm::skipToNextOpenLane,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is LaneStep.StationDone -> Unit // Rendered by StationSummaryContent.
        }
    }
}

@Composable
private fun LockedContent(
    result: ResultDto,
    onUnlock: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.wizard_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Total only: a ResultDto carries no caliber or tag.
            TotalBadge(result.points, caliber = null, tag = null)
            Text(
                text = "${result.hits} X",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shots = ShotMapping.shotsToPickers(result.stationFigureHits)
            if (shots.isNotEmpty()) ScoreMiniRow(shots)
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryActionButton(
            text = stringResource(Res.string.wizard_unlock),
            icon = Icons.Default.LockOpen,
            onClick = onUnlock,
        )
        PrimaryActionButton(
            text = stringResource(Res.string.wizard_skip),
            icon = Icons.Default.ChevronRight,
            onClick = onSkip,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---- Banners --------------------------------------------------------------

@Composable
private fun ResumeHintBanner(
    hint: ResumeHint,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.wizard_resume_hint, hint.stationSortorder, hint.lane),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryActionButton(
                    text = stringResource(Res.string.wizard_resume_stay),
                    icon = Icons.Default.Close,
                    onClick = onDismiss,
                )
                PrimaryActionButton(
                    text = stringResource(Res.string.wizard_resume_go),
                    icon = Icons.Default.ChevronRight,
                    onClick = onGo,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NotActiveContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.wizard_not_active),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.wizard_not_active_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryActionButton(
            text = stringResource(Res.string.retry),
            icon = Icons.Default.Refresh,
            onClick = onRetry,
        )
    }
}

// ---- Station summary ------------------------------------------------------

@Composable
private fun StationSummaryContent(
    state: WizardUiState,
    onGoToLane: (Int) -> Unit,
    onNextStation: () -> Unit,
) {
    val context = state.context ?: return
    val station = state.station ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(Res.string.wizard_station_summary, station.sortorder))
        var registered = 0
        context.lanes.forEachIndexed { index, entry ->
            val result = MarkingLogic.resultFor(entry.signup, station.sortorder)
            val scored = MarkingLogic.isScored(result)
            if (scored) registered++
            Surface(
                onClick = { onGoToLane(index) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.lane.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(36.dp),
                    )
                    Text(
                        text = entry.signup.user?.fullname ?: "?",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = result?.takeIf { scored }?.points?.toString() ?: "–",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result?.takeIf { scored }?.hits?.let { "$it X" } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    if (scored) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(Res.string.wizard_registered_count, registered, context.lanes.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (!state.isLastStation) {
            val next = context.stations.getOrNull(state.stationIndex + 1)
            PrimaryActionButton(
                text = stringResource(Res.string.wizard_next_station, next?.sortorder ?: station.sortorder + 1),
                icon = Icons.Default.ChevronRight,
                onClick = onNextStation,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            FinishSummary(state)
        }
    }
}

/** Final standings after the last series (only when everything is registered). */
@Composable
private fun FinishSummary(state: WizardUiState) {
    if (state.summaryLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        return
    }
    val summary = state.summary ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.wizard_final_done),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (summary.standings.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.wizard_standings))
            summary.standings.forEach { standing ->
                Surface(
                    color = if (standing.tied) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = standing.placement?.toString() ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = standing.name ?: "?",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val sub = if (standing.tied) {
                                stringResource(Res.string.wizard_tied)
                            } else {
                                listOfNotNull(
                                    standing.lane?.let { stringResource(Res.string.wizard_standing_lane, it) },
                                    standing.club,
                                ).joinToString(" · ")
                            }
                            if (sub.isNotEmpty()) {
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "${standing.points}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = " ${standing.hits} X",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
