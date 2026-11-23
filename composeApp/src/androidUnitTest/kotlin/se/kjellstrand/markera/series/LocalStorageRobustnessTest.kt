package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.diag.ErrorLog
import se.kjellstrand.markera.diag.ErrorSink
import kotlin.test.*

/** #30 (a missing local photo is reported once) and #31 (a corrupt legacy outbox row is skipped). */
class LocalStorageRobustnessTest {
    private fun request(id: String = "robust-client") = SeriesRequest(
        "2026-11-22T10:00:00Z", "22lr", listOf(HoleDto(null, null, 9, false, null)), clientId = id,
    )

    private class Fixture(userId: Long = 17L) {
        val db = testSeriesDb()
        val cache = FakeImageCache()
        val localImages = FakeImageCache()
        var seriesPosts = 0
        var imagePosts = 0
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            val image = req.url.encodedPath.endsWith("/image")
            val post = req.method == HttpMethod.Post
            if (post && image) imagePosts++ else if (post) seriesPosts++
            respond(
                when { image -> ""; post -> """{"id":${40 + seriesPosts}}"""; else -> "[]" },
                if (image) HttpStatusCode.NoContent else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }), "http://robust.test") { "tok" }
        val session = testSession(api, userId)
        val repository = SeriesRepository(api, db, cache, session, localImages)
    }

    private val reports = mutableListOf<Pair<String, Throwable>>()
    private fun capturingReports(block: suspend () -> Unit) {
        val old = ErrorLog.sink
        ErrorLog.sink = object : ErrorSink {
            override fun breadcrumb(category: String, message: String) {}
            override fun report(where: String, error: Throwable, details: Map<String, String>) { reports += where to error }
        }
        try { return runBlocking { block() } } finally { ErrorLog.sink = old }
    }

    /** #30: the photo file of a saved row is gone; each refresh fails on it, but Sentry hears of it once. */
    @Test fun missingLocalPhotoIsReportedOnceOverManyRefreshesAndRestarts() = capturingReports {
        val f = Fixture()
        val id = f.repository.keepLocal(request(), EncodedImage(byteArrayOf(1, 2, 3), 40, 30), owner = 17L)
        f.localImages.delete(id)

        assertNull(f.repository.refresh())
        assertNull(f.repository.refresh())
        assertNull(f.repository.refresh())

        assertEquals(1, reports.size, "reported once, not once per refresh: $reports")
        val row = f.db.seriesQueries.localById(id).executeAsOne()
        assertEquals(1L, row.reported)
        assertEquals("queued", row.state, "kept for an explicit retry")
        assertEquals(1, f.seriesPosts, "the series itself went up once; only the photo is missing")
        assertEquals(0, f.imagePosts)

        val restarted = SeriesRepository(f.api, f.db, f.cache, f.session, f.localImages)
        restarted.refresh(); restarted.refresh()
        assertEquals(1, reports.size, "the flag is durable, so a restart does not report it again")
        assertNotNull(f.db.seriesQueries.localById(id).executeAsOneOrNull())
    }

    /** #31: undecodable outbox rows are kept and reported once; the good row and every caller still work. */
    @Test fun corruptLegacyOutboxRowDoesNotBreakRefreshOrSignIn() = capturingReports {
        val f = Fixture(userId = 1L)
        val q = f.db.seriesQueries
        q.enqueue("1", "{ not json", null, null, null, null)
        q.enqueue("not-a-user-id", seriesJson.encodeToString(request("bad-owner")), null, null, null, null)
        q.enqueue("1", seriesJson.encodeToString(request("good")), null, byteArrayOf(5), 10, 10)

        assertEquals(1, f.repository.localCount(2), "the good row is offered to another account")
        assertEquals(0, f.repository.needsDecision(BackendAuth("tok", 1, "dev")) {}, "own rows need no decision")
        assertNull(f.repository.refresh())
        assertNull(f.repository.refresh())
        assertEquals(0, f.repository.localCount(2))

        assertEquals(1, f.seriesPosts, "the good row was adopted and sent")
        assertEquals(1, f.imagePosts)
        assertEquals(listOf("{ not json", seriesJson.encodeToString(request("bad-owner"))),
            q.allOutbox().executeAsList().map { it.request }, "corrupt rows stay for inspection")
        assertEquals(2, reports.size, "one report per corrupt row: $reports")
        assertTrue(reports.none { it.second.message.orEmpty().contains("not json") || it.second.message.orEmpty().contains("bad-owner") },
            "the report never carries the row's content")
    }
}
