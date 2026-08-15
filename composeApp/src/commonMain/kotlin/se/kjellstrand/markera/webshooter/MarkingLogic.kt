package se.kjellstrand.markera.webshooter

import kotlinx.serialization.json.JsonPrimitive
import se.kjellstrand.markera.webshooter.api.dto.ClaimDto
import se.kjellstrand.markera.webshooter.api.dto.RegistrationResponse
import se.kjellstrand.markera.webshooter.api.dto.ResultDto
import se.kjellstrand.markera.webshooter.api.dto.SignupDto
import se.kjellstrand.markera.webshooter.api.dto.StationDto

/** One lane of the marking group with its shooter. */
data class LaneEntry(val lane: Int, val signup: SignupDto)

/**
 * Everything the wizard needs for one marking group, built from the
 * registration payload: the group's lanes (with signups + all existing
 * results) and the competition's stations (= series), all loaded once —
 * series navigation is local, matching the web client.
 */
data class MarkingContext(
    val competitionId: Int,
    val patrolSortorder: Int,
    val laneStart: Int,
    val laneEnd: Int,
    val stations: List<StationDto>,
    val lanes: List<LaneEntry>,
)

/**
 * Pure decision logic for the marking wizard, mirroring the web's
 * MobileScoringV2Controller. Results reference stations by *sortorder*
 * (results[].stations_id == station.sortorder), exactly like the web.
 */
object MarkingLogic {

    fun buildContext(
        competitionId: Int,
        patrolSortorder: Int,
        laneStart: Int,
        laneEnd: Int,
        registration: RegistrationResponse,
    ): MarkingContext {
        val stations = registration.stations
            .filter { it.removed != true }
            .sortedBy { it.sortorder }
        val lanes = registration.signups
            .mapNotNull { (laneKey, signup) ->
                val lane = laneKey.toIntOrNull() ?: return@mapNotNull null
                if (lane < laneStart || lane > laneEnd || signup?.user == null) null
                else LaneEntry(lane, signup)
            }
            .sortedBy { it.lane }
        return MarkingContext(competitionId, patrolSortorder, laneStart, laneEnd, stations, lanes)
    }

    fun resultFor(signup: SignupDto, stationSortorder: Int): ResultDto? =
        signup.results.firstOrNull { it.stationsId == stationSortorder }

    fun isScored(result: ResultDto?): Boolean = result?.scoredAt != null

    /**
     * First (stationIndex, laneIndex) that has no registered result — the
     * resume position. Null when everything is marked.
     */
    fun firstOpenPosition(context: MarkingContext): Pair<Int, Int>? {
        context.stations.forEachIndexed { si, station ->
            context.lanes.forEachIndexed { li, entry ->
                if (!isScored(resultFor(entry.signup, station.sortorder))) return si to li
            }
        }
        return null
    }

    /** True when the current user may not mark this lane (own target, non-admin). */
    fun isSelf(entry: LaneEntry, userId: Long?, isAdmin: Boolean): Boolean =
        !isAdmin && userId != null && entry.signup.usersId == userId

    /**
     * Index of the next lane after [fromIndex] that is neither scored nor
     * claimed by someone else nor the user's own target; null when none left
     * (→ station done / show summary).
     */
    fun nextOpenLaneIndex(
        context: MarkingContext,
        stationSortorder: Int,
        fromIndex: Int,
        claims: Map<Int, ClaimDto?>,
        userId: Long?,
        isAdmin: Boolean,
    ): Int? {
        for (i in (fromIndex + 1) until context.lanes.size) {
            val entry = context.lanes[i]
            if (isScored(resultFor(entry.signup, stationSortorder))) continue
            if (claims[entry.lane] != null) continue
            if (isSelf(entry, userId, isAdmin)) continue
            return i
        }
        return null
    }

    fun allRegistered(context: MarkingContext, stationSortorder: Int): Boolean =
        context.lanes.isNotEmpty() && context.lanes.all {
            isScored(resultFor(it.signup, stationSortorder))
        }

    /** The updated ResultDto for a save, used both for the payload and the local cache. */
    fun updatedResult(
        signup: SignupDto,
        stationSortorder: Int,
        picks: List<Int>,
        nowIso: String,
    ): ResultDto {
        val existing = resultFor(signup, stationSortorder)
        return ResultDto(
            id = existing?.id,
            signupsId = signup.id,
            stationsId = stationSortorder,
            points = ShotMapping.points(picks),
            hits = ShotMapping.xCount(picks),
            figureHits = 0,
            stationFigureHits = picks.map { JsonPrimitive(ShotMapping.pickerToShot(it)) },
            finals = 0,
            distinguish = 0,
            scoredAt = nowIso,
        )
    }

    /** The signup's full results list with [updated] replacing/appending its station's entry. */
    fun mergedResults(signup: SignupDto, updated: ResultDto): List<ResultDto> {
        val others = signup.results.filter { it.stationsId != updated.stationsId }
        return others + updated
    }

    /** mobile-v2 `audit` form payload — field set copied from the web client. */
    fun buildAudit(
        competitionId: Int,
        signupId: Long,
        stationSortorder: Int,
        lane: Int,
        picks: List<Int>,
        clientNonce: String,
    ): Map<String, Any?> = mapOf(
        "competitions_id" to competitionId,
        "signup_id" to signupId,
        "station_sortorder" to stationSortorder,
        "lane" to lane,
        "shots" to picks.map(ShotMapping::pickerToShot),
        "points" to ShotMapping.points(picks),
        "x_count" to ShotMapping.xCount(picks),
        "client_nonce" to clientNonce,
    )

    /** mobile-v2 `results` form payload entry for one ResultDto. */
    fun resultToForm(result: ResultDto): Map<String, Any?> = buildMap {
        result.id?.let { put("id", it) }
        put("signups_id", result.signupsId)
        put("stations_id", result.stationsId)
        put("points", result.points)
        put("hits", result.hits)
        put("figure_hits", result.figureHits)
        result.stationFigureHits?.let { shots ->
            put("station_figure_hits", shots.map { ShotMapping.shotToPicker(it)?.let(ShotMapping::pickerToShot) ?: "" })
        }
        put("finals", result.finals)
        put("distinguish", result.distinguish)
        result.scoredAt?.let { put("scored_at", it) }
    }
}
