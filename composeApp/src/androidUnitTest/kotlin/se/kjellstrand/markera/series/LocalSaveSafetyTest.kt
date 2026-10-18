package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Regression checks around durable I/O and the auth-publication boundaries. */
class LocalSaveSafetyTest {
    private fun request() = SeriesRequest("2026-10-18T10:00:00Z", "22lr", listOf(
        HoleDto(10.0, 20.0, 9, false, 30.0, detectedX = 10.0, detectedY = 20.0),
        HoleDto(40.0, 50.0, 0, false, 120.0, detectedX = 40.0, detectedY = 50.0, deleted = true),
    ), clientId = "safety-scan")

    private class Fixture(val local: ImageCache = FakeImageCache()) {
        val db = testSeriesDb()
        val cache = FakeImageCache()
        var offline = false
        var failTokenWrite = false
        var imageStatus = HttpStatusCode.OK
        var imagePosts = 0
        var seriesPosts = 0
        var deleted = false
        var atPublication: () -> Unit = {}
        var saved: BackendAuth? = BackendAuth("old", 1, "dev")
        val store = object : BackendTokenStore {
            override suspend fun read() = saved
            override suspend fun write(auth: BackendAuth) {
                atPublication()
                if (failTokenWrite) throw IOException("store unavailable")
                saved = auth
            }
            override suspend fun clear() { saved = null }
        }
        lateinit var session: BackendSessionRepository
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            if (offline) throw IOException("offline")
            if (req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/image")) {
                imagePosts++
                return@MockEngine respond("", imageStatus)
            }
            if (req.method == HttpMethod.Post && req.url.encodedPath == "/series") seriesPosts++
            if (req.method == HttpMethod.Delete && req.url.encodedPath == "/series/100") deleted = true
            val body = when {
                req.url.encodedPath == "/auth/dev" -> """{"token":"new","userId":2}"""
                req.method == HttpMethod.Post && req.url.encodedPath == "/series" -> """{"id":100}"""
                req.method == HttpMethod.Get && req.url.encodedPath == "/series" && seriesPosts > 0 && !deleted ->
                    seriesJson.encodeToString(listOf(SeriesDto(100, "2026-10-18T10:00:00Z", "22lr", emptyList(), hasImage = false)))
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "http://safety.test") { session.currentToken }
        val repo: SeriesRepository
        init {
            session = BackendSessionRepository(api, store)
            runBlocking { session.restore() }
            repo = SeriesRepository(api, db, cache, session, local)
            session.claimGate = repo
        }
    }

