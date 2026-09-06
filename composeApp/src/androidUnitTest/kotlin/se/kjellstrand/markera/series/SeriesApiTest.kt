package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient
import se.kjellstrand.markera.webshooter.api.webshooterJson

// Lives in androidUnitTest (not commonTest) only because runBlocking is not
// part of the common coroutines API; the code under test is commonMain.
class SeriesApiTest {

    private val recorded = mutableListOf<HttpRequestData>()

    private fun api(
        baseUrl: String = "http://host:8080",
        token: String? = null,
        respondWith: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SeriesApi {
        val engine = MockEngine { request ->
            recorded += request
            respondWith(request)
        }
        return SeriesApi(createWebshooterHttpClient(engine), baseUrl) { token }
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        body,
        status,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private val sentBody: String get() = (recorded.last().body as TextContent).text

    @Test
    fun authDevPostsSubjectAndParsesSession() = runBlocking {
        val api = api { json("""{"token":"t1","userId":42}""") }

        assertEquals(BackendAuthResponse("t1", 42), api.authDev("x"))

        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://host:8080/auth/dev", request.url.toString())
        assertEquals("""{"subject":"x"}""", sentBody)
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun postSeriesSendsBearerTokenAndReturnsId() = runBlocking {
        val api = api(token = "tok") { json("""{"id":7}""", HttpStatusCode.Created) }
        val request = seriesRequest(
            scores = listOf(
                HitScore(1f, 2f, 0f, 10.0, 10, true),
                HitScore(3f, 4f, 0f, 60.0, 8, false),
            ),
            caliber = Caliber.MM9,
            timestamp = "2026-09-06T10:00:00Z",
        )

        assertEquals(7L, api.postSeries(request))

        assertEquals("Bearer tok", recorded.single().headers[HttpHeaders.Authorization])
        assertEquals("http://host:8080/series", recorded.single().url.toString())
        assertEquals(request, webshooterJson.decodeFromString<SeriesRequest>(sentBody))
        assertTrue(""""caliber":"9mm"""" in sentBody, sentBody)
        // innerTen has no default, so false values must still be on the wire.
        assertEquals(2, Regex("innerTen").findAll(sentBody).count(), sentBody)
    }

    @Test
    fun unauthorizedCarriesServerErrorMessage() {
        val api = api(token = "stale") {
            json("""{"error":"invalid session token"}""", HttpStatusCode.Unauthorized)
        }

        val e = assertFailsWith<SeriesApiException> { runBlocking { api.listSeries() } }
        assertTrue(e.isUnauthorized)
        assertEquals("invalid session token", e.message)
    }

    @Test
    fun baseUrlWorksWithAndWithoutTrailingSlash() = runBlocking {
        listOf("http://host:8080", "http://host:8080/").forEach { base ->
            api(baseUrl = base) { json("[]") }.listSeries()
            assertEquals("http://host:8080/series", recorded.last().url.toString())
        }
    }

    @Test
    fun listSeriesParsesArray() = runBlocking {
        val api = api(token = "tok") {
            json(
                """[{"id":1,"timestamp":"2026-09-06T10:00:00Z","caliber":"22lr",
                   "holes":[{"x":1.5,"y":2.5,"ring":10,"innerTen":true,"distanceMm":8.0}]},
                   {"id":2,"timestamp":"2026-09-06T11:00:00Z","caliber":"-","holes":[]}]""",
            )
        }

        val series = api.listSeries()

        assertEquals(listOf(1L, 2L), series.map { it.id })
        assertEquals("22lr", series[0].caliber)
        assertEquals(HoleDto(1.5, 2.5, 10, true, 8.0), series[0].holes.single())
        assertTrue(series[1].holes.isEmpty())
    }
}
