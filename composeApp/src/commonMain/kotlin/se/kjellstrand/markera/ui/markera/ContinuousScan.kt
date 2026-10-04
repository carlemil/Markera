package se.kjellstrand.markera.ui.markera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ponytail: hand-set thresholds, calibrated on the phone against a real target
// at a real distance (a .22 hole is ~5-10 px at the ~480 px sample grid). A
// false trigger only costs one full rescan that reproduces the same holes; a
// miss just means the user taps Detect as before.

/** Side the analysis frame is box-averaged down to (averaging also kills sensor noise). */
internal const val WATCH_GRID = 480

/** How often the watch loop takes a sample. */
internal const val CHANGE_SAMPLE_MS = 700L

/**
 * How often the watch has the camera meter the light again (exposure and white
 * balance stay locked in between), so a cloud or the sun cannot leave the frame
 * far off a good exposure for a whole series.
 */
internal const val REMETER_INTERVAL_MS = 30_000L

/**
 * Whether the watch should re-meter now: [REMETER_INTERVAL_MS] since the last
 * metering and the [last] verdict settled — never while something moves or a
 * fired scan runs. The reference is kept, so [changeMask]'s light fit bridges
 * the exposure step.
 */
internal fun remeterDue(sinceMeteredMs: Long, last: WatchVerdict?): Boolean =
    sinceMeteredMs >= REMETER_INTERVAL_MS && last?.outcome != WatchOutcome.MOVING && last?.outcome != WatchOutcome.FIRE

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

/** Largest camera shake / creep (grid px) the compare aligns away. */
internal const val MAX_SHIFT = 4

/** Share of the frame changed past which it is light/occlusion/camera motion, not a hole. */
internal const val GLOBAL_CHANGE_SHARE = 0.01f

/**
 * A row-major luma frame of the live feed, in sensor orientation: [rotation] is
 * the clockwise turn (0/90/180/270) that makes it upright on screen.
 */
class LumaFrame(val width: Int, val height: Int, val luma: ByteArray, val rotation: Int = 0)

/** A [changeMask] result: the mask, the luma step it was cut at, the shift it aligned away and the [light] it took out. */
internal class Change(val mask: BooleanArray, val threshold: Int, val dx: Int, val dy: Int, val light: Light, val align: Alignment) {
    val count = mask.count { it }
}

/**
 * Local-light window side (grid px): the local light level is the mean over it.
 * Wide enough that a hole's own step barely moves its window's mean, narrow
 * enough to follow a soft shadow edge.
 */
internal const val LIGHT_WINDOW = 15

/**
 * Largest per-pixel step the local light level follows: caps a hole's pull on its
 * window's mean (a 5 px disk moves it by at most ~5 luma). A light change past it
 * is left in, so it shows as a global change and becomes the new reference.
 */
internal const val LIGHT_MAX_STEP = 40

/** Luma outside this range is clipped: it says nothing about the light. */
private val UNCLIPPED = 6..249

/** The global light change cur ≈ [gain]·ref + [offset] (exposure, a cloud: a gain, not just an offset). */
internal class Light(val gain: Float, val offset: Float) {
    fun of(ref: Int) = gain * ref + offset

    override fun toString() = "light ×${(gain * 100).roundToInt() / 100f}${if (offset.roundToInt() < 0) "" else "+"}${offset.roundToInt()}"
}

/**
 * The least-squares [Light] of cur(x, y) on ref at [align]'s shift, over every 4th
 * pixel where neither is clipped. Aligned pairs only: across a misaligned edge the
 * fit dilutes the gain towards 0. Falls back to a plain mean offset when the
 * unclipped pixels are too few or too flat to fit a gain.
 */
