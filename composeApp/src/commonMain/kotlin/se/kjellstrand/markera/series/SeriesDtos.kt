package se.kjellstrand.markera.series

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.Serializable
import se.kjellstrand.markera.vision.HitScore

/**
 * The Markera backend payloads (see `server/`). Deliberately duplicated
 * rather than shared: `server/` is a standalone Gradle project.
 */
@Serializable
data class HoleDto(
    val x: Double,
    val y: Double,
    val ring: Int,
    val innerTen: Boolean,
    val distanceMm: Double,
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
)

@Serializable
data class BackendAuthResponse(val token: String, val userId: Long)

/** Hole positions travel as raw source-image pixels; the server just stores them. */
fun HitScore.toHoleDto(): HoleDto = HoleDto(
    x = centerXpx.toDouble(),
    y = centerYpx.toDouble(),
    ring = ring,
    innerTen = isInnerTen,
    distanceMm = distanceMm,
)

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
