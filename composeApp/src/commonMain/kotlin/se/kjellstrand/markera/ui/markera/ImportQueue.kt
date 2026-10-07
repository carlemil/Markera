package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import se.kjellstrand.markera.series.Caliber

/** What an imported image's scan leads to. */
enum class ImportDecision { Save, Review, Skip }

/**
 * Auto import saves a scan only when it scored and can be saved as it is (signed in,
 * a caliber chosen); everything else goes to the user, image by image. Without
 * auto import every image is reviewed.
 */
fun importDecision(auto: Boolean, hasScores: Boolean, caliber: Caliber, signedIn: Boolean): ImportDecision =
    if (auto && hasScores && signedIn && caliber != Caliber.NONE) ImportDecision.Save else ImportDecision.Review

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
        }
    }
}
