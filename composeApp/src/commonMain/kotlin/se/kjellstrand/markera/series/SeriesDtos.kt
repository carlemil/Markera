package se.kjellstrand.markera.series

import kotlin.math.hypot
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN
import se.kjellstrand.markera.vision.HitScore

/**
 * The Markera backend payloads (see `server/`). Deliberately duplicated
 * rather than shared: `server/` is a standalone Gradle project.
 */
/**
 * One hole. [ring]/[innerTen] are the *confirmed* values (what the user left in
 * the pickers); [detectedRing]/[detectedInnerTen] are what the detector said, so
 * the pair is training data. Three shapes, all derived, no flag column:
 * detected (`detectedRing != null`), manual (a position but no detection) and
 * typed (a picker value with no hole at all — everything nullable is null).
 */
@Serializable
data class HoleDto(
    val x: Double?,
    val y: Double?,
    val ring: Int,
    val innerTen: Boolean,
    val distanceMm: Double?,
    val detectedRing: Int? = null,
    val detectedInnerTen: Boolean? = null,
    /** Where the detector put the hole; null for a hand-placed or typed one. */
    val detectedX: Double? = null,
    val detectedY: Double? = null,
)

@Serializable
data class SeriesRequest(
    val timestamp: String,
    val caliber: String,
    val holes: List<HoleDto>,
)

@Serializable
data class SeriesDto(
    val id: Long,
    val timestamp: String,
    val caliber: String,
    val holes: List<HoleDto>,
    /** Defaulted so series stored before image upload existed still parse. */
    val hasImage: Boolean = false,
    /**
     * Source-frame pixel size the hole [HoleDto.x]/[HoleDto.y] are in. The
     * stored JPEG is that frame downscaled with the same aspect, so a marker
     * sits at fraction `x / imageWidth` of it. Null for series stored before
     * the size was recorded — then the markers can't be placed.
     */
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

@Serializable
data class BackendAuthResponse(val token: String, val userId: Long)

/** History page size, and the `limit` the client asks for. */
const val SERIES_PAGE_SIZE = 50

/** `before` for the next page, or null when [page] was short — i.e. the last one. */
fun nextPageCursor(page: List<SeriesDto>): Long? =
    if (page.size == SERIES_PAGE_SIZE) page.lastOrNull()?.id else null

/** An inner ten already carries ring 10, so a plain sum is the series total. */
fun SeriesDto.total(): Int = holes.sumOf { it.ring }

/** The hits highest first, `X` for an inner ten — e.g. `X 10 9 9 8`. */
fun SeriesDto.scoreLine(): String =
    holes.sortedWith(compareByDescending<HoleDto> { it.ring }.thenByDescending { it.innerTen })
        .joinToString(" ") { if (it.innerTen) "X" else it.ring.toString() }

/** Hole positions travel as raw source-image pixels; the server just stores them. */
fun HitScore.toHoleDto(): HoleDto = HoleDto(
    x = centerXpx.toDouble(),
    y = centerYpx.toDouble(),
    ring = ring,
    innerTen = isInnerTen,
    distanceMm = distanceMm,
    detectedRing = if (manual) null else ring,
    detectedInnerTen = if (manual) null else isInnerTen,
    detectedX = if (manual) null else centerXpx.toDouble(),
    detectedY = if (manual) null else centerYpx.toDouble(),
)

/** The user changed the score the detector reported for this hole. */
fun HoleDto.isEdited(): Boolean =
    detectedRing != null && (detectedRing != ring || (detectedInnerTen == true) != innerTen)

private fun scoreLabel(ring: Int, innerTen: Boolean) = if (innerTen) "X" else ring.toString()

/** A hole's score as the pickers show it: `X` for an inner ten, else the ring. */
fun HoleDto.label(): String = scoreLabel(ring, innerTen)

/** What the detector scored this hole, or null when it never saw it. */
fun HoleDto.detectedLabel(): String? =
    detectedRing?.let { scoreLabel(it, detectedInnerTen == true) }

/**
 * The score to show in the detail screen's manual cell: the confirmed one when
 * it overrides a detection, or when there is no detection to fall back on.
 * Null means "nothing of the user's own here" — the cell shows a placeholder.
 */
fun HoleDto.manualLabel(): String? =
    if (detectedRing == null || isEdited()) label() else null

/** Puts the score back to what the detector said; the position is left alone. */
fun HoleDto.withDetectedScore(): HoleDto =
    if (detectedRing == null) this else copy(ring = detectedRing, innerTen = detectedInnerTen == true)

/**
 * How a hole came about, for the detail list: `8 → 9` when the score was
 * edited, else [manual] (positioned by hand), [typed] (no position at all) or
 * blank; `, `[moved] appended when it was dragged off the detected spot. The
 * words are parameters so the Swedish stays in `strings.xml`.
 */
fun HoleDto.kindText(manual: String, typed: String, moved: String): String {
    val base = when {
        isEdited() -> "${scoreLabel(detectedRing!!, detectedInnerTen == true)} → ${label()}"
        x == null -> typed
        detectedRing == null -> manual
        else -> ""
    }
    val wasMoved = detectedX != null && (x != detectedX || y != detectedY)
    return when {
        !wasMoved -> base
        base.isEmpty() -> moved
        else -> "$base, $moved"
    }
}

/**
 * Index of the hole nearest [x],[y] (source-image px) within [maxDist], or -1
 * when nothing is in reach. Holes with no position can't be hit.
 */
fun List<HoleDto>.nearestHoleIndex(x: Double, y: Double, maxDist: Double): Int {
    var best = -1
    var bestDist = maxDist
    forEachIndexed { i, hole ->
        val hx = hole.x ?: return@forEachIndexed
        val hy = hole.y ?: return@forEachIndexed
        val dist = hypot(x - hx, y - hy)
        if (dist < bestDist) {
            best = i
            bestDist = dist
        }
    }
    return best
}

/**
 * Overlay the score pickers on the detected holes: picker slot `i` is hole `i`
 * (see `MarkeraViewModelImpl.onHolesDetected`), so its value becomes that hole's
 * confirmed ring — a 0 included, which marks the detection as a false positive.
 * A value past the last hole is a hole the detector missed and the user typed
 * (no position, no detection); a 0 there is just an empty slot. Holes past the
 * end of [topScores] keep what they had.
 */
fun SeriesRequest.withPicks(topScores: List<Int>): SeriesRequest = copy(
    holes = holes.mapIndexed { i, hole ->
        topScores.getOrNull(i)?.let { hole.copy(ring = pickRing(it), innerTen = pickInnerTen(it)) }
            ?: hole
    } + topScores.drop(holes.size).filter { it > 0 }.map {
        HoleDto(x = null, y = null, ring = pickRing(it), innerTen = pickInnerTen(it), distanceMm = null)
    },
)

/** Picker index -> stored ring/inner-ten pair; shared with the detail screen's editing. */
fun pickRing(pick: Int) = if (pick == SCORE_PICKER_INNER_TEN) 10 else pick

fun pickInnerTen(pick: Int) = pick == SCORE_PICKER_INNER_TEN

fun seriesRequest(
    scores: List<HitScore>,
    caliber: Caliber,
    timestamp: String = nowIso(),
): SeriesRequest = SeriesRequest(
    timestamp = timestamp,
    caliber = caliber.label,
    holes = scores.map { it.toHoleDto() },
)

/** ISO-8601 instant with a `Z` suffix — what the server's `Instant.parse` accepts. */
@OptIn(ExperimentalTime::class)
fun nowIso(): String = Clock.System.now().toString()
