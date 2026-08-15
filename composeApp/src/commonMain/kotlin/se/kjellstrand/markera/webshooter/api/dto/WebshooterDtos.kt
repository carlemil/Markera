package se.kjellstrand.markera.webshooter.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * DTOs for the webshooter.se API (Laravel, base /api/v4.1.9/). The payloads are
 * large model dumps; these declare only the fields the app uses and are parsed
 * with ignoreUnknownKeys. Booleans arrive inconsistently as true/false or 0/1
 * depending on endpoint, hence [LenientBooleanSerializer].
 */
object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val prim = json.decodeJsonElement().jsonPrimitive
        prim.booleanOrNull?.let { return it }
        return (prim.intOrNull ?: 0) != 0
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

typealias LenientBoolean = @Serializable(with = LenientBooleanSerializer::class) Boolean

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
data class UserResponse(val user: WsUser)

@Serializable
data class WsUser(
    @SerialName("is_admin") val isAdmin: LenientBoolean = false,
    val fullname: String? = null,
    val email: String? = null,
)

@Serializable
data class CompetitionsResponse(val competitions: CompetitionsPage)

@Serializable
data class CompetitionsPage(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    val total: Int = 0,
    val data: List<CompetitionSummaryDto> = emptyList(),
)

@Serializable
data class CompetitionSummaryDto(
    val id: Int,
    val name: String,
    val date: String? = null,
    @SerialName("results_type") val resultsType: String? = null,
    /** "open", "closed", or "completed" (= avslutad). */
    val status: String? = null,
    @SerialName("status_human") val statusHuman: String? = null,
    @SerialName("contact_city") val contactCity: String? = null,
) {
    /** Avslutad — filtered out of the marking flow's competition picker. */
    val isCompleted: Boolean get() = status == "completed"
}

@Serializable
data class ScoringTargetsResponse(
    val competition: ScoringCompetitionDto? = null,
    @SerialName("marking_groups") val markingGroups: List<MarkingGroupDto> = emptyList(),
    @SerialName("active_patrol") val activePatrol: ActivePatrolDto? = null,
)

@Serializable
data class ScoringCompetitionDto(
    val id: Int,
    val name: String? = null,
    @SerialName("results_type") val resultsType: String? = null,
    @SerialName("mobile_scoring_enabled") val mobileScoringEnabled: LenientBoolean = false,
)

@Serializable
data class MarkingGroupDto(
    val id: Int,
    val name: String,
    val guid: String,
    @SerialName("lane_start") val laneStart: Int,
    @SerialName("lane_end") val laneEnd: Int,
    @SerialName("last_marked") val lastMarked: LastMarkedDto? = null,
    @SerialName("marked_count") val markedCount: Int = 0,
    @SerialName("expected_count") val expectedCount: Int = 0,
    val complete: LenientBoolean = false,
)

@Serializable
data class LastMarkedDto(
    val serie: Int? = null,
    val lane: Int? = null,
    val shooter: String? = null,
)

@Serializable
data class ActivePatrolDto(
    val sortorder: Int? = null,
    @SerialName("start_time_human") val startTimeHuman: String? = null,
)

@Serializable
data class ResolveResponse(
    val type: String,
    @SerialName("competition_id") val competitionId: Int,
    val lanes: List<Int> = emptyList(),
    @SerialName("lane_start") val laneStart: Int? = null,
    @SerialName("lane_end") val laneEnd: Int? = null,
    @SerialName("patrol_sortorder") val patrolSortorder: Int? = null,
)

@Serializable
data class RegistrationResponse(
    // Keyed by lane number ("21".."51"); a lane without a signup can be null.
    val signups: Map<String, SignupDto?> = emptyMap(),
    val stations: List<StationDto> = emptyList(),
    val patrols: List<PatrolDto> = emptyList(),
)

@Serializable
data class SignupDto(
    val id: Long,
    @SerialName("users_id") val usersId: Long? = null,
    val user: SignupUserDto? = null,
    val club: NamedDto? = null,
    val weaponclass: WeaponClassDto? = null,
    val results: List<ResultDto> = emptyList(),
)

@Serializable
data class SignupUserDto(val fullname: String? = null)

@Serializable
data class NamedDto(val name: String? = null)

@Serializable
data class WeaponClassDto(
    val classname: String? = null,
    @SerialName("classname_general") val classnameGeneral: String? = null,
)

@Serializable
data class StationDto(
    val id: Long,
    val sortorder: Int,
    val shots: Int = 5,
    @SerialName("station_nr") val stationNr: Int? = null,
    val removed: Boolean? = null,
)

@Serializable
data class PatrolDto(
    val id: Long,
    val sortorder: Int,
    @SerialName("mobile_scoring_active") val mobileScoringActive: LenientBoolean = false,
    @SerialName("start_time_human") val startTimeHuman: String? = null,
)

@Serializable
data class ResultDto(
    val id: Long? = null,
    @SerialName("signups_id") val signupsId: Long,
    // The station reference is the station *sortorder*, matching the web client.
    @SerialName("stations_id") val stationsId: Int,
    val points: Int = 0,
    val hits: Int = 0,
    @SerialName("figure_hits") val figureHits: Int = 0,
    // Mixed numbers and "X"; mapped to picker values via ShotMapping.
    @SerialName("station_figure_hits") val stationFigureHits: List<JsonElement>? = null,
    val finals: Int = 0,
    val distinguish: Int = 0,
    @SerialName("scored_at") val scoredAt: String? = null,
    @SerialName("scored_by") val scoredBy: Long? = null,
)

@Serializable
data class StatusResponse(val lanes: List<LaneStatusDto> = emptyList())

@Serializable
data class LaneStatusDto(
    val lane: Int,
    @SerialName("signup_id") val signupId: Long? = null,
    val result: ResultDto? = null,
    val claim: ClaimDto? = null,
)

@Serializable
data class ClaimDto(
    val name: String? = null,
    val since: String? = null,
)

@Serializable
data class ClaimResponse(
    val mine: LenientBoolean = false,
    val holder: ClaimDto? = null,
)

@Serializable
data class SummaryResponse(
    @SerialName("all_decided") val allDecided: LenientBoolean = false,
    @SerialName("results_pending") val resultsPending: LenientBoolean = false,
    @SerialName("is_championship") val isChampionship: LenientBoolean = false,
    val standings: List<StandingDto> = emptyList(),
)

@Serializable
data class StandingDto(
    val placement: Int? = null,
    val name: String? = null,
    val points: Int = 0,
    val hits: Int = 0,
    val series: List<SeriesPointsDto> = emptyList(),
    val lane: Int? = null,
    val club: String? = null,
    val tied: LenientBoolean = false,
)

@Serializable
data class SeriesPointsDto(
    val points: Int = 0,
    val hits: Int = 0,
)

/** Error body for 4xx responses, e.g. duplicate_result and no_active_patrol. */
@Serializable
data class ApiErrorDto(
    val error: String? = null,
    val message: String? = null,
    @SerialName("scored_by_name") val scoredByName: String? = null,
    @SerialName("scored_at") val scoredAt: String? = null,
)
