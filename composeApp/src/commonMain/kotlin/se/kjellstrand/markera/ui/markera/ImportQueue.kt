package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import se.kjellstrand.markera.series.Caliber

/** What an imported image's scan leads to. */
enum class ImportDecision { Save, Review, Skip }

/**
 * Auto import saves a scored scan locally when a caliber is chosen, regardless of
 * account state; everything else goes to the user, image by image. Without
 * auto import every image is reviewed.
 */
fun importDecision(auto: Boolean, hasScores: Boolean, caliber: Caliber): ImportDecision =
    if (auto && hasScores && caliber != Caliber.NONE) ImportDecision.Save else ImportDecision.Review

/**
 * What Save on the frozen frame leads to once the commit has answered: null keeps the
 * frame and its results on screen, so Save can be pressed again; otherwise the frame is
 * left and, in an import, the image counts as this decision.
 *
 * [kept] is the commit's local result (the row and photo are durably on the phone).
 * [retained] means the save failed and the recorder still holds the scan for a retry:
 * the only case that stays. A false commit for any other reason (nothing to save, or
 * waiting for the caliber dialog, which saves it later) leaves as before, uncounted.
 */
fun afterSave(kept: Boolean, retained: Boolean): ImportDecision? = when {
    kept -> ImportDecision.Save
    retained -> null
    else -> ImportDecision.Skip
}

/**
 * The images picked for import, worked through one at a time. In [auto] mode the
 * images sent to [ImportDecision.Review] are held back and, once the pass is done,
 * become the queue again, reviewed by hand.
 */
class ImportQueue<T>(items: List<T>, auto: Boolean) {
    var items by mutableStateOf(items)
        private set
    var index by mutableStateOf(0)
        private set
    var auto by mutableStateOf(auto)
        private set
    /** Bumped when the review pass starts: its list and index can equal the first pass's. */
    var pass by mutableStateOf(0)
        private set
    var saved = 0
        private set
    var skipped = 0
        private set
    private val review = mutableListOf<T>()

    /** The image on its turn; null once the queue is done. */
    val current: T? get() = items.getOrNull(index)

    fun next(decision: ImportDecision) {
        val item = current ?: return
        when (decision) {
            ImportDecision.Save -> saved++
            ImportDecision.Skip -> skipped++
            ImportDecision.Review -> if (auto) review += item else skipped++
        }
        index++
        if (index >= items.size && review.isNotEmpty()) {
            items = review.toList()
            review.clear()
            index = 0
            auto = false
            pass++
        }
    }
}
