package se.kjellstrand.markera.ui.markera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ponytail: hand-set thresholds, calibrated on the phone against a real target
// at a real distance (a .22 hole is ~5-10 px at the ~480 px sample grid). A
// false trigger only costs one full rescan that reproduces the same holes; a
// miss just means the user taps Detect as before.

/** Side the analysis frame is box-averaged down to (averaging also kills sensor noise). */
internal const val WATCH_GRID = 480

/** How often the watch loop takes a sample. */
internal const val CHANGE_SAMPLE_MS = 700L

/** Luma step that counts a pixel as changed, at the least. */
internal const val CHANGE_MIN_DELTA = 20

/** ... or this many times the frame's median difference, when the frame is noisier. */
internal const val CHANGE_NOISE_FACTOR = 4

/** Hole-sized blob: changed-pixel area range at [WATCH_GRID]. */
internal const val HOLE_MIN_AREA = 8
internal const val HOLE_MAX_AREA = 400

/** Round enough: longer bbox side at most this times the shorter. */
internal const val HOLE_MAX_ASPECT = 2f

/** Round enough: blob area over its bbox area (a disk is ~0.79, a streak far less). */
internal const val HOLE_MIN_FILL = 0.45f

/** Changed pixels between two samples that still count as "nothing moving". */
internal const val SETTLED_MAX_PIXELS = 6

/** Share of the frame changed past which it is light/occlusion/camera motion, not a hole. */
internal const val GLOBAL_CHANGE_SHARE = 0.01f

/** A row-major luma frame of the live feed. */
class LumaFrame(val width: Int, val height: Int, val luma: ByteArray)

/**
 * Changed-pixel mask of [cur] against [ref]: a pixel is changed when it differs
 * from all nine ref pixels around it (so a 1 px tripod creep does not light up
 * every edge), after taking out the global brightness offset.
 */
internal fun changeMask(ref: LumaFrame, cur: LumaFrame): BooleanArray? {
    if (ref.width != cur.width || ref.height != cur.height) return null
    val w = cur.width
    val h = cur.height
    val r = ref.luma
    val c = cur.luma
    var sum = 0L
    for (i in c.indices) sum += (c[i].toInt() and 0xFF) - (r[i].toInt() and 0xFF)
    val offset = (sum / c.size).toInt()
    val diff = IntArray(c.size)
    val histogram = IntArray(256)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val v = (c[y * w + x].toInt() and 0xFF) - offset
            var best = 255
            for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
                for (nx in max(0, x - 1)..min(w - 1, x + 1)) {
                    best = min(best, abs(v - (r[ny * w + nx].toInt() and 0xFF)))
                }
            }
            diff[y * w + x] = best
            histogram[best]++
        }
    }
    var median = 0
    var seen = 0
    while (seen + histogram[median] <= c.size / 2) seen += histogram[median++]
    val threshold = max(CHANGE_MIN_DELTA, CHANGE_NOISE_FACTOR * median)
    return BooleanArray(c.size) { diff[it] >= threshold }
}

/** A connected region of a change mask. */
internal class Blob(val area: Int, val width: Int, val height: Int) {
    val holeSized: Boolean
        get() = area in HOLE_MIN_AREA..HOLE_MAX_AREA &&
            max(width, height) <= HOLE_MAX_ASPECT * min(width, height) &&
            area >= HOLE_MIN_FILL * width * height
}

/** 4-connected blobs of [mask] ([width] wide). */
internal fun blobs(mask: BooleanArray, width: Int): List<Blob> {
    val seen = BooleanArray(mask.size)
    val stack = IntArray(mask.size)
    var top = 0
    fun push(i: Int) {
        if (mask[i] && !seen[i]) {
            seen[i] = true
            stack[top++] = i
        }
    }
    val out = mutableListOf<Blob>()
    for (start in mask.indices) {
        if (!mask[start] || seen[start]) continue
        push(start)
        var area = 0
        var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        while (top > 0) {
            val i = stack[--top]
            area++
            val x = i % width
            val y = i / width
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            if (x > 0) push(i - 1)
            if (x < width - 1) push(i + 1)
            if (i >= width) push(i - width)
            if (i + width < mask.size) push(i + width)
        }
        out += Blob(area, x1 - x0 + 1, y1 - y0 + 1)
    }
    return out
}

/**
 * "A new hole appeared": [offer] returns true when the sample equals the
 * reference (the scene at the last scan) except for hole-sized, roughly round
 * spots, and nothing moved since the previous sample — so the spot has held
 * for two samples and no hand is crossing the target. A large change (light,
 * a person, the camera nudged) never fires; once it settles it becomes the new
 * reference.
 */
internal class NewHoleWatch {
    private var reference: LumaFrame? = null
    private var previous: LumaFrame? = null

    fun offer(sample: LumaFrame): Boolean {
        val ref = reference
        val prev = previous
        previous = sample
        if (ref == null) {
            reference = sample
            return false
        }
        val settled = prev != null &&
            (changeMask(prev, sample)?.count { it } ?: Int.MAX_VALUE) <= SETTLED_MAX_PIXELS
        if (!settled) return false
        val mask = changeMask(ref, sample) ?: run {
            reference = sample
            return false
        }
        if (mask.count { it } > GLOBAL_CHANGE_SHARE * mask.size) {
            reference = sample
            return false
        }
        // Specks below the hole size are noise; anything else not hole-shaped vetoes.
        val found = blobs(mask, sample.width).filter { it.area >= HOLE_MIN_AREA }
        return found.isNotEmpty() && found.all { it.holeSized }
    }

    /** After a scan: the next sample becomes the new reference. */
    fun reset() {
        reference = null
        previous = null
    }
}
