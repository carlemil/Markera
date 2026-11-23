package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.*

/** #22, #27 and #28: what an account deletion, a server-side delete and a sign-out leave behind. */
class SignOutCleanupTest {
    private val jpeg = byteArrayOf(-1, -40, 7, -1, -39)
    private val stamp = "2026-10-01 10:00:00.000"

    private class Fixture {
        val db = testSeriesDb()
        val cache = FakeImageCache()
        val localImages = FakeImageCache()
        var imageCode = 204
        var listCode = 200
        var listBody = "[]"
        /** Set, the next GET of the list (or of a photo) waits for it; [entered] says it is waiting. */
        var listGate: CompletableDeferred<Unit>? = null
        var photoGate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Post && path == "/series" -> respond("""{"id":41}""", HttpStatusCode.OK, json)
                req.method == HttpMethod.Post && path.endsWith("/image") -> respond("", HttpStatusCode.fromValue(imageCode))
                req.method == HttpMethod.Get && path.endsWith("/image") -> {
                    photoGate?.let { entered.complete(Unit); it.await() }
                    respond(byteArrayOf(-1, -40, 45, -1, -39), HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "image/jpeg"))
                }
                req.method == HttpMethod.Get && path == "/series" -> {
                    listGate?.let { entered.complete(Unit); it.await() }
                    respond(listBody, HttpStatusCode.fromValue(listCode), json)
                }
                else -> respond("", HttpStatusCode.NoContent) // DELETE /account, DELETE /auth/session
            }
        }), "http://host") { "tok" }
        val session = testSession(api, 17L)
        val repo = SeriesRepository(api, db, cache, session, localImages)
    }

    private fun request() = SeriesRequest("2026-10-13T10:20:30Z", "22lr",
        listOf(HoleDto(10.0, 20.0, 9, false, 30.0)), clientId = "pending-photo")

    /** A series the server took (id 41) whose photo is still on the phone. */
    private suspend fun Fixture.photoPending(code: Int): Long {
        imageCode = code
        val id = repo.keepLocal(request(), EncodedImage(jpeg, 4, 4), owner = 17L)
        runCatching { repo.send(id) }
        assertEquals(41L, db.seriesQueries.localById(id).executeAsOne().server_id)
        assertContentEquals(jpeg, localImages.read(id))
        return id
    }

    private fun Fixture.assertGone(localId: Long) {
        assertNull(db.seriesQueries.localById(localId).executeAsOneOrNull(), "photo-pending row is orphaned")
        assertNull(localImages.read(localId), "its JPEG is orphaned")
    }

    // #22
    @Test fun accountDeletionRemovesTheOwnersPhotoPendingRowsAndJpegs() = runBlocking<Unit> {
        val f = Fixture()
        val pending = f.photoPending(503)
        val unsent = f.repo.keepLocal(request().copy(clientId = "unsent"), EncodedImage(jpeg, 4, 4), owner = 17L)

        f.cache.write(41, byteArrayOf(4, 1))

        f.api.deleteAccount(); f.session.signOut(); f.repo.clearDeletedAccount(17L)

        f.assertGone(pending)
        // And the synced cache goes, as with clear().
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().isEmpty())
        assertNull(f.db.seriesQueries.lastSync().executeAsOneOrNull())
        assertNull(f.cache.read(41))
        // Never sent: still the user's own work, offered to the next account.
        assertNotNull(f.db.seriesQueries.localById(unsent).executeAsOneOrNull())
        assertContentEquals(jpeg, f.localImages.read(unsent))
    }

    @Test fun aPlainSignOutKeepsPhotoPendingRowsForTheirOwner() = runBlocking<Unit> {
        val f = Fixture()
        val pending = f.photoPending(503)
        f.session.signOut(); f.repo.clear()
        assertNotNull(f.db.seriesQueries.localById(pending).executeAsOneOrNull())
        assertContentEquals(jpeg, f.localImages.read(pending))
    }

    @Test fun aDeltaTombstoneRemovesThePhotoPendingRow() = runBlocking<Unit> {
        val f = Fixture()
        val pending = f.photoPending(503)
        f.db.seriesQueries.setLastSync("17", stamp)
        // 429 ends the drain without failing the refresh, so the delta below is applied.
        f.imageCode = 429
        f.listBody = """[{"id":41,"timestamp":"","caliber":"","holes":[],"deleted":true,"updatedAt":"2026-10-02 10:00:00.000"}]"""
        assertNull(f.repo.refresh())
        f.assertGone(pending)
        assertTrue(f.repo.series.value.none { it.id == 41L })
    }

    @Test fun aPhoto404RemovesThePhotoPendingRow() = runBlocking<Unit> {
        val f = Fixture()
        val pending = f.photoPending(503)
        f.db.seriesQueries.setLastSync("17", stamp)
        f.imageCode = 404
        f.repo.refresh()
        f.assertGone(pending)
        assertTrue(f.repo.series.value.none { it.id == 41L }, "the deleted series is not shown either")
        assertNull(f.cache.read(41))
    }

    // #27
    @Test fun signingOutDuringARefreshDoesNotSaySessionExpired() = runBlocking<Unit> {
        val f = Fixture()
        f.db.seriesQueries.setLastSync("17", stamp)
        f.listGate = CompletableDeferred()
        f.listCode = 401
        val expired = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1500) { f.repo.sessionExpired.first() }
        }
        val refresh = async(Dispatchers.Default) { f.repo.refresh() }
        withTimeout(5000) { f.entered.await() }

        // Settings' Sign out: the session and the cache side by side.
        f.session.signOut()
        val clear = async(Dispatchers.Default) { f.repo.clear() }
        f.listGate!!.complete(Unit)

        val outcome = withTimeout(5000) { refresh.await() }
        withTimeout(5000) { clear.await() }
        assertNull(expired.await(), "a deliberate sign-out is not an expired session")
        // History and Statistics would show its 401 as "session expired" too.
        assertNull(outcome, "a refresh overtaken by a sign-out has nothing to report")
    }

    @Test fun aGenuine401StillExpiresTheSession() = runBlocking<Unit> {
        val f = Fixture()
        f.db.seriesQueries.setLastSync("17", stamp)
        f.listCode = 401
        val expired = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(5000) { f.repo.sessionExpired.first() }
        }
        assertNotNull(f.repo.refresh())
        assertNotNull(expired.await())
        assertNull(f.session.auth.value)
    }

    // #28
    @Test fun aPhotoDownloadFinishingAfterSignOutIsNotCached() = runBlocking<Unit> {
        val f = Fixture()
        f.photoGate = CompletableDeferred()
        val image = async(Dispatchers.Default) { f.repo.image(45) }
        withTimeout(5000) { f.entered.await() }

        f.session.signOut(); f.repo.clear()
        f.photoGate!!.complete(Unit)
        withTimeout(5000) { image.await() }

        assertNull(f.cache.read(45), "a late download is written back into the signed-out cache")
    }

    @Test fun aPhotoDownloadWhileSignedInIsCached() = runBlocking<Unit> {
        val f = Fixture()
        assertNotNull(f.repo.image(45))
        assertNotNull(f.cache.read(45))
    }
}
