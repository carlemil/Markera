package se.kjellstrand.markera.ui.competition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.kjellstrand.markera.webshooter.LaneEntry
import se.kjellstrand.markera.webshooter.MarkingContext
import se.kjellstrand.markera.webshooter.MarkingLogic
import se.kjellstrand.markera.webshooter.ScoringRepository
import se.kjellstrand.markera.webshooter.api.WebshooterApiException
import se.kjellstrand.markera.webshooter.api.dto.ClaimDto
import se.kjellstrand.markera.webshooter.api.dto.ResultDto
import se.kjellstrand.markera.webshooter.api.dto.StationDto
import se.kjellstrand.markera.webshooter.api.dto.SummaryResponse
import se.kjellstrand.markera.webshooter.auth.SessionRepository

/** How long the "saved" checkmark shows before auto-advancing (matches the web). */
private const val SAVED_DWELL_MS = 1200L
private const val STATUS_POLL_MS = 10_000L

/** The per-lane step, mirroring the web's entering/confirm/saved/locked states. */
sealed interface LaneStep {
    /** Camera live; waiting for a scan (or its result). */
    data object Entering : LaneStep

    /** Scan done: scores prefilled and editable; user accepts or rescans. */
    data class Confirm(val shots: List<Int>) : LaneStep

    data object Saving : LaneStep

    data class Saved(val lane: Int, val points: Int, val xCount: Int) : LaneStep

    /** A registered result already exists; unlock to edit. */
    data class Locked(val result: ResultDto) : LaneStep

    /** The user's own lane — marking blocked (admins exempt). */
    data object Self : LaneStep

    /** Someone else holds the claim on this lane. */
    data class ClaimedByOther(val holder: ClaimDto) : LaneStep

    /** Every lane in this station handled — series summary. */
    data object StationDone : LaneStep
}

data class ResumeHint(
    val stationIndex: Int,
    val laneIndex: Int,
    val stationSortorder: Int,
    val lane: Int,
)

data class WizardUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    /** The patrol is not activated for mobile scoring. */
    val notActive: Boolean = false,
    /** Non-null when a station's shot count isn't supported by the pickers. */
    val unsupportedShots: Int? = null,
    val context: MarkingContext? = null,
    val stationIndex: Int = 0,
    val laneIndex: Int = 0,
    val step: LaneStep = LaneStep.Entering,
    /** Lanes currently claimed by other markers (from status polling). */
    val claims: Map<Int, ClaimDto> = emptyMap(),
    val resumeHint: ResumeHint? = null,
    val saveError: Boolean = false,
    /** Set when a save bounced off someone else's result (duplicate_result). */
    val duplicateBy: String? = null,
    val summary: SummaryResponse? = null,
    val summaryLoading: Boolean = false,
) {
    val station: StationDto? get() = context?.stations?.getOrNull(stationIndex)
    val laneEntry: LaneEntry? get() = context?.lanes?.getOrNull(laneIndex)
    val isLastStation: Boolean get() = context?.let { stationIndex >= it.stations.size - 1 } ?: false
}

/**
 * Drives the marking wizard for one marking group, mirroring the web's
 * MobileScoringV2Controller: load everything once, then per lane
 * claim → scan → confirm → save → advance, station by station.
 *
 * The camera/scan side stays in the screen; the screen reports a finished
 * scan via [onScanComplete] and renders according to [uiState].
 *
 * Deliberately not an androidx ViewModel: its lifetime matches one visit to
 * the wizard screen (created with remember, torn down via [dispose]), so the
 * status polling and claim don't outlive the screen in the activity scope.
 */
