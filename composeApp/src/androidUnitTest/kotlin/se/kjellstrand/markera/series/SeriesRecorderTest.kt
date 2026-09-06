package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import sun.misc.Unsafe
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.PlatformImage
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

    // PlatformImage is android.graphics.Bitmap, which a JVM unit test cannot
    // construct (every method of the mockable android.jar throws, and there is
    // no Robolectric here). An uninitialised instance is enough: the recorder
    // only ever hands it to encodeJpeg, and the encoders below ignore it.
    private val image: PlatformImage = run {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        (field.get(null) as Unsafe).allocateInstance(PlatformImage::class.java) as PlatformImage
    }

    private val jpeg = byteArrayOf(-1, -40, 4, 2)

    private fun recorder(
        signedIn: Boolean = true,
        stored: Caliber = Caliber.NONE,
        status: HttpStatusCode = HttpStatusCode.Created,
        imageStatus: HttpStatusCode = HttpStatusCode.NoContent,
        // The pre-image tests below count series requests only; their default
        // encoder fails, so nothing is uploaded unless a test asks for it.
        encodeJpeg: suspend (PlatformImage) -> ByteArray = { error("no encoder") },
    ): SeriesRecorder {
        val engine = MockEngine { request ->
            recorded += request
            if (request.url.encodedPath.endsWith("/image")) {
                respond("", imageStatus)
            } else {
                respond(
                    if (status.value < 300) """{"id":1}""" else """{"error":"boom"}""",
                    status,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
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
                encodeJpeg = encodeJpeg,
                scope = scope,
            ).also { it.caliber.first { c -> c == stored } }
        }
    }

    /** The recorder saves off-thread; wait for a terminal status. */
    private fun SeriesRecorder.awaitDone(): SaveStatus = runBlocking {
        status.first { it is SaveStatus.Saved || it is SaveStatus.Failed }
    }

    /** The image upload runs after the status turns Saved, so wait for it too. */
    private fun awaitRequests(n: Int) = runBlocking {
        withTimeout(5_000) { while (recorded.size < n) delay(10) }
    }

    private val sentBody: String get() = (recorded.last().body as TextContent).text

    @Test
    fun signedOutReportsSignedOutAndPostsNothing() {
        val recorder = recorder(signedIn = false)

        recorder.onSeriesDetected(scores, image)

        assertEquals(SaveStatus.SignedOut, recorder.status.value)
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun noCaliberAsksThenSavesWithTheChosenOne() {
        val recorder = recorder(stored = Caliber.NONE)

        recorder.onSeriesDetected(scores, image)

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
        recorder.onSeriesDetected(scores, image)

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

        recorder.onSeriesDetected(scores, image)

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(written.isEmpty())
        assertTrue(""""caliber":"22lr"""" in sentBody, sentBody)
    }

    @Test
    fun serverErrorReportsFailed() {
        val recorder = recorder(stored = Caliber.MM9, status = HttpStatusCode.InternalServerError)

        recorder.onSeriesDetected(scores, image)

        assertIs<SaveStatus.Failed>(recorder.awaitDone())
    }

    @Test
    fun theScannedFrameIsUploadedUnderTheReturnedId() {
        val recorder = recorder(stored = Caliber.MM9, encodeJpeg = { jpeg })

        recorder.onSeriesDetected(scores, image)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        awaitRequests(2)
        val upload = recorded.last()
        assertEquals("http://host:8090/series/1/image", upload.url.toString())
        assertEquals(ContentType.Image.JPEG, upload.body.contentType)
        assertContentEquals(jpeg, (upload.body as OutgoingContent.ByteArrayContent).bytes())
    }

    @Test
    fun aFailedImageUploadLeavesTheSeriesSaved() {
        val recorder = recorder(
            stored = Caliber.MM9,
            imageStatus = HttpStatusCode.InternalServerError,
            encodeJpeg = { jpeg },
        )

        recorder.onSeriesDetected(scores, image)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        awaitRequests(2)
        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.status.value)
    }

    @Test
    fun clearResetsToIdleAndDropsThePendingSeries() {
        val recorder = recorder(stored = Caliber.NONE)
        recorder.onSeriesDetected(scores, image)

        recorder.clear()

        assertEquals(SaveStatus.Idle, recorder.status.value)

        // Nothing left to save, so choosing a caliber only stores the preference.
        recorder.selectCaliber(Caliber.MM9)
        runBlocking { recorder.caliber.first { it == Caliber.MM9 } }
        assertEquals(SaveStatus.Idle, recorder.status.value)
        assertTrue(recorded.isEmpty())
    }
}
