package se.kjellstrand.markera.webshooter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN
import se.kjellstrand.markera.webshooter.api.dto.ClaimDto
import se.kjellstrand.markera.webshooter.api.dto.RegistrationResponse
import se.kjellstrand.markera.webshooter.api.dto.ResultDto
import se.kjellstrand.markera.webshooter.api.dto.SignupDto
import se.kjellstrand.markera.webshooter.api.dto.SignupUserDto
import se.kjellstrand.markera.webshooter.api.dto.StationDto

class MarkingLogicTest {

    private fun signup(id: Long, usersId: Long, results: List<ResultDto> = emptyList()) =
        SignupDto(id = id, usersId = usersId, user = SignupUserDto("Shooter $id"), results = results)

    private fun result(signupId: Long, station: Int, scored: Boolean = true) = ResultDto(
        signupsId = signupId,
        stationsId = station,
        points = 40,
        scoredAt = if (scored) "2026-08-14T05:49:48.000000Z" else null,
    )

    private fun context(vararg lanes: Pair<Int, SignupDto>, stationCount: Int = 3) = MarkingContext(
        competitionId = 244,
        patrolSortorder = 1,
        laneStart = lanes.minOf { it.first },
        laneEnd = lanes.maxOf { it.first },
        stations = (1..stationCount).map { StationDto(id = 2000L + it, sortorder = it, shots = 5) },
        lanes = lanes.map { LaneEntry(it.first, it.second) },
    )

    @Test
    fun buildContextFiltersLaneRangeAndSortsStations() {
        val registration = RegistrationResponse(
            signups = mapOf(
                "21" to signup(1, 100),
                "31" to signup(2, 200),
                "32" to signup(3, 300),
                "33" to null,
            ),
            stations = listOf(
                StationDto(id = 2, sortorder = 2),
                StationDto(id = 1, sortorder = 1),
                StationDto(id = 3, sortorder = 3, removed = true),
            ),
        )
        val ctx = MarkingLogic.buildContext(244, 1, 31, 35, registration)
        assertEquals(listOf(31, 32), ctx.lanes.map { it.lane })
        assertEquals(listOf(1, 2), ctx.stations.map { it.sortorder })
    }

    @Test
    fun firstOpenPositionSkipsScoredLanes() {
        val ctx = context(
            31 to signup(1, 100, listOf(result(1, 1), result(1, 2))),
            32 to signup(2, 200, listOf(result(2, 1))),
            33 to signup(3, 300, listOf(result(3, 1))),
        )
        // Station 1 fully scored, station 2 open from lane index 1 (lane 32).
        assertEquals(1 to 1, MarkingLogic.firstOpenPosition(ctx))
    }

    @Test
    fun firstOpenPositionNullWhenEverythingScored() {
        val ctx = context(
            31 to signup(1, 100, listOf(result(1, 1), result(1, 2), result(1, 3))),
            stationCount = 3,
        )
        assertNull(MarkingLogic.firstOpenPosition(ctx))
    }

    @Test
    fun unscoredResultEntryDoesNotCountAsScored() {
        val ctx = context(31 to signup(1, 100, listOf(result(1, 1, scored = false))))
        assertEquals(0 to 0, MarkingLogic.firstOpenPosition(ctx))
    }

    @Test
    fun nextOpenLaneSkipsScoredClaimedAndSelf() {
        val ctx = context(
            31 to signup(1, 100),
            32 to signup(2, 200, listOf(result(2, 1))),   // scored
            33 to signup(3, 300),                          // claimed by someone else
            34 to signup(4, 2019),                         // the current user's own lane
            35 to signup(5, 500),
        )
        val claims = mapOf(33 to ClaimDto(name = "Someone Else"))
        val next = MarkingLogic.nextOpenLaneIndex(
            context = ctx, stationSortorder = 1, fromIndex = 0,
            claims = claims, userId = 2019L, isAdmin = false,
        )
        assertEquals(4, next) // lane 35
    }

    @Test
    fun nextOpenLaneNullAtEnd() {
        val ctx = context(31 to signup(1, 100), 32 to signup(2, 200, listOf(result(2, 1))))
        assertNull(
            MarkingLogic.nextOpenLaneIndex(ctx, 1, fromIndex = 1, claims = emptyMap(), userId = null, isAdmin = false)
        )
    }

    @Test
    fun adminMayMarkOwnLane() {
        val entry = LaneEntry(31, signup(1, 2019))
        assertTrue(MarkingLogic.isSelf(entry, userId = 2019L, isAdmin = false))
        assertFalse(MarkingLogic.isSelf(entry, userId = 2019L, isAdmin = true))
        assertFalse(MarkingLogic.isSelf(entry, userId = null, isAdmin = false))
    }

    @Test
    fun allRegisteredRequiresEveryLaneScored() {
        val done = context(
            31 to signup(1, 100, listOf(result(1, 1))),
            32 to signup(2, 200, listOf(result(2, 1))),
        )
        assertTrue(MarkingLogic.allRegistered(done, 1))
        assertFalse(MarkingLogic.allRegistered(done, 2))
    }

    @Test
    fun mergedResultsReplacesSameStationAndKeepsOthers() {
        val existing = listOf(result(1, 1), result(1, 2))
        val s = signup(1, 100, existing)
        val updated = MarkingLogic.updatedResult(
            s, stationSortorder = 2,
            picks = listOf(SCORE_PICKER_INNER_TEN, 10, 9, 8, 7),
            nowIso = "2026-08-15T12:00:00.000Z",
        )
        val merged = MarkingLogic.mergedResults(s, updated)
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.stationsId == 2 })
        val entry = merged.first { it.stationsId == 2 }
        assertEquals(44, entry.points)
        assertEquals(1, entry.hits)
        assertEquals(listOf("X", "10", "9", "8", "7"), entry.stationFigureHits?.map { (it as JsonPrimitive).content })
    }

    @Test
    fun updatedResultAppendsWhenStationHasNoEntry() {
        val s = signup(1, 100, listOf(result(1, 1)))
        val updated = MarkingLogic.updatedResult(s, 3, listOf(5, 5, 5, 5, 5), "2026-08-15T12:00:00.000Z")
        assertNull(updated.id)
        val merged = MarkingLogic.mergedResults(s, updated)
        assertEquals(2, merged.size)
        assertEquals(25, merged.first { it.stationsId == 3 }.points)
    }

    @Test
    fun updatedResultKeepsExistingIdWhenOverwriting() {
        val withId = result(1, 1).copy(id = 116994L)
        val s = signup(1, 100, listOf(withId))
        val updated = MarkingLogic.updatedResult(s, 1, listOf(9, 9, 9, 9, 9), "2026-08-15T12:00:00.000Z")
        assertEquals(116994L, updated.id)
    }

    @Test
    fun auditPayloadMatchesWebClient() {
        val audit = MarkingLogic.buildAudit(
            competitionId = 244, signupId = 19248L, stationSortorder = 2, lane = 31,
            picks = listOf(SCORE_PICKER_INNER_TEN, 10, 0, 8, 7),
            clientNonce = "mv2-1-abc",
        )
        assertEquals(244, audit["competitions_id"])
        assertEquals(19248L, audit["signup_id"])
        assertEquals(2, audit["station_sortorder"])
        assertEquals(31, audit["lane"])
        assertEquals(listOf("X", "10", "0", "8", "7"), audit["shots"])
        assertEquals(35, audit["points"])
        assertEquals(1, audit["x_count"])
        assertEquals("mv2-1-abc", audit["client_nonce"])
    }
}
