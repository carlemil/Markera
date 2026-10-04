package se.kjellstrand.markera.ui.competition

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import se.kjellstrand.markera.webshooter.LaneEntry
import se.kjellstrand.markera.webshooter.MarkingContext
import se.kjellstrand.markera.webshooter.ScoringRepository
import se.kjellstrand.markera.webshooter.api.WebshooterApi
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient
import se.kjellstrand.markera.webshooter.api.dto.SignupDto
import se.kjellstrand.markera.webshooter.api.dto.StationDto
import se.kjellstrand.markera.webshooter.auth.InMemoryTokenStore
import se.kjellstrand.markera.webshooter.auth.SessionRepository

class MarkingWizardViewModelTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    // Two lanes, one station; lane 1 = signup 101, lane 2 = signup 102.
    private val resolve =
        """{"type":"group","competition_id":244,"lanes":[1,2],"lane_start":1,"lane_end":2,"patrol_sortorder":1}"""
    private val registration = """{"signups":{
        "1":{"id":101,"users_id":11,"user":{"fullname":"A"}},
        "2":{"id":102,"users_id":12,"user":{"fullname":"B"}}},
        "stations":[{"id":5001,"sortorder":1,"shots":5}]}"""

    @Test
    fun aSaveForALaneTheUserLeftNeitherLandsOnNorYanksTheNewLane() = runBlocking<Unit> {
        val putArrived = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val released = MutableStateFlow<List<String?>>(emptyList())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("scoring/resolve/g") -> respond(resolve, HttpStatusCode.OK, json)
                path.endsWith("results/registration") -> respond(registration, HttpStatusCode.OK, json)
                path.endsWith("scoring/claim") && request.method == HttpMethod.Delete -> {
                    released.update { it + request.url.parameters["signup_id"] }
                    respond("", HttpStatusCode.NoContent)
                }
                path.endsWith("scoring/claim") -> respond("""{"mine":true}""", HttpStatusCode.OK, json)
                path.endsWith("scoring/status") -> respond("""{"lanes":[]}""", HttpStatusCode.OK, json)
                path.endsWith("results/mobile-v2") -> {
                    putArrived.complete(Unit)
                    gate.await()
                    respond("{}", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        lateinit var session: SessionRepository
        val api = WebshooterApi(
            createWebshooterHttpClient(engine), "https://ws.test/", "secret", { session.currentToken },
        )
        session = SessionRepository(api, InMemoryTokenStore())
        val vm = MarkingWizardViewModel(
            ScoringRepository(api, session), session, 244, "g",
            nowIso = { "2026-10-04T12:00:00.000Z" }, clientNonce = { "n" }, scope = scope,
        )
        withTimeout(5_000) { vm.uiState.first { !it.loading && it.step is LaneStep.Entering } }
        assertEquals(0, vm.uiState.value.laneIndex)

        vm.onScanComplete(listOf(10, 10, 10, 10, 10))
        vm.save()
        withTimeout(5_000) { putArrived.await() }
        // The user taps lane 2 while lane 1's save is still in flight.
        vm.goToLane(1)
        // Leaving mid-save gives lane 1's claim back (before the save answers).
        withTimeout(5_000) { released.first { "101" in it } }
        gate.complete(Unit)
        delay(300)

        val st = vm.uiState.value
        assertEquals(1, st.laneIndex)
        assertIs<LaneStep.Entering>(st.step)
        vm.dispose()
    }

    private fun state(stationIndex: Int, laneIndex: Int = 0) = WizardUiState(
        loading = false,
        stationIndex = stationIndex,
        laneIndex = laneIndex,
        context = MarkingContext(
            competitionId = 244,
            patrolSortorder = 1,
            laneStart = 1,
            laneEnd = 1,
            // Sortorders with a gap (a removed station): the position is what counts.
            stations = listOf(3, 7).map { StationDto(id = it.toLong(), sortorder = it) },
            lanes = listOf(LaneEntry(1, SignupDto(id = 1))),
        ),
    )

    @Test
    fun seriesPositionIsTheOneBasedIndexNotTheSortorder() {
        assertEquals(1 to 2, state(0).seriesPosition)
        assertEquals(2 to 2, state(1).seriesPosition)
        assertNull(WizardUiState().seriesPosition)
    }

    @Test
    fun isAtMatchesBothStationAndLane() {
        assertTrue(state(1, 0).isAt(1, 0))
        assertFalse(state(1, 0).isAt(0, 0))
        assertFalse(state(1, 0).isAt(1, 1))
    }
}
