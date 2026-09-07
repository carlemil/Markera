package se.kjellstrand.markera.series

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
)

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

private fun pickRing(pick: Int) = if (pick == SCORE_PICKER_INNER_TEN) 10 else pick

private fun pickInnerTen(pick: Int) = pick == SCORE_PICKER_INNER_TEN

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
