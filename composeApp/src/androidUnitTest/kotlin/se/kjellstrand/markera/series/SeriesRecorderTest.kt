package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

// androidUnitTest (not commonTest) for runBlocking; the code under test is
// commonMain. The recorder saves on its own scope, so the tests wait on the
// status flow instead of assuming a dispatcher.
class SeriesRecorderTest {

    private val recorded = mutableListOf<HttpRequestData>()
    private val written = mutableListOf<Caliber>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val scores = listOf(
        HitScore(1f, 2f, 0f, 10.0, 10, true),
        HitScore(3f, 4f, 0f, 60.0, 8, false),
    )

    private fun recorder(
        signedIn: Boolean = true,
        stored: Caliber = Caliber.NONE,
        status: HttpStatusCode = HttpStatusCode.Created,
    ): SeriesRecorder {
        val engine = MockEngine { request ->
            recorded += request
            respond(
                if (status.value < 300) """{"id":1}""" else """{"error":"boom"}""",
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = SeriesApi(createWebshooterHttpClient(engine), "http://host:8090") { "tok" }
        val session = BackendSessionRepository(
            api,
            InMemoryBackendTokenStore(
                if (signedIn) BackendAuth("tok", 1, "dev") else null,
            ),
        )
        return runBlocking {
            session.restore()
            SeriesRecorder(
                api = api,
                session = session,
                readCaliber = { stored },
                writeCaliber = { written += it },
                scope = scope,
            ).also { it.caliber.first { c -> c == stored } }
        }
    }

    /** The recorder saves off-thread; wait for a terminal status. */
    private fun SeriesRecorder.awaitDone(): SaveStatus = runBlocking {
        status.first { it is SaveStatus.Saved || it is SaveStatus.Failed }
    }

    private val sentBody: String get() = (recorded.last().body as TextContent).text

    @Test
    fun signedOutReportsSignedOutAndPostsNothing() {
        val recorder = recorder(signedIn = false)

        recorder.onSeriesDetected(scores)

        assertEquals(SaveStatus.SignedOut, recorder.status.value)
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun noCaliberAsksThenSavesWithTheChosenOne() {
        val recorder = recorder(stored = Caliber.NONE)

        recorder.onSeriesDetected(scores)

        assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)
        assertTrue(recorder.caliberDialogOpen.value)
        assertTrue(recorded.isEmpty())

        recorder.selectCaliber(Caliber.MM9)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        assertFalse(recorder.caliberDialogOpen.value)
        assertEquals(Caliber.MM9, recorder.caliber.value)
        assertEquals(listOf(Caliber.MM9), written)
        assertEquals("http://host:8090/series", recorded.single().url.toString())
        assertTrue(""""caliber":"9mm"""" in sentBody, sentBody)
        assertEquals(2, Regex("distanceMm").findAll(sentBody).count(), sentBody)
    }

    @Test
    fun selectingNoneKeepsTheSeriesPending() {
        val recorder = recorder(stored = Caliber.NONE)
        recorder.onSeriesDetected(scores)

        recorder.selectCaliber(Caliber.NONE)

        assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)
        assertTrue(recorded.isEmpty())

        // Still pending: choosing a real caliber now saves the same series.
        recorder.selectCaliber(Caliber.C38)
        assertEquals(SaveStatus.Saved(Caliber.C38), recorder.awaitDone())
        assertTrue(""""caliber":"38"""" in sentBody, sentBody)
    }

    @Test
    fun storedCaliberSavesImmediatelyWithNoDialog() {
        val recorder = recorder(stored = Caliber.LR22)

        recorder.onSeriesDetected(scores)

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(written.isEmpty())
        assertTrue(""""caliber":"22lr"""" in sentBody, sentBody)
    }

    @Test
    fun serverErrorReportsFailed() {
        val recorder = recorder(stored = Caliber.MM9, status = HttpStatusCode.InternalServerError)

        recorder.onSeriesDetected(scores)

        assertIs<SaveStatus.Failed>(recorder.awaitDone())
    }

    @Test
    fun clearResetsToIdleAndDropsThePendingSeries() {
        val recorder = recorder(stored = Caliber.NONE)
        recorder.onSeriesDetected(scores)

        recorder.clear()

        assertEquals(SaveStatus.Idle, recorder.status.value)

        // Nothing left to save, so choosing a caliber only stores the preference.
        recorder.selectCaliber(Caliber.MM9)
        runBlocking { recorder.caliber.first { it == Caliber.MM9 } }
        assertEquals(SaveStatus.Idle, recorder.status.value)
        assertTrue(recorded.isEmpty())
    }
}
