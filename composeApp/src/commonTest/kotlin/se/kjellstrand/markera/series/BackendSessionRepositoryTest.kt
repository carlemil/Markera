package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

// commonTest: it runs on the JVM and on iOS. runBlocking is not part of the
// *common* coroutines API but exists on both of this project's targets.
class BackendSessionRepositoryTest {

    private val recorded = mutableListOf<HttpRequestData>()
    private val store = InMemoryBackendTokenStore()
    private lateinit var api: SeriesApi
    private lateinit var repo: BackendSessionRepository

    @BeforeTest
    fun setUp() {
        val engine = MockEngine { request ->
            recorded += request
            val body = if (request.url.encodedPath == "/series") {
                """{"id":1}"""
            } else {
                """{"token":"tok","userId":9}"""
            }
            respond(
                body,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        lateinit var session: BackendSessionRepository
        api = SeriesApi(
            client = createWebshooterHttpClient(engine),
            baseUrl = "http://host:8080",
            tokenProvider = { session.currentToken },
        )
        session = BackendSessionRepository(api, store)
        repo = session
    }

    private val expected = BackendAuth("tok", 9, "dev")

    @Test
    fun signInPublishesAndStoresTheSession() = runBlocking {
        assertNull(repo.auth.value)

        assertEquals(expected, repo.signInDev("subject-1"))

        assertEquals(expected, repo.auth.value)
        assertEquals(expected, store.read())
        assertEquals("http://host:8080/auth/dev", recorded.single().url.toString())
    }

    @Test
    fun restoreRepublishesAStoredSession() = runBlocking {
        store.write(BackendAuth("stored", 3, "google"))

        assertEquals(BackendAuth("stored", 3, "google"), repo.restore())
        assertEquals(BackendAuth("stored", 3, "google"), repo.auth.value)
    }

    @Test
    fun apiPicksUpTheTokenAfterSignIn() = runBlocking {
        repo.signInDev("subject-1")

        api.postSeries(seriesRequest(emptyList(), Caliber.NONE, "2026-09-06T10:00:00Z"))

        assertEquals("Bearer tok", recorded.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun signOutClearsSessionAndStore() = runBlocking {
        repo.signInDev("subject-1")

        repo.signOut()

        assertNull(repo.auth.value)
        assertNull(store.read())
    }
}
