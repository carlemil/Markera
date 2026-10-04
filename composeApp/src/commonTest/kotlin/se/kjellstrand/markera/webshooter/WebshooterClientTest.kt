package se.kjellstrand.markera.webshooter

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.webshooter.api.WebshooterApi
import se.kjellstrand.markera.webshooter.api.WebshooterApiException
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient
import se.kjellstrand.markera.webshooter.api.dto.ApiErrorDto
import se.kjellstrand.markera.webshooter.api.dto.StationDto
import se.kjellstrand.markera.webshooter.api.webshooterJson
import se.kjellstrand.markera.webshooter.auth.AuthSession
import se.kjellstrand.markera.webshooter.auth.InMemoryTokenStore
import se.kjellstrand.markera.webshooter.auth.SessionRepository

class WebshooterClientTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session(
        refreshStatus: HttpStatusCode,
        store: InMemoryTokenStore = InMemoryTokenStore(AuthSession("old", "r", 7)),
    ): SessionRepository {
        val engine = MockEngine { respond("""{"message":"no"}""", refreshStatus, json) }
        lateinit var repo: SessionRepository
        val api = WebshooterApi(
            createWebshooterHttpClient(engine), "https://ws.test/", "secret", { repo.currentToken },
        )
        repo = SessionRepository(api, store)
        return repo
    }

    @Test
    fun refreshRefusedWith401SignsOut() = runBlocking<Unit> {
        val store = InMemoryTokenStore(AuthSession("old", "r", 7))
        val repo = session(HttpStatusCode.Unauthorized, store)
        repo.restore()
        assertFalse(repo.refresh())
        assertNull(repo.session.value)
        assertNull(store.read())
    }

    @Test
    fun refreshRefusedWith400SignsOut() = runBlocking<Unit> {
        val repo = session(HttpStatusCode.BadRequest)
        repo.restore()
        assertFalse(repo.refresh())
        assertNull(repo.session.value)
    }

    @Test
    fun refreshServerErrorKeepsTheSessionAndSurfacesTheError() = runBlocking<Unit> {
        val store = InMemoryTokenStore(AuthSession("old", "r", 7))
        val repo = session(HttpStatusCode.ServiceUnavailable, store)
        repo.restore()
        val e = assertFailsWith<WebshooterApiException> { repo.refresh() }
        assertEquals(503, e.status)
        assertEquals("old", repo.session.value?.accessToken)
        assertNotNull(store.read())
    }

    @Test
    fun refreshNetworkFailureKeepsTheSession() = runBlocking<Unit> {
        val engine = MockEngine { throw IllegalStateException("offline") }
        lateinit var repo: SessionRepository
        val api = WebshooterApi(
            createWebshooterHttpClient(engine), "https://ws.test/", "secret", { repo.currentToken },
        )
        repo = SessionRepository(api, InMemoryTokenStore(AuthSession("old", "r", 7)))
        repo.restore()
        assertFailsWith<IllegalStateException> { repo.refresh() }
        assertEquals("old", repo.session.value?.accessToken)
    }

    @Test
    fun allCompetitionsLoadsEveryPage() = runBlocking<Unit> {
        val pages = mutableListOf<String>()
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]!!
            pages += page
            val id = page.toInt()
            respond(
                """{"competitions":{"current_page":$id,"last_page":3,"total":3,
                   "data":[{"id":$id,"name":"C$id"}]}}""",
                HttpStatusCode.OK,
                json,
            )
        }
        lateinit var session: SessionRepository
        val api = WebshooterApi(
            createWebshooterHttpClient(engine), "https://ws.test/", "secret", { session.currentToken },
        )
        session = SessionRepository(api, InMemoryTokenStore())
        val all = ScoringRepository(api, session).allCompetitions()
        assertEquals(listOf(1, 2, 3), all.map { it.id })
        assertEquals(listOf("1", "2", "3"), pages)
    }

    @Test
    fun notActiveIsA403OrNoActivePatrol() {
        assertTrue(WebshooterApiException(403, null, "").isNotActive)
        assertTrue(WebshooterApiException(422, ApiErrorDto(error = "no_active_patrol"), "").isNotActive)
        assertFalse(WebshooterApiException(500, null, "").isNotActive)
        assertFalse(WebshooterApiException(401, ApiErrorDto(error = "other"), "").isNotActive)
    }

    @Test
    fun stationRemovedAcceptsIntBooleans() {
        val removed = webshooterJson.decodeFromString<StationDto>("""{"id":1,"sortorder":1,"removed":1}""")
        assertEquals(true, removed.removed)
        val kept = webshooterJson.decodeFromString<StationDto>("""{"id":1,"sortorder":1,"removed":0}""")
        assertEquals(false, kept.removed)
        val absent = webshooterJson.decodeFromString<StationDto>("""{"id":1,"sortorder":1,"removed":null}""")
        assertNull(absent.removed)
    }
}
