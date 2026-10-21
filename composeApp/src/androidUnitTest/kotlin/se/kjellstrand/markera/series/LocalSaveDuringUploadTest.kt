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

    @Test fun keepLocalCompletesWhileAPostIsHeldAndSendsStaySerial() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val posted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath == "/series") {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                posted += req.url.toString()
                gate.await()
                inFlight.decrementAndGet()
                respond("""{"id":${100 + posted.size}}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } else respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "http://host") { "tok" }
        val session = BackendSessionRepository(api, InMemoryBackendTokenStore(BackendAuth("tok", 1L, "dev")))
            .also { it.restore() }
        val repo = SeriesRepository(api, testSeriesDb(), FakeImageCache(), session, FakeImageCache())

        val first = repo.keepLocal(request("a"), null, 1L)
        val firstSend = async(Dispatchers.Default) { repo.send(first) }
        withTimeout(5000) { while (posted.isEmpty()) delay(10) }

        // The upload of "a" is stuck: further local saves still go through at once.
        val second = withTimeout(5000) { repo.keepLocal(request("b"), null, 1L) }
        val third = withTimeout(5000) { repo.keepLocal(request("c"), null, 1L) }
        assertEquals(setOf(-first, -second, -third), repo.series.value.map { it.id }.toSet())

        val secondSend = async(Dispatchers.Default) { repo.send(second) }
        delay(200)
        assertEquals(1, posted.size, "the next send waits its turn")
        gate.complete(Unit)
        firstSend.await(); secondSend.await()
        assertEquals(1, maxInFlight.get())
        assertEquals(2, posted.size)
    }
}
