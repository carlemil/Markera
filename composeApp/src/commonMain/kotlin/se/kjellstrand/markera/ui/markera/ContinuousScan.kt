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

/** Largest camera shake / creep (grid px) the compare aligns away. */
internal const val MAX_SHIFT = 4

/** Share of the frame changed past which it is light/occlusion/camera motion, not a hole. */
internal const val GLOBAL_CHANGE_SHARE = 0.01f

/**
 * A row-major luma frame of the live feed, in sensor orientation: [rotation] is
 * the clockwise turn (0/90/180/270) that makes it upright on screen.
 */
class LumaFrame(val width: Int, val height: Int, val luma: ByteArray, val rotation: Int = 0)

/** A [changeMask] result: the mask, the luma step it was cut at and the shift it aligned away. */
internal class Change(val mask: BooleanArray, val threshold: Int, val dx: Int, val dy: Int) {
    val count = mask.count { it }
}

/**
 * The whole-pixel shake (dx, dy) that best lines [cur] up with [ref], so
 * cur(x, y) ≈ ref(x + dx, y + dy): the least absolute difference, brightness
 * [offset] taken out, over every 4th pixel — the standard block-matching global
 * motion estimate, enough for a few px of tripod shake.
 */
internal fun estimateShift(ref: LumaFrame, cur: LumaFrame, offset: Int, radius: Int = MAX_SHIFT): Pair<Int, Int> {
    val w = cur.width
    val h = cur.height
    var best = Long.MAX_VALUE
    var shift = 0 to 0
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            var sad = 0L
            for (y in radius until h - radius step 4) {
                for (x in radius until w - radius step 4) {
                    val v = (cur.luma[y * w + x].toInt() and 0xFF) - offset
                    sad += abs(v - (ref.luma[(y + dy) * w + x + dx].toInt() and 0xFF))
                }
            }
            // Ties (a featureless frame) keep the smaller shift, so no motion wins.
            if (sad < best || (sad == best && abs(dx) + abs(dy) < abs(shift.first) + abs(shift.second))) {
                best = sad
                shift = dx to dy
            }
        }
    }
    return shift
}

/**
 * Changed-pixel mask of [cur] against [ref]: first the camera shake is aligned
 * away ([estimateShift]), then a pixel is changed when it differs from all nine
 * shifted ref pixels around it (absorbing the sub-pixel remainder), after taking
 * out the global brightness offset. The border band the shift uncovers never counts.
 */
internal fun changeMask(ref: LumaFrame, cur: LumaFrame): Change? {
    if (ref.width != cur.width || ref.height != cur.height) return null
    val w = cur.width
    val h = cur.height
    val r = ref.luma
    val c = cur.luma
    var sum = 0L
    for (i in c.indices) sum += (c[i].toInt() and 0xFF) - (r[i].toInt() and 0xFF)
    val offset = (sum / c.size).toInt()
    val (dx, dy) = estimateShift(ref, cur, offset)
    val diff = IntArray(c.size)
    val histogram = IntArray(256)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val rx = x + dx
            val ry = y + dy
            if (rx !in 0 until w || ry !in 0 until h) {
                histogram[0]++
                continue
            }
            val v = (c[y * w + x].toInt() and 0xFF) - offset
            var best = 255
            for (ny in max(0, ry - 1)..min(h - 1, ry + 1)) {
                for (nx in max(0, rx - 1)..min(w - 1, rx + 1)) {
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
    return Change(BooleanArray(c.size) { diff[it] >= threshold }, threshold, dx, dy)
}

/** A connected region of a change mask; [x], [y] is its bbox's top-left. */
internal class Blob(val area: Int, val width: Int, val height: Int, val x: Int = 0, val y: Int = 0) {
    val holeSized: Boolean
        get() = area in HOLE_MIN_AREA..HOLE_MAX_AREA &&
            max(width, height) <= HOLE_MAX_ASPECT * min(width, height) &&
            area >= HOLE_MIN_FILL * width * height

    override fun toString() = "a=$area ${width}x$height fill ${100 * area / (width * height)}%"
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
        out += Blob(area, x1 - x0 + 1, y1 - y0 + 1, x0, y0)
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

    /** Why the last [offer] did or did not fire (the debug overlay shows it). */
    var last: WatchVerdict? = null
        private set

    fun offer(sample: LumaFrame): Boolean {
        val ref = reference
        val prev = previous
        previous = sample
        if (ref == null) {
            reference = sample
            return verdict(sample, "reference taken")
        }
        val moving = prev?.let { changeMask(it, sample) }
        if (moving == null || moving.count > SETTLED_MAX_PIXELS) {
            val shift = moving?.let { " (shift ${it.dx},${it.dy})" }.orEmpty()
            return verdict(sample, "moving: ${moving?.count ?: "?"} px changed since last sample$shift (max $SETTLED_MAX_PIXELS)")
        }
        val change = changeMask(ref, sample) ?: run {
            reference = sample
            return verdict(sample, "frame size changed, new reference")
        }
        val vsRef = "${change.count} px vs reference, threshold ${change.threshold}, shift ${change.dx},${change.dy}"
        if (change.count > GLOBAL_CHANGE_SHARE * change.mask.size) {
            reference = sample
            return verdict(sample, "global change ($vsRef, max ${(GLOBAL_CHANGE_SHARE * change.mask.size).toInt()}), new reference")
        }
        // Specks below the hole size are noise; anything else not hole-shaped vetoes.
        val all = blobs(change.mask, sample.width)
        val found = all.filter { it.area >= HOLE_MIN_AREA }
        val fired = found.isNotEmpty() && found.all { it.holeSized }
        // A still, clean sample becomes the reference, so slow light drift and
        // tripod creep never pile up. Safe for holes: a new one first shows as
        // "moving" (more than SETTLED_MAX_PIXELS changed), and fires on the next sample.
        if (found.isEmpty()) reference = sample
        val why = when {
            found.isEmpty() -> "no blob of $HOLE_MIN_AREA+ px"
            fired -> "FIRE: ${found.size} hole(s) ${found.joinToString()}"
            else -> "vetoed by ${found.filterNot { it.holeSized }.joinToString()}"
        }
        return verdict(sample, "$why\n$vsRef", all, fired)
    }

    private fun verdict(sample: LumaFrame, reason: String, blobs: List<Blob> = emptyList(), fired: Boolean = false): Boolean {
        // Single-pixel specks are just sensor noise; cap so the overlay stays cheap.
        last = WatchVerdict(reason, sample.width, sample.height, sample.rotation, blobs.filter { it.area > 1 }.take(60))
        return fired
    }

    /** After a scan: the next sample becomes the new reference. */
    fun reset() {
        reference = null
        previous = null
    }
}

/** One [NewHoleWatch.offer] explained, with the changed blobs in frame pixels. */
internal class WatchVerdict(
    val reason: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val rotation: Int,
    val blobs: List<Blob>,
)