    @Test fun heldAccountSwitchAndDismissCannotExposePreviousSyncedCache() = runBlocking {
        val f = Fixture()
        f.repo.save(request())
        f.cache.write(100, byteArrayOf(7))
        val id = f.repo.keepLocal(request().copy(clientId = "unclaimed"), null)
        f.session.signInDev("other")
        assertNull(f.session.auth.value)
        assertNull(f.saved)
        assertEquals(2L, f.session.pendingAuth.value?.userId)
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().isEmpty())
        assertNull(f.cache.read(100))
        assertEquals(listOf(-id), f.repo.series.value.map { it.id })
        f.session.discardPending()
        assertNull(f.session.auth.value)
        assertEquals(listOf(-id), f.repo.series.value.map { it.id })
    }

    @Test fun offlineAccountSwitchWipesCacheEvenWithoutServerUpdatedAt() = runBlocking {
        val f = Fixture()
        f.repo.save(request())
        assertEquals("1", f.db.seriesQueries.lastSync().executeAsOne().user_id)
        f.session.signInDev("other") // no local offer; publish user 2 immediately
        f.offline = true
        assertNotNull(f.repo.refresh())
        assertTrue(f.repo.series.value.isEmpty())
    }

    @Test fun failedPhotoWriteRollsBackLocalRowAndLeavesLegacyOutboxForAdoptionRetry() = runBlocking {
        val bad = object : ImageCache by FakeImageCache() {
            override fun write(id: Long, bytes: ByteArray) { throw IOException("disk full") }
        }
        val f = Fixture(bad)
        assertFailsWith<IOException> { f.repo.keepLocal(request(), EncodedImage(byteArrayOf(1), 10, 10)) }
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        f.db.seriesQueries.enqueue("1", seriesJson.encodeToString(request()), null, byteArrayOf(1), 10, 10)
        assertFailsWith<IOException> { f.repo.adoptLegacyOutbox() }
        assertEquals(1, f.db.seriesQueries.allOutbox().executeAsList().size)
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
    }

    @Test fun ownershipIsDurableBeforeTokenPublicationAndSurvivesFailedTokenWrite() = runBlocking {
        val f = Fixture()
        val id = f.repo.keepLocal(request(), null)
        f.session.signInDev("other")
        f.atPublication = { assertEquals("2", f.db.seriesQueries.localById(id).executeAsOne().owner_user_id) }
        f.repo.claimLocal(2)
        f.failTokenWrite = true
        assertFailsWith<IOException> { f.session.confirmPending() }
        assertNull(f.session.auth.value)
        assertNull(f.saved)
        assertEquals("2", f.db.seriesQueries.localById(id).executeAsOne().owner_user_id)
        f.failTokenWrite = false
        f.session.confirmPending()
        assertEquals(2L, f.session.auth.value?.userId)
    }

    @Test fun localEditsPreserveClientIdAndPreviouslyRemovedDetectionAndDeletePhoto() = runBlocking {
        val local = FakeImageCache()
        val f = Fixture(local)
        val id = f.repo.keepLocal(request(), EncodedImage(byteArrayOf(1, 2), 100, 100))
        f.repo.update(-id, request().copy(holes = listOf(request().holes.first().copy(ring = 8)), clientId = null))
        val stored = seriesJson.decodeFromString<SeriesRequest>(f.db.seriesQueries.localById(id).executeAsOne().request)
        assertEquals("safety-scan", stored.clientId)
        assertEquals(8, stored.holes.first().ring)
        assertEquals(request().holes.last(), stored.holes.last())
        assertEquals(1, f.repo.series.value.single().holes.size)
        f.repo.delete(-id)
        assertNull(local.read(id))
        assertTrue(f.repo.series.value.isEmpty())
    }

    @Test fun refusedPhotoIsVisibleAndDetailRetriesPositiveIdWithoutPostingMetadataAgain() = runBlocking {
        val f = Fixture()
        f.imageStatus = HttpStatusCode.PayloadTooLarge
        val id = f.repo.keepLocal(request(), EncodedImage(byteArrayOf(1), 10, 10), owner = 1)
        assertFailsWith<SeriesApiException> { f.repo.send(id) }
        assertEquals(100L, f.repo.series.value.single().id)
        assertEquals(LocalMark.REFUSED, f.repo.series.value.single().local)
        f.repo.refresh() // The server metadata truthfully says the refused photo has not arrived.
        assertTrue(f.repo.series.value.single().hasImage, "Durable local photo stays visible/exportable after refresh")
        assertContentEquals(byteArrayOf(1), f.repo.image(100))
        val priorImagePosts = f.imagePosts
        f.imageStatus = HttpStatusCode.OK
        f.repo.retrySeries(100)
        assertEquals(1, f.seriesPosts)
        assertEquals(priorImagePosts + 1, f.imagePosts)
        assertNull(f.repo.series.value.single().local)
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
    }

    @Test fun deletingAcknowledgedSeriesCancelsPendingPhotoAndRemovesDurableFile() = runBlocking {
        val local = FakeImageCache()
        val f = Fixture(local)
        f.imageStatus = HttpStatusCode.Forbidden
        val id = f.repo.keepLocal(request(), EncodedImage(byteArrayOf(1), 10, 10), owner = 1)
        assertFailsWith<SeriesApiException> { f.repo.send(id) }
        f.repo.delete(100)
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        assertNull(local.read(id))
        assertTrue(f.repo.series.value.isEmpty())
        f.repo.refresh()
        assertEquals(1, f.imagePosts, "Deleted series must never retry their pending photo")
    }
}