internal fun fitLight(ref: LumaFrame, cur: LumaFrame, align: Alignment = Alignment(cur.width, cur.height, 0, 0)): Light {
    val w = cur.width
    val radius = MAX_SHIFT + TILE_SHIFT
    var n = 0L; var sx = 0L; var sy = 0L; var sxx = 0L; var sxy = 0L
    for (y in radius until cur.height - radius step 4) {
        for (x in radius until w - radius step 4) {
            val t = align.tile(x, y)
            val r = ref.luma[(y + align.dys[t]) * w + x + align.dxs[t]].toInt() and 0xFF
            val c = cur.luma[y * w + x].toInt() and 0xFF
            if (r !in UNCLIPPED || c !in UNCLIPPED) continue
            n++; sx += r; sy += c; sxx += r * r; sxy += r * c
        }
    }
    if (n == 0L) return Light(1f, 0f)
    val det = (n * sxx - sx * sx).toDouble()
    val gain = if (n < 100 || det <= 0.0) 0.0 else (n * sxy - sx * sy) / det
    // Too few or too flat to fit a gain (or a nonsense one): the old offset-only model.
    if (gain !in 0.25..4.0) return Light(1f, ((sy - sx).toDouble() / n).toFloat())
    return Light(gain.toFloat(), ((sy - gain * sx) / n).toFloat())
}

/** The grid is split into [TILES]×[TILES] tiles for the per-tile alignment. */
internal const val TILES = 6

/** How far (grid px) a tile's own shift may stray from the global one. */
internal const val TILE_SHIFT = 2

/**
 * Per-tile whole-pixel shifts, so cur(x, y) ≈ ref(x + dx, y + dy) with (dx, dy)
 * the shift of the tile holding (x, y). One global shift cannot follow a slight
 * turn, keystone or paper flex: at the 600 px grid a 0.5° turn already moves the
 * corners ~2.6 px each way against the centre. Within a tile (100 px there) the
 * same turn spreads under a pixel, which the 3×3 interval compare absorbs.
 * Seams are hard: each pixel takes its own tile's shift.
 */
internal class Alignment(private val w: Int, private val h: Int, val dxs: IntArray, val dys: IntArray) {
    constructor(w: Int, h: Int, dx: Int, dy: Int) : this(w, h, IntArray(TILES * TILES) { dx }, IntArray(TILES * TILES) { dy })

    private val cols = IntArray(w) { it * TILES / w }
    private val rows = IntArray(h) { it * TILES / h * TILES }

    fun tile(x: Int, y: Int) = rows[y] + cols[x]
}

/**
 * Refines the global ([dx], [dy]) per tile: each textured tile takes the shift
 * within ±[TILE_SHIFT] of it with the least absolute difference (every 2nd pixel,
 * the [light] taken out); a flat tile keeps the global shift, since it has nothing
 * to align on and any shift fits it equally. Textured = at least a quarter tile
 * side's worth of edge samples, an edge being a central difference of
 * [CHANGE_MIN_DELTA]+ across or down: that is half of one edge crossing the tile
 * (a crossing edge leaves ~side/2 samples), while a weaker step could not show up
 * as a changed pixel anyway, and noise alone never reaches it.
 */
internal fun estimateTiles(ref: LumaFrame, cur: LumaFrame, light: Light, dx: Int, dy: Int): Alignment {
    val w = cur.width
    val h = cur.height
    val c = cur.luma
    val margin = MAX_SHIFT + TILE_SHIFT
    val lit = IntArray(256) { light.of(it).roundToInt() }
    val r = ref.luma
    val dxs = IntArray(TILES * TILES) { dx }
    val dys = IntArray(TILES * TILES) { dy }
    for (ty in 0 until TILES) {
        val y0 = max(margin, ty * h / TILES)
        val y1 = min(h - margin, (ty + 1) * h / TILES)
        for (tx in 0 until TILES) {
            val x0 = max(margin, tx * w / TILES)
            val x1 = min(w - margin, (tx + 1) * w / TILES)
            var edges = 0
            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val i = y * w + x
                    val gx = abs((c[i + 1].toInt() and 0xFF) - (c[i - 1].toInt() and 0xFF))
                    val gy = abs((c[i + w].toInt() and 0xFF) - (c[i - w].toInt() and 0xFF))
                    if (max(gx, gy) >= CHANGE_MIN_DELTA) edges++
                }
            }
            if (edges < (x1 - x0) / 4) continue
            var best = Int.MAX_VALUE
            for (sy in dy - TILE_SHIFT..dy + TILE_SHIFT) {
                for (sx in dx - TILE_SHIFT..dx + TILE_SHIFT) {
                    var sad = 0
                    for (y in y0 until y1 step 2) {
                        val row = y * w
                        val refRow = (y + sy) * w + sx
                        for (x in x0 until x1 step 2) {
                            sad += abs((c[row + x].toInt() and 0xFF) - lit[r[refRow + x].toInt() and 0xFF])
                        }
                    }
                    val t = ty * TILES + tx
                    // Ties (along a straight edge) keep the shift closest to the global one.
                    if (sad < best || (sad == best && abs(sx - dx) + abs(sy - dy) < abs(dxs[t] - dx) + abs(dys[t] - dy))) {
                        best = sad
                        dxs[t] = sx
                        dys[t] = sy
                    }
                }
            }
        }
    }
    return Alignment(w, h, dxs, dys)
}

