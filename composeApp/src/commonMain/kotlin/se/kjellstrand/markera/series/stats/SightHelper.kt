package se.kjellstrand.markera.series.stats

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sight helper (docs/coaching-plan.md, card 8): a stable offset ([Finding.OffsetGroup.stable],
 * the same offset on 3+ separate days) turned into a sight correction. Pure, so it is unit-tested.
 *
 * Per caliber (the caliber stands in for the gun) the user may enter the shooting distance and
 * how many mm at the target one click moves the impact at that distance; with it the correction
 * is also given in clicks. [adjustedAtMs] is when the user said they adjusted the sights: the
 * offset finding then judges only series taken after it ([offsetCutOff]).
 *
 * A device preference: stored locally (one JSON map caliber label to setup, [encodeSightSetups]),
 * never sent to the server.
 */
@Serializable
data class SightSetup(
    val distanceM: Double = DEFAULT_DISTANCE_M,
    /** Mm at the target per click, at [distanceM]; null = not entered (mm only). */
    val mmPerClick: Double? = null,
    /** Epoch ms of "I adjusted the sights"; null = never. */
    val adjustedAtMs: Long? = null,
)

const val DEFAULT_DISTANCE_M = 25.0

/**
 * How to move the impact back to the centre. Signed mm at the target, whole mm: [upMm] positive
 * is up, [rightMm] positive is right. The rear sight moves the same way as the impact should, so
 * these are also the directions to turn. [upClicks]/[rightClicks] carry the same signs and are
 * null without a click value.
 */
data class SightCorrection(
    val upMm: Int,
    val rightMm: Int,
    val upClicks: Int?,
    val rightClicks: Int?,
    val distanceM: Double,
) {
    val hasClicks: Boolean get() = upClicks != null
}

/**
 * The correction for [offset] (target mm, y down: a group low and right needs the impact up and
 * left by as much). Mm are rounded to whole mm; clicks come from the unrounded mm ([clicksFor]).
 */
fun sightCorrection(offset: Finding.OffsetGroup, setup: SightSetup?): SightCorrection {
    val up = offset.mpiYMm
    val right = -offset.mpiXMm
    val perClick = setup?.mmPerClick?.takeIf { it.isValidClick() }
    return SightCorrection(
        upMm = roundHalfAway(up),
        rightMm = roundHalfAway(right),
        upClicks = perClick?.let { clicksFor(up, it) },
        rightClicks = perClick?.let { clicksFor(right, it) },
        distanceM = setup?.distanceM?.takeIf { it.isValidDistance() } ?: DEFAULT_DISTANCE_M,
    )
}

/** Whole clicks for a signed move of [mm], rounded half away from zero, so up and down round alike. */
fun clicksFor(mm: Double, mmPerClick: Double): Int {
    require(mmPerClick.isValidClick()) { "mm per click must be positive" }
    return roundHalfAway(mm / mmPerClick)
}

private fun roundHalfAway(v: Double): Int = if (v < 0) -(abs(v).roundToInt()) else v.roundToInt()

private fun Double.isValidClick() = isFinite() && this > 0.0
private fun Double.isValidDistance() = isFinite() && this > 0.0

/** A number the user typed, with either decimal mark; null unless it is a positive, finite value. */
fun parsePositiveDecimal(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** The adjustment as an instant, or null when the sights were never adjusted for this caliber. */
@OptIn(ExperimentalTime::class)
fun SightSetup?.offsetCutOff(): Instant? = this?.adjustedAtMs?.let { Instant.fromEpochMilliseconds(it) }

/** The series the offset finding may use: those taken strictly after [cutOff], or all without one. */
@OptIn(ExperimentalTime::class)
fun List<PlottedSeries>.sinceAdjustment(cutOff: Instant?): List<PlottedSeries> =
    if (cutOff == null) this else filter { it.at > cutOff }

private val sightJson = Json { ignoreUnknownKeys = true }

/** Keyed by caliber label. */
fun encodeSightSetups(setups: Map<String, SightSetup>): String = sightJson.encodeToString(setups)

/** Tolerant: a missing or unreadable value is no setups at all, never a crash. */
fun decodeSightSetups(text: String?): Map<String, SightSetup> =
    if (text.isNullOrBlank()) emptyMap() else runCatching { sightJson.decodeFromString<Map<String, SightSetup>>(text) }.getOrDefault(emptyMap())
