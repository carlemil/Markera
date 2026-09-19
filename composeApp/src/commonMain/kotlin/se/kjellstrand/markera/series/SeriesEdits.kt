package se.kjellstrand.markera.series

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The editable part of a saved series. */
data class SeriesEdit(val holes: List<HoleDto>, val caliber: String, val tag: String?)

/**
 * Autosave for the Serie page: every edit PUTs the whole series at once. Undo is
 * just [save] of the state it returned.
 */
class SeriesEdits(private val repo: SeriesRepository, private val base: SeriesDto) {
    /** The last state the server accepted. */
    var saved = SeriesEdit(base.holes, base.caliber, base.tag)
        private set

    private val mutex = Mutex()

    /**
     * PUTs [next], plus [removedHole] flagged deleted — sent in this one PUT only,
     * since the server keeps every deleted copy it gets. Returns the state to undo
     * to, or null when the save failed ([saved] is then unchanged).
     *
     * Non-cancellable, lock included, so going back straight after an edit can't
     * drop it; the lock keeps quick edits in order.
     */
    suspend fun save(next: SeriesEdit, removedHole: HoleDto? = null): SeriesEdit? =
        withContext(NonCancellable) {
            mutex.withLock {
                try {
                    repo.update(
                        base.id,
                        SeriesRequest(
                            base.timestamp,
                            next.caliber,
                            next.holes + listOfNotNull(removedHole),
                            base.geometry,
                            next.tag,
                        ),
                    )
                    saved.also { saved = next }
                } catch (_: Exception) {
                    null
                }
            }
        }
}
