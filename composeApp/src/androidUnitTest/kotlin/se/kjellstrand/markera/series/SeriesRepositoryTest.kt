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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import se.kjellstrand.markera.series.db.MarkeraDb

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
        respondWith: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SeriesRepository {
        val engine = MockEngine { request ->
            recorded += request
            respondWith(request)
        }
        val api = SeriesApi(createSeriesHttpClient(engine), "http://host:8090") { "tok" }
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
    fun theOutboxDropsARefusedRowSendsTheRestAndStopsWhenOffline() = runBlocking {
        val db = testSeriesDb()
        var postStatus = HttpStatusCode.BadRequest
        val repo = repo(db = db) { request ->
            when {
                request.method == HttpMethod.Get -> json("[]")
                request.url.encodedPath.endsWith("/image") -> respond("", HttpStatusCode.NoContent)
                else -> json("""{"id":5}""", postStatus).also { postStatus = HttpStatusCode.Created }
            }
        }
        val req = SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList())
        repo.enqueue(req, null)
        repo.enqueue(req.copy(caliber = "22lr"), EncodedImage(byteArrayOf(1), 10, 10))

        assertNull(repo.refresh())

        // The 400 is gone for good, the second went up with its photo, and nothing is left.
        assertEquals(listOf("/series", "/series", "/series/5/image"), recorded.filter { it.method == HttpMethod.Post }.map { it.url.encodedPath })
        assertEquals(listOf<Byte>(1), images.files[5]?.toList())
        assertTrue(db.seriesQueries.outboxFor("1").executeAsList().isEmpty())
    }

    @Test
    fun anOfflineRefreshKeepsTheOutbox() = runBlocking {
        val db = testSeriesDb()
        val repo = repo(db = db) { json("""{"error":"down"}""", HttpStatusCode.ServiceUnavailable) }
        repo.enqueue(SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList()), null)

        assertNotNull(repo.refresh())

        assertEquals(1, db.seriesQueries.outboxFor("1").executeAsList().size)
    }

    @Test
    fun overTheDailyQuotaTheOutboxWaitsButTheRefreshGoesThrough() = runBlocking {
        val db = testSeriesDb()
        val repo = repo(db = db) { request ->
            if (request.method == HttpMethod.Get) json("[]") else json("""{"error":"quota"}""", HttpStatusCode.TooManyRequests)
        }
        repo.enqueue(SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList()), null)

        assertNull(repo.refresh())

        assertEquals(1, db.seriesQueries.outboxFor("1").executeAsList().size)
    }

    @Test
    fun aCachedThumbnailIsServedWithoutTouchingTheFullImage() = runBlocking {
        val repo = repo { error("no request expected") }
        images.writeThumb(9, byteArrayOf(1, 2))

        assertEquals(listOf<Byte>(1, 2), repo.thumbnail(9, 384)?.toList())
        assertTrue(recorded.isEmpty())
        assertNull(images.files[9])
    }

    @Test
    fun deleteAndClearTakeTheThumbnailsToo() = runBlocking {
        val repo = repo { json("", HttpStatusCode.NoContent) }
        images.writeThumb(1, byteArrayOf(7))
        images.writeThumb(2, byteArrayOf(8))

        repo.delete(1)
        assertNull(images.thumbs[1])

        repo.clear()
        assertTrue(images.thumbs.isEmpty())
    }

    @Test
    fun saveCachesThePostedSeriesWithoutAnotherFetch() = runBlocking {
        val repo = repo { json("""{"id":7}""", HttpStatusCode.Created) }

        val id = repo.save(SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList()))

        assertEquals(7L, id)
        assertEquals(listOf(7L), repo.series.value.map { it.id })
        assertEquals(1, recorded.size)
    }

    @Test
    fun updateSendsDeletedHolesButKeepsThemOutOfTheCache() = runBlocking {
        val repo = repo { respond("", HttpStatusCode.NoContent) }
        val live = HoleDto(1.0, 2.0, 9, false, 31.2)
        val gone = HoleDto(5.0, 6.0, 7, false, 80.0, detectedRing = 7, detectedInnerTen = false, deleted = true)

        repo.update(3, SeriesRequest("2026-09-05T10:00:00Z", "9mm", listOf(live, gone)))

        assertTrue(""""deleted":true""" in (recorded.single().body as TextContent).text)
        assertEquals(listOf(live), repo.series.value.single().holes)
    }

    private suspend fun awaitRequests(n: Int) = withTimeout(5_000) { while (recorded.size < n) delay(10) }

    @Test
    fun anUndecodableCachedRowIsDroppedNotThrown() = runBlocking {
        val db = testSeriesDb()
        repo(db = db) {
            json("""[${dto(1, "2026-09-01T10:00:00Z", "2026-09-01 10:00:00.000")}]""")
        }.refresh()
        db.seriesQueries.upsert(5, "2026-09-02T10:00:00Z", "garbage")

        val repo = repo(db = db) { json("[]") }

        assertNull(repo.refresh())
        assertEquals(listOf(1L), repo.series.value.map { it.id })
        assertEquals(listOf(1L), db.seriesQueries.selectAll().executeAsList().map { it.id })
        // The stamp went with it, so the next refresh is a full load that fetches a clean copy.
        repo.refresh()
        assertNull(lastSince)
    }

    @Test
    fun aSaveDuringAFullLoadSurvivesIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repo = repo { request ->
            if (request.method == HttpMethod.Post) {
                json("""{"id":7}""", HttpStatusCode.Created)
            } else {
                gate.await()
                json("[]")
            }
        }
        val refresh = async { repo.refresh() }
        awaitRequests(1)
        val save = async { repo.save(SeriesRequest("2026-09-05T10:00:00Z", "9mm", emptyList())) }
        delay(100)
        gate.complete(Unit)

        assertNull(refresh.await())
        assertEquals(7L, save.await())
        assertEquals(listOf(7L), repo.series.value.map { it.id })
    }

    @Test
    fun aRefreshAskedForDuringAnotherJoinsItAndSharesItsOutcome() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repo = repo {
            gate.await()
            json("""{"error":"down"}""", HttpStatusCode.ServiceUnavailable)
        }
        val first = async { repo.refresh() }
        awaitRequests(1)

        val second = async { repo.refresh() }
        delay(100)
        // Still waiting on the first one, not reporting a success it has not had.
        assertTrue(second.isActive)
        gate.complete(Unit)

        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals(1, recorded.size)
    }
}
