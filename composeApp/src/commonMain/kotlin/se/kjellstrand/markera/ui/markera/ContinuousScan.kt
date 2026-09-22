package se.kjellstrand.markera.ui.markera

import kotlin.math.abs

// ponytail: three hand-set thresholds, calibrated on the phone against a real
// target at a real distance. A false trigger only costs one wasted rescan that
// reproduces the same holes, so they may lean sensitive; a miss just means the
// user taps Detect as before.

/** Sampling grid the live feed is reduced to; a new hole is ~2 cells wide. */
internal const val CHANGE_GRID = 128

/** Per-cell luma step that counts as changed (sensor noise is a few levels). */
internal const val CHANGE_CELL_DELTA = 24

/** Cells that must change before the scene counts as changed. */
internal const val CHANGE_CELLS = 3

/** How often the live feed is sampled. */
internal const val CHANGE_SAMPLE_MS = 700L

/** Cells of [a] and [b] that differ by at least [CHANGE_CELL_DELTA]. */
internal fun changedCells(a: ByteArray, b: ByteArray): Int {
    if (a.size != b.size) return Int.MAX_VALUE
    var n = 0
    for (i in a.indices) {
        if (abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) >= CHANGE_CELL_DELTA) n++
    }
    return n
}

/**
 * "Changed, then settled": [offer] returns true once a sample differs from the
 * baseline *and* has stopped differing from the sample before it, so a hand or
 * a body crossing the target does not fire a scan while it is still moving.
 */
internal class ChangeWatch {
    private var baseline: ByteArray? = null
    private var previous: ByteArray? = null

    fun offer(sample: ByteArray): Boolean {
        val base = baseline
        val prev = previous
        previous = sample
        if (base == null) {
            baseline = sample
            return false
        }
        val changed = changedCells(base, sample) >= CHANGE_CELLS
        val settled = prev != null && changedCells(prev, sample) < CHANGE_CELLS
        return changed && settled
    }

    /** After a scan: the next sample becomes the new baseline. */
    fun reset() {
        baseline = null
        previous = null
    }
}