/**
 * The whole-pixel shake (dx, dy) that best lines [cur] up with [ref], so
 * cur(x, y) ≈ ref(x + dx, y + dy): the least absolute difference, the global
 * [light] change taken out, over every 4th pixel — the standard block-matching
 * global motion estimate, enough for a few px of tripod shake.
 */
internal fun estimateShift(ref: LumaFrame, cur: LumaFrame, light: Light, radius: Int = MAX_SHIFT): Pair<Int, Int> {
    val w = cur.width
    val h = cur.height
    var best = Float.MAX_VALUE
    var shift = 0 to 0
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            var sad = 0f
            for (y in radius until h - radius step 4) {
                for (x in radius until w - radius step 4) {
                    val v = cur.luma[y * w + x].toInt() and 0xFF
                    sad += abs(v - light.of(ref.luma[(y + dy) * w + x + dx].toInt() and 0xFF))
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
 * Changed-pixel mask of [cur] against [ref]. The light is taken out in two
 * steps: globally ([fitLight], a gain and an offset, fitted again once
 * [estimateShift] and then [estimateTiles] per tile have aligned the camera
 * shake and any slight turn away), then locally — a shadow or a lamp on part of the target — by subtracting the rest of the difference
 * averaged over a [LIGHT_WINDOW] box, each pixel's share capped at
 * [LIGHT_MAX_STEP] and clipped pixels left out. Light is large-scale and a hole
 * small, so the box mean follows the one and not the other. A pixel's
 * difference is then how far it lies outside the [min, max] of the nine
 * shifted, relit ref pixels around it: a sub-pixel shift only slides an edge
 * pixel between its neighbours' values, so it stays inside and never counts
 * (the closest-single-neighbour test left 1 px lines along every edge). The
 * border band the shift uncovers, plus one pixel, never counts. Clipped cur
 * pixels still count: a hole in the black often shows the lit wall behind at 255.
 */
internal fun changeMask(ref: LumaFrame, cur: LumaFrame): Change? {
    if (ref.width != cur.width || ref.height != cur.height) return null
    val w = cur.width
    val h = cur.height
    val r = ref.luma
    val c = cur.luma
    val (dx, dy) = estimateShift(ref, cur, fitLight(ref, cur))
    val align = estimateTiles(ref, cur, fitLight(ref, cur, Alignment(w, h, dx, dy)), dx, dy)
    val light = fitLight(ref, cur, align)
    val levels = IntArray(256) { light.of(it).roundToInt() }
    val lit = IntArray(r.size) { levels[r[it].toInt() and 0xFF] }
    // Integral images of the capped residual and of how many pixels have one, for O(1) box sums.
    val stride = w + 1
    val sums = IntArray(stride * (h + 1))
    val counts = IntArray(stride * (h + 1))
    for (y in 0 until h) {
        var rowSum = 0
        var rowCount = 0
        for (x in 0 until w) {
            val t = align.tile(x, y)
            val rx = x + align.dxs[t]
            val ry = y + align.dys[t]
            val v = c[y * w + x].toInt() and 0xFF
            if (rx in 0 until w && ry in 0 until h && v in UNCLIPPED && (r[ry * w + rx].toInt() and 0xFF) in UNCLIPPED) {
                rowSum += (v - lit[ry * w + rx]).coerceIn(-LIGHT_MAX_STEP, LIGHT_MAX_STEP)
                rowCount++
            }
            sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + rowSum
            counts[(y + 1) * stride + x + 1] = counts[y * stride + x + 1] + rowCount
        }
    }
    val half = LIGHT_WINDOW / 2
    val diff = IntArray(c.size)
    val histogram = IntArray(256)
    for (y in 0 until h) {
        val top = max(0, y - half) * stride
        val bottom = min(h, y + half + 1) * stride
        for (x in 0 until w) {
            val t = align.tile(x, y)
            val rx = x + align.dxs[t]
            val ry = y + align.dys[t]
            // Needs the whole 3×3 around it: a clipped one has a narrower interval.
            if (rx !in 1 until w - 1 || ry !in 1 until h - 1) {
                histogram[0]++
                continue
            }
            val left = max(0, x - half)
            val right = min(w, x + half + 1)
            val n = counts[bottom + right] - counts[top + right] - counts[bottom + left] + counts[top + left]
            val sum = sums[bottom + right] - sums[top + right] - sums[bottom + left] + sums[top + left]
            val v = (c[y * w + x].toInt() and 0xFF) - if (n == 0) 0f else sum.toFloat() / n
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (ny in ry - 1..ry + 1) {
                for (nx in rx - 1..rx + 1) {
                    lo = min(lo, lit[ny * w + nx])
                    hi = max(hi, lit[ny * w + nx])
                }
            }
            val d = max(lo - v, v - hi).roundToInt().coerceIn(0, 255)
            diff[y * w + x] = d
            histogram[d]++
        }
    }
    var median = 0
    var seen = 0
    while (seen + histogram[median] <= c.size / 2) seen += histogram[median++]
    val threshold = max(CHANGE_MIN_DELTA, CHANGE_NOISE_FACTOR * median)
    return Change(BooleanArray(c.size) { diff[it] >= threshold }, threshold, dx, dy, light, align)
}

/** How many still, clean samples the reference averages. */
internal const val REFERENCE_SAMPLES = 3

/**
 * Running mean of same-grid frames: the analyzer [add]s every frame it gets and
 * the watch [take]s their mean as one sample (n frames, about √n less noise).
 * A frame of another size starts the mean over. Not thread-safe.
 */
internal class FrameMean {
    private var sums: IntArray? = null
    private var count = 0
    private var width = 0
    private var height = 0
    private var rotation = 0

    fun add(frame: LumaFrame) {
        var s = sums
        if (s == null || frame.width != width || frame.height != height) {
            s = IntArray(frame.width * frame.height)
            sums = s
            count = 0
            width = frame.width
            height = frame.height
        }
        rotation = frame.rotation
        val luma = frame.luma
        for (i in s.indices) s[i] += luma[i].toInt() and 0xFF
        count++
    }

    /** The mean (rounded) of the frames added since the last take, or null when none were. */
    fun take(): LumaFrame? {
        val s = sums ?: return null
        val n = count
        sums = null
        return LumaFrame(width, height, ByteArray(s.size) { ((s[it] + n / 2) / n).toByte() }, rotation)
    }

    fun clear() {
        sums = null
    }
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

    /** The last still, clean samples, oldest first; [reference] is their mean. */
    private val history = ArrayDeque<LumaFrame>()

    /**
     * Makes [sample] the reference, or — given the clean [change] it compared
     * with against the reference — the mean of it and the previous
     * [REFERENCE_SAMPLES] - 1 still, clean ones (about √3 less noise). The older
     * ones are first moved onto [sample] by that change's own tile shifts, so
     * creep or a slight turn never blurs the mean. They are not relit: a gain
     * fitted on noisy frames is biased low (noise in the regressor), and relighting
     * by it would flatten the reference a little more every rebase; the light
     * drift between still samples is far below that. Without a change (a new
     * scene, reference or framing) the history starts over: a mean must not
     * straddle that.
     */
    private fun rebase(sample: LumaFrame, change: Change? = null) {
        val moved = if (change == null) emptyList() else history.map { it.movedOnto(change) }
        history.clear()
        history.addAll(moved)
        history.addLast(sample)
        while (history.size > REFERENCE_SAMPLES) history.removeFirst()
        reference = if (history.size == 1) sample else FrameMean().apply { history.forEach(::add) }.take()
    }

    /** Why the last [offer] did or did not fire (the debug overlay shows it). */
    var last: WatchVerdict? = null
        private set

    fun offer(sample: LumaFrame): Boolean {
        val ref = reference
        val prev = previous
        previous = sample
        if (ref == null) {
            rebase(sample)
            return verdict(sample, "reference taken")
        }
        // Settled = no blob of hole size since the previous sample: scattered
        // sensor-noise pixels never join into one, a hand or a fresh hole does.
        val moving = prev?.let { changeMask(it, sample) }
        val biggest = moving?.let { m -> blobs(m.mask, sample.width).maxByOrNull { it.area } }
        if (moving == null || (biggest != null && biggest.area >= HOLE_MIN_AREA)) {
            val what = moving?.let { "${it.count} px changed, biggest blob $biggest, shift ${it.dx},${it.dy}" } ?: "?"
            val why = "moving since last sample: $what (settled below a $HOLE_MIN_AREA px blob)"
            return verdict(sample, why, outcome = WatchOutcome.MOVING, reference = ref, previous = prev)
        }
        val change = changeMask(ref, sample) ?: run {
            rebase(sample)
            return verdict(sample, "frame size changed, new reference")
        }
        val vsRef = "${change.count} px vs reference, threshold ${change.threshold}, shift ${change.dx},${change.dy}, ${change.light}"
        if (change.count > GLOBAL_CHANGE_SHARE * change.mask.size) {
            rebase(sample)
            return verdict(sample, "global change ($vsRef, max ${(GLOBAL_CHANGE_SHARE * change.mask.size).toInt()}), new reference")
        }
        // Specks below the hole size are noise; anything else not hole-shaped vetoes.
        val all = blobs(change.mask, sample.width)
        val found = all.filter { it.area >= HOLE_MIN_AREA }
        val fired = found.isNotEmpty() && found.all { it.holeSized }
        // A still, clean sample becomes the reference, so slow light drift and
        // tripod creep never pile up. Safe for holes: a new one first shows as
        // "moving" (a blob of HOLE_MIN_AREA+ against the previous sample), and fires on the next.
        // It joins the reference mean, the older samples moved onto its framing.
        if (found.isEmpty()) rebase(sample, change)
        val why = when {
            found.isEmpty() -> "no blob of $HOLE_MIN_AREA+ px"
            fired -> "FIRE: ${found.size} hole(s) ${found.joinToString()}"
            else -> "vetoed by ${found.filterNot { it.holeSized }.joinToString()}"
        }
        val outcome = when {
            fired -> WatchOutcome.FIRE
            found.isEmpty() -> WatchOutcome.OTHER
            else -> WatchOutcome.VETO
        }
        return verdict(sample, "$why\n$vsRef", all, outcome, ref, prev)
    }

    private fun verdict(
        sample: LumaFrame,
        reason: String,
        blobs: List<Blob> = emptyList(),
        outcome: WatchOutcome = WatchOutcome.OTHER,
        reference: LumaFrame? = null,
        previous: LumaFrame? = null,
    ): Boolean {
        // Single-pixel specks are just sensor noise; cap so the overlay stays cheap.
        last = WatchVerdict(
            reason, sample.width, sample.height, sample.rotation, blobs.filter { it.area > 1 }.take(60),
            outcome, reference, previous, sample,
        )
        return outcome == WatchOutcome.FIRE
    }

    /** Replay only: the state a recorded verdict started from, so the next [offer] redoes it exactly. */
    fun seed(reference: LumaFrame, previous: LumaFrame) {
        rebase(reference)
        this.previous = previous
    }

    /**
     * After a re-meter: the reference stays (the light fit bridges the step), but
     * the next clean sample starts its mean over, so no mean mixes two exposures.
     */
    fun lightChanged() {
        history.clear()
    }

    /** After a scan: the next sample becomes the new reference. */
    fun reset() {
        reference = null
        previous = null
        history.clear()
    }
}

/**
 * This (an earlier reference sample) as [change]'s sample would see it:
 * this(x + dx, y + dy) by the tile shift at (x, y), clamped at the border (the
 * band the compare never counts).
 */
private fun LumaFrame.movedOnto(change: Change): LumaFrame {
    val align = change.align
    if (align.dxs.all { it == 0 } && align.dys.all { it == 0 }) return this
    val out = ByteArray(luma.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val t = align.tile(x, y)
            val sx = (x + align.dxs[t]).coerceIn(0, width - 1)
            val sy = (y + align.dys[t]).coerceIn(0, height - 1)
            out[y * width + x] = luma[sy * width + sx]
        }
    }
    return LumaFrame(width, height, out, rotation)
}

/** What a [WatchVerdict] came to; the debug recorder keeps the fires, vetoes and moving streaks. */
internal enum class WatchOutcome { OTHER, MOVING, FIRE, VETO }

/**
 * One [NewHoleWatch.offer] explained, with the changed blobs in frame pixels, and
 * the frames it compared: [reference] and [previous] (null when the offer compared
 * nothing) against [current], the offered sample.
 */
internal class WatchVerdict(
    val reason: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val rotation: Int,
    val blobs: List<Blob>,
    val outcome: WatchOutcome = WatchOutcome.OTHER,
    val reference: LumaFrame? = null,
    val previous: LumaFrame? = null,
    val current: LumaFrame? = null,
)

/**
 * The files [FrameSource.recordWatch] keeps for this verdict, by name suffix: the
 * three compared frames as PGM plus a one-line "rotation <r>\t<reason>"; null when
 * the offer compared nothing.
 */
internal fun WatchVerdict.recording(): Map<String, ByteArray>? {
    val ref = reference ?: return null
    val prev = previous ?: return null
    val cur = current ?: return null
    return mapOf(
        "ref.pgm" to encodePgm(ref),
        "prev.pgm" to encodePgm(prev),
        "cur.pgm" to encodePgm(cur),
        "verdict.txt" to "rotation $rotation\t${reason.replace('\n', ' ')}\n".encodeToByteArray(),
    )
}

/** [frame] as a binary PGM (P5, maxval 255): a header, then the raw luma rows. */
internal fun encodePgm(frame: LumaFrame): ByteArray =
    "P5\n${frame.width} ${frame.height}\n255\n".encodeToByteArray() + frame.luma

/** The inverse of [encodePgm] (no comments, maxval 255); throws on anything else. */
internal fun decodePgm(bytes: ByteArray, rotation: Int = 0): LumaFrame {
    // Four whitespace-separated header tokens, then exactly one whitespace byte.
    val tokens = mutableListOf<String>()
    var i = 0
    while (tokens.size < 4) {
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        val start = i
        while (i < bytes.size && !bytes[i].toInt().toChar().isWhitespace()) i++
        require(i > start) { "PGM header ends early" }
        tokens += bytes.decodeToString(start, i)
    }
    require(tokens[0] == "P5" && tokens[3] == "255") { "not an 8-bit binary PGM: ${tokens[0]} maxval ${tokens[3]}" }
    val w = tokens[1].toInt()
    val h = tokens[2].toInt()
    val data = i + 1
    require(bytes.size == data + w * h) { "PGM ${w}x$h needs ${w * h} bytes, has ${bytes.size - data}" }
    return LumaFrame(w, h, bytes.copyOfRange(data, bytes.size), rotation)
}
