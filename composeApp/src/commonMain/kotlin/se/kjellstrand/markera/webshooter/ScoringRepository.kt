package se.kjellstrand.markera.webshooter

import se.kjellstrand.markera.webshooter.api.WebshooterApi
import se.kjellstrand.markera.webshooter.api.dto.CompetitionsPage
import se.kjellstrand.markera.webshooter.api.dto.ResultDto
import se.kjellstrand.markera.webshooter.api.dto.ScoringTargetsResponse
import se.kjellstrand.markera.webshooter.api.dto.SignupDto
import se.kjellstrand.markera.webshooter.api.dto.StatusResponse
import se.kjellstrand.markera.webshooter.api.dto.SummaryResponse
import se.kjellstrand.markera.webshooter.auth.SessionRepository

/**
 * All webshooter calls the marking flow makes, wrapped in [SessionRepository.withAuth]
 * so a stale token transparently refreshes + retries once.
 */
class ScoringRepository(
    private val api: WebshooterApi,
    private val sessionRepository: SessionRepository,
) {

    suspend fun competitions(search: String = "", page: Int = 1): CompetitionsPage =
        sessionRepository.withAuth { api.competitions(search, page) }.competitions

    suspend fun scoringTargets(competitionId: Int): ScoringTargetsResponse =
        sessionRepository.withAuth { api.scoringTargets(competitionId) }

    /** Resolves a marking group's guid and loads the full registration in one go. */
    suspend fun openMarkingGroup(competitionId: Int, guid: String): MarkingContext {
        val resolved = sessionRepository.withAuth { api.resolve(guid) }
        val laneStart = resolved.laneStart ?: resolved.lanes.minOrNull() ?: 1
        val laneEnd = resolved.laneEnd ?: resolved.lanes.maxOrNull() ?: laneStart
        val patrol = resolved.patrolSortorder ?: 1
        val registration = sessionRepository.withAuth {
            api.registration(competitionId, patrol, laneStart, laneEnd)
        }
        return MarkingLogic.buildContext(competitionId, patrol, laneStart, laneEnd, registration)
    }

    suspend fun status(context: MarkingContext, stationSortorder: Int): StatusResponse =
        sessionRepository.withAuth {
            api.status(
                competitionId = context.competitionId,
                station = stationSortorder,
                patrol = context.patrolSortorder,
                laneStart = context.laneStart,
                laneEnd = context.laneEnd,
            )
        }

    /** Returns the holder when someone else has the lane, null when the claim is ours. */
    suspend fun claim(context: MarkingContext, signupId: Long, stationSortorder: Int) =
        sessionRepository.withAuth { api.claim(context.competitionId, signupId, stationSortorder) }
            .let { if (it.mine) null else it.holder }

    suspend fun releaseClaim(context: MarkingContext, signupId: Long, stationSortorder: Int) {
        try {
            sessionRepository.withAuth {
                api.releaseClaim(context.competitionId, signupId, stationSortorder)
            }
        } catch (_: Exception) {
            // Best-effort, like the web client.
        }
    }

    /**
     * Saves one lane's series and returns the updated ResultDto for the local
     * cache. Throws WebshooterApiException (isDuplicateResult for conflicts).
     */
    suspend fun save(
        context: MarkingContext,
        signup: SignupDto,
        lane: Int,
        stationSortorder: Int,
        picks: List<Int>,
        nowIso: String,
        clientNonce: String,
    ): ResultDto {
        val updated = MarkingLogic.updatedResult(signup, stationSortorder, picks, nowIso)
        val results = MarkingLogic.mergedResults(signup, updated)
        sessionRepository.withAuth {
            api.saveMobileV2(
                competitionId = context.competitionId,
                audit = MarkingLogic.buildAudit(
                    competitionId = context.competitionId,
                    signupId = signup.id,
                    stationSortorder = stationSortorder,
                    lane = lane,
                    picks = picks,
                    clientNonce = clientNonce,
                ),
                results = results.map(MarkingLogic::resultToForm),
            )
        }
        return updated
    }

    suspend fun summary(context: MarkingContext): SummaryResponse =
        sessionRepository.withAuth {
            api.summary(context.competitionId, context.patrolSortorder)
        }

    suspend fun requestPublish(context: MarkingContext) {
        try {
            sessionRepository.withAuth { api.requestPublish(context.competitionId) }
        } catch (_: Exception) {
            // Fire-and-forget, like the web client.
        }
    }
}
