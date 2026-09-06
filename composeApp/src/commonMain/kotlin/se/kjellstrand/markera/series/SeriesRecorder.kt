package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.PlatformImage

/** Where the last detected series got to. Only the terminal ones reach the UI. */
sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object SignedOut : SaveStatus

    /** Scanned and held; saved when the user leaves the frozen frame. */
    data object Pending : SaveStatus
    data object NeedsCaliber : SaveStatus
    data object Saving : SaveStatus
    data class Saved(val caliber: Caliber) : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

/**
 * Auto-saves every scanned series to the backend. Fed by
 * `TargetScanController.onSeriesDetected`, so free marking and the competition
 * wizard share one instance.
 *
 * A scan only becomes *pending*: [commit] (the user leaving the frozen frame)
 * is what saves it, and a rescan [clear]s it instead. The timestamp is the
 * detection time, not the (later) save time. A plain class
 * with [dispose], not an androidx ViewModel: the in-flight save must die with
 * the screen that started it.
 */
class SeriesRecorder(
    private val api: SeriesApi,
    private val session: BackendSessionRepository,
    private val readCaliber: suspend () -> Caliber,
    private val writeCaliber: suspend (Caliber) -> Unit,
    private val encodeJpeg: suspend (PlatformImage) -> ByteArray,
    private val scope: CoroutineScope,
) {
    private val _caliber = MutableStateFlow(Caliber.NONE)
    val caliber: StateFlow<Caliber> = _caliber.asStateFlow()

    private val _status = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val status: StateFlow<SaveStatus> = _status.asStateFlow()

    private val _caliberDialogOpen = MutableStateFlow(false)
    val caliberDialogOpen: StateFlow<Boolean> = _caliberDialogOpen.asStateFlow()

    /** The scanned series waiting for a caliber (or for its POST to finish). */
    private var pending: SeriesRequest? = null

    /** The frame it was scored from, uploaded once the series has an id. */
    private var pendingImage: PlatformImage? = null
    private var saveJob: Job? = null

    /** True once [commit] asked for the save; until then a caliber choice only stores it. */
    private var saveRequested = false

    init {
        // The stored value must not clobber a choice made before the read lands.
        scope.launch { _caliber.compareAndSet(Caliber.NONE, readCaliber()) }
    }

    fun onSeriesDetected(scores: List<HitScore>, image: PlatformImage) {
        if (session.auth.value == null) {
            pending = null
            pendingImage = null
            _status.value = SaveStatus.SignedOut
            return
        }
        pending = seriesRequest(scores, _caliber.value)
        pendingImage = image
        saveRequested = false // a fresh scan waits for its own commit
        _status.value = SaveStatus.Pending
    }

    /**
     * Back to the live view: save what is pending (asking for a caliber first).
     * [topScores] are the picker values as the user left them — merged in here,
     * so the caliber dialog's later [selectCaliber] posts the edited series too.
     */
    fun commit(topScores: List<Int>) {
        pending = pending?.withPicks(topScores) ?: return
        saveRequested = true
        val caliber = _caliber.value
        if (caliber == Caliber.NONE) {
            _status.value = SaveStatus.NeedsCaliber
            _caliberDialogOpen.value = true
        } else {
            startSave(caliber)
        }
    }

    /** Chosen in the dialog or from the chip: stored, and saves a series [commit] is waiting for. */
    fun selectCaliber(caliber: Caliber) {
        _caliber.value = caliber
        _caliberDialogOpen.value = false
        startSave(caliber, persist = true)
    }

    fun openCaliberDialog() {
        _caliberDialogOpen.value = true
    }

    /** Dismissed without choosing: the series stays pending, waiting for one. */
    fun dismissCaliberDialog() {
        _caliberDialogOpen.value = false
    }

    /** Rescan: forget the pending series and its status, saving nothing. */
    fun clear() {
        saveJob?.cancel()
        pending = null
        pendingImage = null
        saveRequested = false
        _status.value = SaveStatus.Idle
    }

    fun dispose() {
        saveJob?.cancel()
    }

    /**
     * Persists [caliber] when asked, then posts the pending series. With no
     * pending series, none [commit]ted yet, or with [Caliber.NONE] (nothing to
     * tag it with), only the preference is written and the status is left as it was.
     */
    private fun startSave(caliber: Caliber, persist: Boolean = false) {
        val request = pending
            ?.takeIf { caliber != Caliber.NONE && saveRequested }
            ?.copy(caliber = caliber.label)
        val image = pendingImage
        saveJob?.cancel()
        if (request != null) _status.value = SaveStatus.Saving
        saveJob = scope.launch {
            if (persist) writeCaliber(caliber)
            if (request == null) return@launch
            val id = try {
                api.postSeries(request).also {
                    pending = null
                    pendingImage = null
                    _status.value = SaveStatus.Saved(caliber)
                }
            } catch (e: CancellationException) {
                throw e // clear()/dispose() cancelled us; the status is theirs to set.
            } catch (e: Exception) {
                _status.value = SaveStatus.Failed(e.message ?: e.toString())
                return@launch
            }
            // The snapshot is a bonus: a failed encode or upload never
            // downgrades an already-saved series.
            if (image == null) return@launch
            try {
                api.postSeriesImage(id, encodeJpeg(image))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Markera: series image upload failed: $e")
            }
        }
    }
}
