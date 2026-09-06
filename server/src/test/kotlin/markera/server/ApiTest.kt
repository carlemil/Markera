package markera.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private fun apiTest(devAuth: Boolean = true, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            val dbFile = File.createTempFile("markera-test", ".db").also { it.delete(); it.deleteOnExit() }
            application { markeraModule(Config(0, dbFile.path, null, null, devAuth), Db(dbFile.path)) }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            block(client)
        }

    private suspend fun HttpClient.devAuth(subject: String): AuthResponse =
        post("/auth/dev") { contentType(ContentType.Application.Json); setBody(DevAuthRequest(subject)) }.body()

    private fun series(timestamp: String = "2026-09-06T12:34:56Z", caliber: String = "9mm") = SeriesRequest(
        timestamp = timestamp,
        caliber = caliber,
        holes = listOf(
            Hole(1.0, 2.0, 9, false, 31.2),
            Hole(-3.5, 4.25, 10, true, 8.0),
        ),
    )

    @Test
    fun health() = apiTest { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }

    @Test
    fun devAuthIssuesTokenAndReusesTheUser() = apiTest { client ->
        val first = client.devAuth("tester")
        val second = client.devAuth("tester")
        val other = client.devAuth("someone-else")
        assertTrue(first.token.length == 64, "expected a 32-byte hex token, got '${first.token}'")
        assertTrue(first.token != second.token, "each login gets its own session token")
        assertEquals(first.userId, second.userId)
        assertTrue(other.userId != first.userId)
    }

    @Test
    fun seriesRoundTrips() = apiTest { client ->
        val me = client.devAuth("me")
        val older = series(timestamp = "2026-09-05T10:00:00Z", caliber = "22lr")
        val newer = series(timestamp = "2026-09-06T12:34:56Z")
        for (request in listOf(older, newer)) {
            val response = client.post("/series") {
                bearerAuth(me.token); contentType(ContentType.Application.Json); setBody(request)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.body<IdResponse>().id > 0)
        }

        val stored: List<Series> = client.get("/series") { bearerAuth(me.token) }.body()
        assertEquals(listOf(newer.timestamp, older.timestamp), stored.map { it.timestamp })
        assertEquals(listOf("9mm", "22lr"), stored.map { it.caliber })
        assertEquals(newer.holes, stored[0].holes)

        val stranger = client.devAuth("stranger")
        assertEquals(emptyList(), client.get("/series") { bearerAuth(stranger.token) }.body<List<Series>>())
    }

    @Test
    fun invalidSeriesAreRejected() = apiTest { client ->
        val token = client.devAuth("me").token
        val bad = listOf(
            series(caliber = "50bmg"),
            series().copy(holes = emptyList()),
            series(timestamp = "yesterday"),
        )
        for (request in bad) {
            val response = client.post("/series") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $request")
            assertTrue(response.body<ErrorResponse>().error.isNotEmpty())
        }
    }

    @Test
    fun seriesNeedsAValidBearerToken() = apiTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series") { bearerAuth("nope") }.status)
        val post = client.post("/series") {
            bearerAuth("nope"); contentType(ContentType.Application.Json); setBody(series())
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    @Test
    fun devAuthIsAbsentUnlessEnabled() = apiTest(devAuth = false) { client ->
        val response = client.post("/auth/dev") {
            contentType(ContentType.Application.Json); setBody(DevAuthRequest("tester"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun unconfiguredProvidersReport503() = apiTest { client ->
        val response = client.post("/auth/google") {
            contentType(ContentType.Application.Json); setBody(IdTokenRequest("whatever"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