class MarkingWizardViewModel(
    private val scoringRepository: ScoringRepository,
    private val sessionRepository: SessionRepository,
    private val competitionId: Int,
    private val groupGuid: String,
    /** ISO-8601 UTC timestamp factory, e.g. 2026-08-15T12:00:00.000Z. */
    private val nowIso: () -> String,
    /** Unique client nonce for idempotent save retries. */
    private val clientNonce: () -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState.asStateFlow()

    // Claim release must survive VM/scope teardown (best-effort fire-and-forget).
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val userId: Long? get() = sessionRepository.session.value?.userId
    private val isAdmin: Boolean get() = sessionRepository.session.value?.isAdmin ?: false

    init {
        load()
        scope.launch { pollStatus() }
    }

    fun load() {
        scope.launch {
            _uiState.value = WizardUiState(loading = true)
            try {
                val context = scoringRepository.openMarkingGroup(competitionId, groupGuid)
                // The resolve endpoint 403s when no patrol is active, so reaching
                // here means the server accepted the group. An empty lane list
                // (no signups in range) still gets the not-active treatment.
                val notActive = context.lanes.isEmpty()
                val unsupported = context.stations
                    .firstOrNull { it.shots != SUPPORTED_SHOTS }?.shots
                if (notActive || unsupported != null) {
                    _uiState.value = WizardUiState(
                        loading = false,
                        notActive = notActive,
                        unsupportedShots = unsupported,
                        context = context,
                    )
                    return@launch
                }
                // Resume where marking stopped: first (station, lane) without a
                // registered result — like opening a group QR on the web.
                val (stationIndex, laneIndex) =
                    MarkingLogic.firstOpenPosition(context)
                        ?: ((context.stations.size - 1) to (context.lanes.size - 1).coerceAtLeast(0))
                _uiState.value = WizardUiState(
                    loading = false,
                    context = context,
                    stationIndex = stationIndex,
                    laneIndex = laneIndex,
                )
                enterCurrentLane()
                refreshStatus()
            } catch (e: WebshooterApiException) {
                _uiState.value = WizardUiState(
                    loading = false,
                    notActive = e.isNoActivePatrol || e.status == 403,
                    loadError = !(e.isNoActivePatrol || e.status == 403),
                )
            } catch (_: Exception) {
                _uiState.value = WizardUiState(loading = false, loadError = true)
            }
        }
    }

    // ---- Lane lifecycle --------------------------------------------------

    /** Applies self/locked state and claims the lane when it becomes current. */
    private fun enterCurrentLane() {
        val st = _uiState.value
        val entry = st.laneEntry ?: return
        val station = st.station ?: return
        val existing = MarkingLogic.resultFor(entry.signup, station.sortorder)
        val step = when {
            MarkingLogic.isSelf(entry, userId, isAdmin) -> LaneStep.Self
            MarkingLogic.isScored(existing) -> LaneStep.Locked(existing!!)
            else -> LaneStep.Entering
        }
        _uiState.update {
            it.copy(step = step, saveError = false, duplicateBy = null, resumeHint = null)
        }
        if (step == LaneStep.Entering) claimCurrentLane()
    }

    private fun claimCurrentLane() {
        val st = _uiState.value
        val entry = st.laneEntry ?: return
        val station = st.station ?: return
        scope.launch {
            try {
                val holder = scoringRepository.claim(
                    st.context!!, entry.signup.id, station.sortorder,
                )
                if (holder != null) {
                    _uiState.update { cur ->
                        if (cur.laneIndex == st.laneIndex && cur.step is LaneStep.Entering) {
                            cur.copy(step = LaneStep.ClaimedByOther(holder))
                        } else {
                            cur
                        }
                    }
                }
            } catch (_: Exception) {
                // Claiming is advisory; scanning proceeds without it.
            }
        }
    }

    private fun releaseClaim(entry: LaneEntry, stationSortorder: Int) {
        val context = _uiState.value.context ?: return
        releaseScope.launch {
            scoringRepository.releaseClaim(context, entry.signup.id, stationSortorder)
        }
    }

    /** Best-effort release when the user leaves the wizard. */
    fun onLeave() {
        val st = _uiState.value
        val entry = st.laneEntry ?: return
        val station = st.station ?: return
        if (st.step is LaneStep.Entering || st.step is LaneStep.Confirm) {
            releaseClaim(entry, station.sortorder)
        }
    }

    /** Tear down: release the current claim and stop all coroutines. */
    fun dispose() {
        onLeave()
        scope.cancel()
    }

    /** Re-evaluate the current lane (e.g. retry after a claim ban). */
    fun retryLane() = enterCurrentLane()

    // ---- Scanning / confirm ---------------------------------------------

    /** The screen finished a scan; prefill the confirm step with the top scores. */
    fun onScanComplete(topScores: List<Int>) {
        _uiState.update {
            if (it.step is LaneStep.Entering) it.copy(step = LaneStep.Confirm(topScores)) else it
        }
    }

    fun updateShot(index: Int, value: Int) {
        _uiState.update { st ->
            val step = st.step as? LaneStep.Confirm ?: return@update st
            val shots = step.shots.toMutableList()
            if (index in shots.indices) shots[index] = value.coerceIn(0, 11)
            st.copy(step = LaneStep.Confirm(shots))
        }
    }

    /** Back from confirm (or locked) to a fresh scan. */
    fun rescan() {
        _uiState.update { it.copy(step = LaneStep.Entering, saveError = false, duplicateBy = null) }
    }

    /** Unlock an already-registered result for editing, prefilled from it. */
    fun unlockForEdit() {
        _uiState.update { st ->
            val locked = st.step as? LaneStep.Locked ?: return@update st
            val prior = se.kjellstrand.markera.webshooter.ShotMapping
                .shotsToPickers(locked.result.stationFigureHits)
            val shots = if (prior.size == SUPPORTED_SHOTS) prior else List(SUPPORTED_SHOTS) { 0 }
            st.copy(step = LaneStep.Confirm(shots))
        }
    }

    // ---- Save ------------------------------------------------------------

    fun save() {
        val st = _uiState.value
        val confirm = st.step as? LaneStep.Confirm ?: return
        val context = st.context ?: return
        val entry = st.laneEntry ?: return
        val station = st.station ?: return
        _uiState.update { it.copy(step = LaneStep.Saving, saveError = false, duplicateBy = null) }
        scope.launch {
            try {
                val updated = scoringRepository.save(
                    context = context,
                    signup = entry.signup,
                    lane = entry.lane,
                    stationSortorder = station.sortorder,
                    picks = confirm.shots,
                    nowIso = nowIso(),
                    clientNonce = clientNonce(),
                )
                mergeResult(entry.lane, updated)
                releaseClaim(entry, station.sortorder)
                _uiState.update {
                    it.copy(step = LaneStep.Saved(entry.lane, updated.points, updated.hits))
                }
                delay(SAVED_DWELL_MS)
                advanceAfterSave()
            } catch (e: WebshooterApiException) {
                if (e.isDuplicateResult) {
                    // Someone else registered first: keep their result, show who.
                    _uiState.update {
                        it.copy(duplicateBy = e.error?.scoredByName ?: "?", step = confirm)
                    }
                    refreshStatus()
                } else {
                    _uiState.update { it.copy(step = confirm, saveError = true) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(step = confirm, saveError = true) }
            }
        }
    }

    private fun advanceAfterSave() {
        val st = _uiState.value
        val context = st.context ?: return
        val station = st.station ?: return
        val next = MarkingLogic.nextOpenLaneIndex(
            context, station.sortorder, st.laneIndex, st.claims, userId, isAdmin,
        )
        if (next != null) {
            _uiState.update { it.copy(laneIndex = next) }
            enterCurrentLane()
        } else {
            showStationSummary()
        }
    }

    // ---- Navigation ------------------------------------------------------

    fun goToLane(index: Int) {
        val st = _uiState.value
        val context = st.context ?: return
        if (index !in context.lanes.indices || index == st.laneIndex) return
        leaveCurrentLane()
        _uiState.update { it.copy(laneIndex = index) }
        enterCurrentLane()
    }

    fun prevLane() = goToLane(_uiState.value.laneIndex - 1)

    fun nextLane() = goToLane(_uiState.value.laneIndex + 1)

    /** Skip past a claimed/self lane to the next open one (or the summary). */
    fun skipToNextOpenLane() {
        val st = _uiState.value
        val context = st.context ?: return
        val station = st.station ?: return
        leaveCurrentLane()
        val next = MarkingLogic.nextOpenLaneIndex(
            context, station.sortorder, st.laneIndex, st.claims, userId, isAdmin,
        )
        if (next != null) {
            _uiState.update { it.copy(laneIndex = next) }
            enterCurrentLane()
        } else {
            showStationSummary()
        }
    }

    private fun leaveCurrentLane() {
        val st = _uiState.value
        val entry = st.laneEntry ?: return
        val station = st.station ?: return
        if (st.step is LaneStep.Entering || st.step is LaneStep.Confirm) {
            releaseClaim(entry, station.sortorder)
        }
    }

    fun showStationSummary() {
        leaveCurrentLane()
        _uiState.update { it.copy(step = LaneStep.StationDone) }
        maybeLoadFinishSummary()
    }

    /** Advance to the next series; fires the publish request like the web. */
    fun nextStation() {
        val st = _uiState.value
        val context = st.context ?: return
        if (st.stationIndex >= context.stations.size - 1) return
        scope.launch { scoringRepository.requestPublish(context) }
        val newStationIndex = st.stationIndex + 1
        val station = context.stations[newStationIndex]
        // First open lane in the new station (fall back to the first lane).
        val laneIndex = MarkingLogic.nextOpenLaneIndex(
            context, station.sortorder, -1, st.claims, userId, isAdmin,
        ) ?: 0
        _uiState.update {
            it.copy(stationIndex = newStationIndex, laneIndex = laneIndex, summary = null)
        }
        enterCurrentLane()
        refreshStatus()
    }

    /** Back from the summary into the current station's lanes. */
    fun backToLanes(index: Int = _uiState.value.laneIndex) {
        _uiState.update { it.copy(laneIndex = index) }
        enterCurrentLane()
    }

    // ---- Status polling / summary ---------------------------------------

    private suspend fun pollStatus() {
        while (scope.isActive) {
            delay(STATUS_POLL_MS)
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val st = _uiState.value
        val context = st.context ?: return
        val station = st.station ?: return
        scope.launch {
            try {
                val status = scoringRepository.status(context, station.sortorder)
                val claims = status.lanes
                    .mapNotNull { lane -> lane.claim?.let { lane.lane to it } }
                    .toMap()
                status.lanes.forEach { lane ->
                    lane.result?.let { mergeResult(lane.lane, it) }
                }
                _uiState.update { it.copy(claims = claims) }
                // Someone may have registered the current lane meanwhile.
                val cur = _uiState.value
                if (cur.step is LaneStep.Entering) {
                    val entry = cur.laneEntry
                    val existing = entry?.let {
                        MarkingLogic.resultFor(it.signup, station.sortorder)
                    }
                    if (MarkingLogic.isScored(existing)) {
                        _uiState.update { it.copy(step = LaneStep.Locked(existing!!)) }
                    }
                }
                updateResumeHint()
            } catch (_: Exception) {
                // Polling is best-effort.
            }
        }
    }

    /** Merge a server-side result into the cached signup for [lane]. */
    private fun mergeResult(lane: Int, result: ResultDto) {
        _uiState.update { st ->
            val ctx = st.context ?: return@update st
            val lanes = ctx.lanes.map { entry ->
                if (entry.lane != lane) {
                    entry
                } else {
                    entry.copy(
                        signup = entry.signup.copy(
                            results = MarkingLogic.mergedResults(entry.signup, result),
                        )
                    )
                }
            }
            st.copy(context = ctx.copy(lanes = lanes))
        }
    }

    private fun updateResumeHint() {
        val st = _uiState.value
        val context = st.context ?: return
        if (st.step !is LaneStep.Entering && st.step !is LaneStep.Locked && st.step !is LaneStep.Self) return
        val open = MarkingLogic.firstOpenPosition(context) ?: run {
            _uiState.update { it.copy(resumeHint = null) }
            return
        }
        val (si, li) = open
        val hint = if (si == st.stationIndex && li == st.laneIndex) {
            null
        } else {
            ResumeHint(
                stationIndex = si,
                laneIndex = li,
                stationSortorder = context.stations[si].sortorder,
                lane = context.lanes[li].lane,
            )
        }
        _uiState.update { it.copy(resumeHint = hint) }
    }

    fun goToResumeHint() {
        val hint = _uiState.value.resumeHint ?: return
        leaveCurrentLane()
        _uiState.update {
            it.copy(
                stationIndex = hint.stationIndex,
                laneIndex = hint.laneIndex,
                resumeHint = null,
                summary = null,
            )
        }
        enterCurrentLane()
        refreshStatus()
    }

    fun dismissResumeHint() {
        _uiState.update { it.copy(resumeHint = null) }
    }

    private fun maybeLoadFinishSummary() {
        val st = _uiState.value
        val context = st.context ?: return
        val station = st.station ?: return
        if (!st.isLastStation) return
        if (!MarkingLogic.allRegistered(context, station.sortorder)) return
        scope.launch {
            _uiState.update { it.copy(summaryLoading = true) }
            try {
                val summary = scoringRepository.summary(context)
                _uiState.update { it.copy(summaryLoading = false, summary = summary) }
                scoringRepository.requestPublish(context)
            } catch (_: Exception) {
                _uiState.update { it.copy(summaryLoading = false) }
            }
        }
    }

    companion object {
        const val SUPPORTED_SHOTS = 5
    }
}
