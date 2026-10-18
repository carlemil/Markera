package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.diag.ErrorSink
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.vision.HitScore
import kotlin.test.*

/** Real red contracts for #654, using the compile-only #650 repository seams. */
class LocalSaveRefusedTest {
    private val jpeg = byteArrayOf(-1, -40, 1, 3, -1, -39)
    private val stamp = "2026-10-13T10:20:30Z"
    private fun request(id: String = "retained-client") = SeriesRequest(
        stamp, "22lr", listOf(HoleDto(null, null, 9, false, null)), tag = "training", clientId = id,
    )
    private val encoded get() = EncodedImage(jpeg, 4000, 3000)

    private inner class Fixture(
        val db: MarkeraDb = testSeriesDb(),
        val localImages: ImageCache = FakeImageCache(),
    ) {
        var code = 400
        var failure: Exception? = null
        var imageCode = 204
        val requests = mutableListOf<HttpRequestData>()
        val posts = mutableListOf<SeriesRequest>()
        val cache = FakeImageCache()
        val server = linkedMapOf<String, SeriesDto>()
        var loseCreatedResponse = false
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            requests += req
            val image = req.url.encodedPath.endsWith("/image")
            val post = req.method == HttpMethod.Post && !image
            if (post) {
                posts += seriesJson.decodeFromString<SeriesRequest>((req.body as TextContent).text)
                if (loseCreatedResponse) {
                    val body = posts.last()
                    server.getOrPut(body.clientId!!) { SeriesDto(41L, body.timestamp, body.caliber, body.holes, tag = body.tag) }
                    throw SocketTimeoutException("created but response lost")
                }
                failure?.let { throw it }
                if (code < 300) {
                    val body = posts.last()
                    server.getOrPut(body.clientId!!) { SeriesDto(41L + server.size, body.timestamp, body.caliber, body.holes, tag = body.tag) }
                }
            }
            respond(
                when { image -> ""; post && code < 300 -> """{"id":${server.getValue(posts.last().clientId!!).id}}"""; post -> "Failed to convert request body"; else -> seriesJson.encodeToString(server.values.toList()) },
                HttpStatusCode.fromValue(when { image -> imageCode; post -> code; else -> 200 }),
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }), "http://host:8090") { "tok" }
        val session = testSession(api, 17L)
        val repository = SeriesRepository(api, db, cache, session, localImages)
        suspend fun save(id: String = "retained-client"): Long {
            val localId = repository.keepLocal(request(id), encoded, owner = 17L)
            runCatching { repository.send(localId) }
            return localId
        }
        fun assertRetained(localId: Long, refused: Boolean) {
            val row = db.seriesQueries.localById(localId).executeAsOne()
            assertEquals("17", row.owner_user_id)
            assertEquals(request(row.client_id), seriesJson.decodeFromString<SeriesRequest>(row.request))
            assertEquals(if (refused) "refused" else "queued", row.state)
            assertContentEquals(jpeg, localImages.read(localId))
            val visible = repository.series.value.single { it.id == -localId }
            assertEquals(if (refused) LocalMark.REFUSED else LocalMark.QUEUED, visible.local)
        }
    }

    private fun failureRetains(code: Int? = null, error: Exception? = null) = runBlocking {
        val f = Fixture().also { it.code = code ?: 500; it.failure = error }
        val id = f.save()
        f.assertRetained(id, code != null && code in 400..499 && code != 401 && code != 429)
        assertEquals(code?.toLong(), f.db.seriesQueries.localById(id).executeAsOne().last_status)
        assertContentEquals(jpeg, f.repository.image(-id))
        if (code == 401) assertNull(f.session.auth.value, "401 signs out without destroying the owned row")
        else assertEquals(17L, f.session.auth.value?.userId)
    }
    @Test fun badRequest400RetainsSeriesAndPhoto() = failureRetains(400)
    @Test fun unauthorized401RetainsOwnerAndSignsOut() = failureRetains(401)
    @Test fun forbidden403RetainsSeriesAndPhoto() = failureRetains(403)
    @Test fun payload413RetainsSeriesAndPhoto() = failureRetains(413)
    @Test fun validation422RetainsSeriesAndPhoto() = failureRetains(422)
    @Test fun server500RetainsSeriesAndPhoto() = failureRetains(500)
    @Test fun gateway502RetainsSeriesAndPhoto() = failureRetains(502)
    @Test fun unavailable503RetainsSeriesAndPhoto() = failureRetains(503)
    @Test fun quota429RetainsSeriesAndPhoto() = failureRetains(429)
    @Test fun offlineRetainsSeriesAndPhoto() = failureRetains(error = IOException("offline"))
    @Test fun timeoutRetainsSeriesAndPhoto() = failureRetains(error = SocketTimeoutException("lost response"))

    @Test fun transientQueueRetriesInInsertionOrderAndPublishesServerIdentity() = runBlocking {
        for (code in listOf(500, 502, 503, 429, 0)) {
            val f = Fixture().also { it.code = code; if (code == 0) it.failure = IOException("offline"); if (code == -1) it.failure = SocketTimeoutException("timeout") }
            f.save("first"); f.save("second")
            f.posts.clear(); f.code = 200; f.failure = null
            f.repository.refresh()
            assertEquals(listOf("first", "second"), f.posts.map { it.clientId })
            assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
            assertEquals(setOf(41L, 42L), f.repository.series.value.map { it.id }.toSet())
            val synced = f.repository.series.value.single { it.id == 41L }
            assertNull(synced.local)
            assertEquals(stamp, synced.timestamp)
            assertEquals("22lr", synced.caliber)
            assertEquals("training", synced.tag)
            assertContentEquals(jpeg, f.cache.read(41))
        }
    }

    @Test fun refusedRetriesOncePerRepositoryStartAndExplicitRetryReportsOnlyOnce() = runBlocking {
        val f = Fixture()
        val reports = mutableListOf<Throwable>()
        val old = ErrorLog.sink
        ErrorLog.sink = object : ErrorSink {
            override fun breadcrumb(category: String, message: String) {}
            override fun report(where: String, error: Throwable, details: Map<String, String>) { reports += error }
        }
        try {
            val id = f.save()
            assertEquals(1, reports.size)
            assertEquals(1L, f.db.seriesQueries.localById(id).executeAsOne().reported)
            f.repository.refresh(); f.repository.refresh(); f.repository.refresh()
            assertEquals(2, f.posts.size, "initial save plus one refusal retry per start")
            f.assertRetained(id, true)
            val restarted = SeriesRepository(f.api, f.db, f.cache, f.session, f.localImages)
            restarted.refresh(); restarted.refresh()
            assertEquals(3, f.posts.size)
            runCatching { restarted.retry(id) }
            assertEquals(4, f.posts.size, "Detail retry is available without restarting")
            assertEquals(1, reports.size)
            f.code = 201
            restarted.retry(id)
            assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
            assertEquals(41L, restarted.series.value.single().id)
        } finally { ErrorLog.sink = old }
    }

    @Test fun photoFailureKeepsSyncedRowAndDurablePhotoUntilPhotoOnlyRetrySucceeds() = runBlocking {
        val f = Fixture().also { it.code = 201; it.imageCode = 503 }
        val id = f.save()
        val row = f.db.seriesQueries.localById(id).executeAsOne()
        assertEquals(41L, row.server_id)
        assertEquals(41L, f.repository.series.value.single().id)
        assertNull(f.repository.series.value.single().local)
        assertContentEquals(jpeg, f.localImages.read(id))
        assertContentEquals(jpeg, f.repository.image(41))
        f.imageCode = 204
        f.repository.refresh()
        assertEquals(1, f.posts.size, "photo retry must not POST the series again")
        assertEquals(2, f.requests.count { it.url.encodedPath.endsWith("/image") && it.method == HttpMethod.Post })
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        assertContentEquals(jpeg, f.cache.read(41))
    }

    @Test fun lostCreationResponseRetriesSamePersistedClientId() = runBlocking {
        val f = Fixture().also { it.loseCreatedResponse = true }
        val id = f.save()
        assertEquals("retained-client", f.db.seriesQueries.localById(id).executeAsOne().client_id)
        f.loseCreatedResponse = false; f.code = 200 // Server deduplicates this existing clientId.
        f.repository.refresh()
        assertEquals(listOf("retained-client", "retained-client"), f.posts.map { it.clientId })
        assertEquals(listOf(41L), f.repository.series.value.map { it.id })
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
    }

    @Test fun queuedPhotoAndRequestSurviveClosingSqliteAndCreatingNewRepository() = runBlocking {
        val dir = Files.createTempDirectory("markera-refused-")
        val url = "jdbc:sqlite:${dir.resolve("series.db")}"
        var driver: JdbcSqliteDriver? = JdbcSqliteDriver(url)
        try {
            MarkeraDb.Schema.create(driver!!)
            val first = Fixture(MarkeraDb(driver!!), LocalImageStore(Path(dir.toString())))
            first.failure = IOException("offline")
            val id = first.save()
            driver!!.close(); driver = JdbcSqliteDriver(url)
            val next = Fixture(MarkeraDb(driver!!), LocalImageStore(Path(dir.toString())))
            assertContentEquals(jpeg, next.localImages.read(id))
            assertEquals(request(), seriesJson.decodeFromString<SeriesRequest>(next.db.seriesQueries.localById(id).executeAsOne().request))
            next.code = 201
            next.repository.refresh()
            assertEquals(listOf("retained-client"), next.posts.map { it.clientId })
            assertEquals(41L, next.repository.series.value.single().id)
            assertContentEquals(jpeg, next.cache.read(41))
            assertTrue(next.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        } finally { driver?.close(); dir.toFile().deleteRecursively() }
    }

    private fun recorderStatus(code: Int) = runBlocking {
            val f = Fixture().also { it.code = code; if (code == 0) it.failure = IOException("offline"); if (code == -1) it.failure = SocketTimeoutException("timeout") }
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val recorder = SeriesRecorder(f.repository, f.session, { Caliber.DEFAULT }, {}, { "training" }, {}, { encoded }, scope)
            try {
                recorder.caliber.first { it == Caliber.DEFAULT }
                recorder.onSeriesDetected(listOf(HitScore(1f, 1f, 1f, 1.0, 9, false)), testImage(), null)
                recorder.commit(listOf(9))
                withTimeout(5000) { recorder.status.first { it !is SaveStatus.Saving && it !is SaveStatus.Pending } }
                val status = recorder.status.value
                when (code) {
                    201 -> assertIs<SaveStatus.Saved>(status)
                    -1, 0, 500, 502, 503, 429 -> assertEquals(SaveStatus.Queued, status)
                    else -> assertEquals(SaveStatus.SavedOnPhone(Caliber.DEFAULT), status)
                }
                if (code != 201) {
                    val row = f.db.seriesQueries.selectLocal().executeAsOne()
                    assertEquals("17", row.owner_user_id)
                    assertContentEquals(jpeg, f.localImages.read(row.local_id))
                    assertNotNull(f.repository.series.value.single().local)
                }
            } finally { recorder.dispose(); scope.cancel() }
        }
    @Test fun recorder400SaysSavedOnPhone() = recorderStatus(400)
    @Test fun recorder401SaysSavedOnPhone() = recorderStatus(401)
    @Test fun recorder403SaysSavedOnPhone() = recorderStatus(403)
    @Test fun recorder413SaysSavedOnPhone() = recorderStatus(413)
    @Test fun recorder422SaysSavedOnPhone() = recorderStatus(422)
    @Test fun recorder500SaysQueued() = recorderStatus(500)
    @Test fun recorder502SaysQueued() = recorderStatus(502)
    @Test fun recorder503SaysQueued() = recorderStatus(503)
    @Test fun recorder429SaysQueued() = recorderStatus(429)
    @Test fun recorderOfflineSaysQueued() = recorderStatus(0)
    @Test fun recorderTimeoutSaysQueued() = recorderStatus(-1)
    @Test fun recorder201SaysUploaded() = recorderStatus(201)
}


