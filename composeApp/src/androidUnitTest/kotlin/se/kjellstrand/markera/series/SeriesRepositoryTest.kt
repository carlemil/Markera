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
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/** Signed in as [userId] without talking to the network. */
fun testSession(api: SeriesApi, userId: Long = 1): BackendSessionRepository =
    BackendSessionRepository(api, InMemoryBackendTokenStore(BackendAuth("tok", userId, "dev")))
        .also { runBlocking { it.restore() } }

// androidUnitTest (not commonTest): the paged-refresh assertions lean on the
// JDBC SQLite driver; the code under test is commonMain.
class SeriesRepositoryTest {

    private val recorded = mutableListOf<HttpRequestData>()
    private val images = FakeImageCache()

    private fun repo(
        userId: Long = 1,
        db: MarkeraDb = testSeriesDb(),
        respondWith: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SeriesRepository {
        val engine = MockEngine { request ->
            recorded += request
            respondWith(request)
        }
        val api = SeriesApi(createWebshooterHttpClient(engine), "http://host:8090") { "tok" }
        return SeriesRepository(api, db, images, testSession(api, userId))
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        body,
        status,
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun dto(id: Long, stamp: String, updatedAt: String?, caliber: String = "22lr") =
        """{"id":$id,"timestamp":"$stamp","caliber":"$caliber","holes":[],""" +
            (updatedAt?.let { """"updatedAt":"$it",""" } ?: "") +
            """"hasImage":false}"""

    private val lastSince: String? get() = recorded.last().url.parameters["since"]

    @Test
    fun aFullLoadStoresEveryRowAndTheNewestStamp() = runBlocking {
        val repo = repo {
            json(
                """[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")},""" +
                    """${dto(2, "2026-09-02T10:00:00Z", "2026-09-02 11:00:00.000")}]""",
            )
        }

        assertNull(repo.refresh())

        // Newest timestamp first, and no `since` on the first ever load.
        assertEquals(listOf(2L, 1L), repo.series.value.map { it.id })
        assertNull(lastSince)

        // The stored stamp is what the next refresh asks from.
        repo.refresh()
        assertEquals("2026-09-02 11:00:00.000", lastSince)
    }

    @Test
    fun aDeltaUpsertsChangedRowsDropsTombstonesAndKeepsTheRest() = runBlocking {
        val db = testSeriesDb()
        repo(db = db) {
            json(
                """[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")},""" +
                    """${dto(2, "2026-09-02T10:00:00Z", "2026-09-02 10:00:00.000")},""" +
                    """${dto(3, "2026-09-03T10:00:00Z", "2026-09-03 10:00:00.000")}]""",
            )
        }.refresh()
        images.write(3, byteArrayOf(1))

        // Series 2 changed caliber, series 3 was deleted, series 1 untouched.
        val delta = repo(db = db) {
            json(
                """[${dto(2, "2026-09-02T10:00:00Z", "2026-09-04 09:00:00.000", "9mm")},""" +
                    """{"id":3,"timestamp":"","caliber":"","holes":[],""" +
                    """"updatedAt":"2026-09-04 10:00:00.000","deleted":true}]""",
            )
        }

        assertNull(delta.refresh())

        assertEquals(listOf(2L, 1L), delta.series.value.map { it.id })
        assertEquals("9mm", delta.series.value.first { it.id == 2L }.caliber)
        // The tombstone took the cached image with it.
        assertNull(images.files[3])

        // The stamp advanced to the newest one seen — the tombstone's.
        delta.refresh()
        assertEquals("2026-09-04 10:00:00.000", lastSince)
    }

    @Test
    fun aFailedRefreshReportsAndKeepsTheCachedRows() = runBlocking {
        val db = testSeriesDb()
        repo(db = db) {
            json("""[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")}]""")
        }.refresh()

        val offline = repo(db = db) { json("""{"error":"boom"}""", HttpStatusCode.ServiceUnavailable) }

        assertNotNull(offline.refresh())
        assertEquals(listOf(1L), offline.series.value.map { it.id })
    }

    @Test
    fun anotherUserSigningInWipesTheCache() = runBlocking {
        val db = testSeriesDb()
        repo(db = db, userId = 1) {
            json("""[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")}]""")
        }.refresh()

        val other = repo(db = db, userId = 2) { json("[]") }
        assertNull(other.refresh())

        assertTrue(other.series.value.isEmpty())
        // A full load, not a delta: the other user's stamp is not ours.
        assertNull(lastSince)
    }

    @Test
    fun deleteRemovesTheRowOnceTheServerAccepted() = runBlocking {
        val db = testSeriesDb()
        val repo = repo(db = db) { request ->
            if (request.method == HttpMethod.Delete) {
                json("", HttpStatusCode.NoContent)
            } else {
                json(
                    """[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")},""" +
                        """${dto(2, "2026-09-02T10:00:00Z", "2026-09-02 10:00:00.000")}]""",
                )
            }
        }
        repo.refresh()
        images.write(1, byteArrayOf(7))

        repo.delete(1)

        assertEquals(listOf(2L), repo.series.value.map { it.id })
        assertNull(images.files[1])
    }

    @Test
    fun clearEmptiesTheRowsTheImagesAndTheStamp() = runBlocking {
        val repo = repo {
            json("""[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")}]""")
        }
        repo.refresh()
        images.write(1, byteArrayOf(7))

        repo.clear()

        assertTrue(repo.series.value.isEmpty())
        assertTrue(images.files.isEmpty())
        // No stamp left, so the next refresh is a full load again.
        repo.refresh()
        assertNull(lastSince)
    }

    @Test
    fun imageIsDownloadedOnceAndServedFromTheCacheAfter() = runBlocking {
        val jpeg = byteArrayOf(-1, -40, 4, 2)
        val repo = repo {
            respond(
                jpeg,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Image.JPEG.toString()),
            )
        }

        val first = repo.image(9)

        assertEquals(1, recorded.size)
        assertTrue(recorded.single().url.toString().endsWith("/series/9/image"))
        assertNotNull(first)
        assertEquals(jpeg.toList(), first.toList())
        // The second read is the cached file: no second request.
        assertEquals(jpeg.toList(), repo.image(9)?.toList())
        assertEquals(1, recorded.size)
    }

    @Test
    fun saveCachesThePostedSeriesWithoutAnotherFetch() = runBlocking {
        val repo = repo { json("""{"id":7}""", HttpStatusCode.Created) }

        val id = repo.save(SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList()))

        assertEquals(7L, id)
        assertEquals(listOf(7L), repo.series.value.map { it.id })
        assertEquals(1, recorded.size)
    }
}
