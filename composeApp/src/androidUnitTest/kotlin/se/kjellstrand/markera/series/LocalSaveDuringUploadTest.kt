package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** A local save must not wait for an upload in flight; sends stay one at a time. */
class LocalSaveDuringUploadTest {
    private fun request(clientId: String) = SeriesRequest("2026-10-18T10:00:00Z", "22lr",
        listOf(HoleDto(10.0, 20.0, 9, false, 30.0)), clientId = clientId)

    private class Server {
        val gate = CompletableDeferred<Unit>()
        val posted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val photos = java.util.concurrent.CopyOnWriteArrayList<String>()
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.method == HttpMethod.Post && req.url.encodedPath == "/series" -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    posted += req.url.toString()
                    gate.await()
                    inFlight.decrementAndGet()
                    respond("""{"id":${100 + posted.size}}""", HttpStatusCode.OK, json)
                }
                req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/image") -> {
                    photos += req.url.encodedPath
                    respond("", HttpStatusCode.OK)
                }
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }), "http://host") { "tok" }
    }

    private suspend fun repo(server: Server, localImages: ImageCache = FakeImageCache()): SeriesRepository {
        val session = BackendSessionRepository(server.api, InMemoryBackendTokenStore(BackendAuth("tok", 1L, "dev")))
            .also { it.restore() }
        return SeriesRepository(server.api, testSeriesDb(), FakeImageCache(), session, localImages)
    }

    @Test fun keepLocalCompletesWhileAPostIsHeldAndSendsStaySerial() = runBlocking {
        val server = Server()
        val repo = repo(server)

        val first = repo.keepLocal(request("a"), null, 1L)
        val firstSend = async(Dispatchers.Default) { repo.send(first) }
        withTimeout(5000) { while (server.posted.isEmpty()) delay(10) }

        // The upload of "a" is stuck: further local saves still go through at once.
        val second = withTimeout(5000) { repo.keepLocal(request("b"), null, 1L) }
        val third = withTimeout(5000) { repo.keepLocal(request("c"), null, 1L) }
        assertEquals(setOf(-first, -second, -third), repo.series.value.map { it.id }.toSet())

        val secondSend = async(Dispatchers.Default) { repo.send(second) }
        delay(200)
        assertEquals(1, server.posted.size, "the next send waits its turn")
        server.gate.complete(Unit)
        withTimeout(5000) { firstSend.await(); secondSend.await() }
        assertEquals(1, server.maxInFlight.get())
        assertEquals(2, server.posted.size)
    }

    /** A photo attached to the row (same clientId) while its POST was out is uploaded, not dropped. */
    @Test fun aPhotoStoredDuringThePostIsUploadedAfterIt() = runBlocking {
        val server = Server()
        val localImages = FakeImageCache()
        val repo = repo(server, localImages)

        val id = repo.keepLocal(request("a"), null, 1L)
        val send = async(Dispatchers.Default) { repo.send(id) }
        withTimeout(5000) { while (server.posted.isEmpty()) delay(10) }

        val again = withTimeout(5000) { repo.keepLocal(request("a"), EncodedImage(byteArrayOf(1, 2, 3), 4, 4), 1L) }
        assertEquals(id, again)
        server.gate.complete(Unit)
        val serverId = withTimeout(5000) { send.await() }

        assertEquals(listOf("/series/$serverId/image"), server.photos.toList())
        assertTrue(repo.series.value.single { it.id == serverId }.hasImage)
        assertNull(localImages.read(id), "the local copy goes once uploaded")
    }
}
