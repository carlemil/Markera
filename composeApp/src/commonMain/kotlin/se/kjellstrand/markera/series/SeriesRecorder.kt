package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    data class Failed(val error: Throwable) : SaveStatus
}

/**
 * A scanned frame ready to upload. [width]/[height] are the *source* pixels the
 * hole coordinates refer to, not the (downscaled) JPEG's — the server needs them
 * to place the hit markers over the image it stores.
 */
class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

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
    private val repository: SeriesRepository,
    private val session: BackendSessionRepository,
    private val readCaliber: suspend () -> Caliber,
    private val writeCaliber: suspend (Caliber) -> Unit,
    private val readTag: suspend () -> String?,
    private val writeTag: suspend (String?) -> Unit,
    private val encodeJpeg: suspend (PlatformImage) -> EncodedImage,
    private val scope: CoroutineScope,
) {
    private val _caliber = MutableStateFlow(Caliber.NONE)
    val caliber: StateFlow<Caliber> = _caliber.asStateFlow()

    private val _status = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val status: StateFlow<SaveStatus> = _status.asStateFlow()

    private val _caliberDialogOpen = MutableStateFlow(false)
    val caliberDialogOpen: StateFlow<Boolean> = _caliberDialogOpen.asStateFlow()

    /** The free-text tag the next series gets; null is untagged. Same shape as [caliber]. */
    private val _tag = MutableStateFlow<String?>(null)
    val tag: StateFlow<String?> = _tag.asStateFlow()

    private val _tagDialogOpen = MutableStateFlow(false)
    val tagDialogOpen: StateFlow<Boolean> = _tagDialogOpen.asStateFlow()

    /**
     * A scanned series and the frame it was scored from (uploaded once the series has an id).
     * [committed] once [commit] asked for the save; until then a caliber choice only stores it.
     */
    private class Pending(var request: SeriesRequest, val image: PlatformImage?) {
        var committed = false
    }

    /** The scanned series waiting for a caliber (or for its POST to finish). */
    private var pending: Pending? = null

    /** The one whose POST is running: a second save of it (the chip tapped mid-save) is refused. */
    private var inFlight: Pending? = null

    /**
     * Every save is its own child of this: a rescan or the next scan never cancels a
     * committed save (its image and cache insert included), only [dispose] does.
     */
    private val saves = SupervisorJob(scope.coroutineContext[Job])

    init {
        // The stored value must not clobber a choice made before the read lands.
        scope.launch { _caliber.compareAndSet(Caliber.NONE, readCaliber()) }
        scope.launch { _tag.compareAndSet(null, normalizeTag(readTag())) }
        // A tag is the user's own: signing out forgets it (the store drops its copy in clear()).
        scope.launch {
            var signedIn = false
            session.auth.collect { auth ->
                if (signedIn && auth == null) _tag.value = null
                signedIn = auth != null
            }
        }
    }

    fun onSeriesDetected(scores: List<HitScore>, image: PlatformImage, geometry: GeometryDto?) {
        if (session.auth.value == null) {
            pending = null
            _status.value = SaveStatus.SignedOut
            return
        }
        // A fresh scan waits for its own commit.
        pending = Pending(seriesRequest(scores, _caliber.value, geometry = geometry), image)
        _status.value = SaveStatus.Pending
    }

    /**
     * Back to the live view: save what is pending (asking for a caliber first).
     * [topScores] are the picker values as the user left them — merged in here,
     * so the caliber dialog's later [selectCaliber] posts the edited series too.
     */
    fun commit(topScores: List<Int>) {
        val p = pending ?: return
        p.request = p.request.withPicks(topScores)
        p.committed = true
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

    /**
     * Chosen in the tag picker, typed or tapped. Normalised here — the one place
     * a tag enters — so blank can never reach the wire as `""`. Unlike the
     * caliber a tag never gates a save, so this only remembers it for the next one.
     */
    fun selectTag(raw: String?) {
        val tag = normalizeTag(raw)
        _tag.value = tag
        _tagDialogOpen.value = false
        scope.launch { writeTag(tag) }
    }

    fun openTagDialog() {
        _tagDialogOpen.value = true
    }

    fun dismissTagDialog() {
        _tagDialogOpen.value = false
    }

    /** Rescan: forget the pending series and its status. A save already under way still finishes. */
    fun clear() {
        pending = null
        _status.value = SaveStatus.Idle
    }

    fun dispose() {
        saves.cancel()
    }

    /**
     * Persists [caliber] when asked, then posts the pending series. With no
     * pending series, none [commit]ted yet, or with [Caliber.NONE] (nothing to
     * tag it with), only the preference is written and the status is left as it was.
     */
    private fun startSave(caliber: Caliber, persist: Boolean = false) {
        if (persist) scope.launch { writeCaliber(caliber) }
        val p = pending?.takeIf { caliber != Caliber.NONE && it.committed && it !== inFlight } ?: return
        // The tag is read at save time, so retagging a frozen frame still counts. The clientId
        // rides along unchanged, so a retry after a lost answer cannot store a second copy.
        val request = p.request.copy(caliber = caliber.label, tag = _tag.value)
        val image = p.image
        inFlight = p
        _status.value = SaveStatus.Saving
        scope.launch(saves) {
            val id = try {
                repository.save(request)
            } catch (e: CancellationException) {
                throw e // dispose() cancelled us; the screen is gone.
            } catch (e: Exception) {
                if (inFlight === p) inFlight = null
                if (e is SeriesApiException && e.isUnauthorized) {
                    // The repository signed out and toasts it; a Failed would be a second toast.
                    pending = null
                    _status.value = SaveStatus.Idle
                } else {
                    // Still pending: the chip retries it.
                    _status.value = SaveStatus.Failed(e)
                }
                return@launch
            }
            if (inFlight === p) inFlight = null
            // A series scanned while this one was posting stays pending.
            if (pending === p) pending = null
            _status.value = SaveStatus.Saved(caliber)
            // The snapshot is a bonus: a failed encode or upload never
            // downgrades an already-saved series.
            if (image == null) return@launch
            try {
                val encoded = encodeJpeg(image)
                repository.uploadImage(id, encoded.bytes, encoded.width, encoded.height)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Markera: series image upload failed: $e")
            }
        }
    }
}
