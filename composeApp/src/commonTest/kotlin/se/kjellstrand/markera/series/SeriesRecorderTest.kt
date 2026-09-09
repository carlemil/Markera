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
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

// The recorder saves on its own scope, so the tests wait on the status flow
// instead of assuming a dispatcher.
class SeriesRecorderTest {

    private val recorded = mutableListOf<HttpRequestData>()
    private val written = mutableListOf<Caliber>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val scores = listOf(
        HitScore(1f, 2f, 0f, 10.0, 10, true),
        HitScore(3f, 4f, 0f, 60.0, 8, false),
    )

    /** Most tests are about *when* a series is saved, not about the picker edits. */
    private val noPicks = emptyList<Int>()

    private val image: PlatformImage = testImage()

    private val jpeg = byteArrayOf(-1, -40, 4, 2)

    // The fake image cannot report a size, so the encoder carries it — which is
    // what the real one does too (the source size, not the JPEG's).
    private val encoded = EncodedImage(jpeg, 1200, 1200)

    private fun recorder(
        signedIn: Boolean = true,
        stored: Caliber = Caliber.NONE,
        status: HttpStatusCode = HttpStatusCode.Created,
        imageStatus: HttpStatusCode = HttpStatusCode.NoContent,
        // The pre-image tests below count series requests only; their default
        // encoder fails, so nothing is uploaded unless a test asks for it.
        encodeJpeg: suspend (PlatformImage) -> EncodedImage = { error("no encoder") },
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
        // The recorder saves through the cache now; a real in-memory one, so
        // the request counts below are still exactly what goes over the wire.
        val repository = SeriesRepository(api, testSeriesDb(), FakeImageCache(), session)
        return runBlocking {
            session.restore()
            SeriesRecorder(
                repository = repository,
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

        recorder.onSeriesDetected(scores, image, null)

        assertEquals(SaveStatus.SignedOut, recorder.status.value)
        assertFalse(recorder.caliberDialogOpen.value)

        recorder.commit(noPicks)

        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aCaliberChosenFromTheChipAfterAnEarlierSaveDoesNotPostTheNewScan() {
        val recorder = recorder(stored = Caliber.LR22)
        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)
        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())

        recorder.onSeriesDetected(scores, image, null)
        recorder.selectCaliber(Caliber.MM9)

        assertEquals(SaveStatus.Pending, recorder.status.value)
        assertEquals(1, recorded.size)

        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        assertEquals(2, recorded.size)
        assertTrue(""""caliber":"9mm"""" in sentBody, sentBody)
    }

    @Test
    fun detectionOnlyMakesTheSeriesPendingUntilCommit() {
        val recorder = recorder(stored = Caliber.LR22)

        recorder.onSeriesDetected(scores, image, null)

        assertEquals(SaveStatus.Pending, recorder.status.value)
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(recorded.isEmpty())

        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertEquals("http://host:8090/series", recorded.single().url.toString())
        assertTrue(""""caliber":"22lr"""" in sentBody, sentBody)
    }

    @Test
    fun aSecondDetectionReplacesTheFirstPendingSeries() {
        val recorder = recorder(stored = Caliber.MM9)

        recorder.onSeriesDetected(scores, image, null)
        recorder.onSeriesDetected(listOf(HitScore(9f, 9f, 0f, 90.0, 6, false)), image, null)
        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        assertEquals(1, recorded.size)
        assertEquals(1, Regex("distanceMm").findAll(sentBody).count(), sentBody)
        assertTrue(""""ring":6""" in sentBody, sentBody)
    }

    @Test
    fun dismissingTheDialogKeepsTheSeriesPendingForTheNextCommit() {
        val recorder = recorder(stored = Caliber.NONE)
        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        recorder.dismissCaliberDialog()

        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(recorded.isEmpty())

        recorder.commit(noPicks)

        assertTrue(recorder.caliberDialogOpen.value)
        assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)
    }

    @Test
    fun noCaliberAsksThenSavesWithTheChosenOne() {
        val recorder = recorder(stored = Caliber.NONE)

        recorder.onSeriesDetected(scores, image, null)

        assertEquals(SaveStatus.Pending, recorder.status.value)
        assertFalse(recorder.caliberDialogOpen.value)

        recorder.commit(noPicks)

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
        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        recorder.selectCaliber(Caliber.NONE)

        assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)
        assertTrue(recorded.isEmpty())

        // Still pending: choosing a real caliber now saves the same series.
        recorder.selectCaliber(Caliber.C38)
        assertEquals(SaveStatus.Saved(Caliber.C38), recorder.awaitDone())
        assertTrue(""""caliber":"38"""" in sentBody, sentBody)
    }

    @Test
    fun storedCaliberSavesOnCommitWithNoDialog() {
        val recorder = recorder(stored = Caliber.LR22)

        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertFalse(recorder.caliberDialogOpen.value)
        assertTrue(written.isEmpty())
        assertTrue(""""caliber":"22lr"""" in sentBody, sentBody)
    }

    @Test
    fun serverErrorReportsFailed() {
        val recorder = recorder(stored = Caliber.MM9, status = HttpStatusCode.InternalServerError)

        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        assertIs<SaveStatus.Failed>(recorder.awaitDone())
    }

    @Test
    fun theScannedFrameIsUploadedUnderTheReturnedId() {
        val recorder = recorder(stored = Caliber.MM9, encodeJpeg = { encoded })

        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        awaitRequests(2)
        val upload = recorded.last()
        assertEquals("http://host:8090/series/1/image?width=1200&height=1200", upload.url.toString())
        assertEquals(ContentType.Image.JPEG, upload.body.contentType)
        assertContentEquals(jpeg, (upload.body as OutgoingContent.ByteArrayContent).bytes())
    }

    @Test
    fun aFailedImageUploadLeavesTheSeriesSaved() {
        val recorder = recorder(
            stored = Caliber.MM9,
            imageStatus = HttpStatusCode.InternalServerError,
            encodeJpeg = { encoded },
        )

        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(noPicks)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        awaitRequests(2)
        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.status.value)
    }

    @Test
    fun commitSavesThePickerValuesAlongsideWhatWasDetected() {
        val recorder = recorder(stored = Caliber.LR22)

        recorder.onSeriesDetected(scores, image, null)
        // Hit 1 corrected 10X -> 9, hit 2 rejected, a sixth-ring hit typed in.
        recorder.commit(listOf(9, 0, 6, 0, 0))

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertTrue(""""ring":9,"innerTen":false,"distanceMm":10.0,"detectedRing":10""" in sentBody, sentBody)
        assertTrue(""""ring":0,"innerTen":false,"distanceMm":60.0,"detectedRing":8""" in sentBody, sentBody)
        assertTrue(""""ring":6,"innerTen":false}""" in sentBody, sentBody)
        assertEquals(3, Regex(""""ring"""").findAll(sentBody).count(), sentBody)
    }

    @Test
    fun theCaliberDialogSavesTheSameEditedSeries() {
        val recorder = recorder(stored = Caliber.NONE)

        recorder.onSeriesDetected(scores, image, null)
        recorder.commit(listOf(9, 0))

        assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)

        recorder.selectCaliber(Caliber.MM9)

        assertEquals(SaveStatus.Saved(Caliber.MM9), recorder.awaitDone())
        assertTrue(""""ring":9,"innerTen":false,"distanceMm":10.0,"detectedRing":10""" in sentBody, sentBody)
        assertTrue(""""ring":0,"innerTen":false,"distanceMm":60.0,"detectedRing":8""" in sentBody, sentBody)
    }

    @Test
    fun theScanGeometryIsPostedWithTheSeries() {
        val recorder = recorder(stored = Caliber.LR22)

        recorder.onSeriesDetected(scores, image, GeometryDto(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 0.5))
        recorder.commit(listOf(9, 0))

        assertEquals(SaveStatus.Saved(Caliber.LR22), recorder.awaitDone())
        assertTrue(
            """"geometry":{"centreX":1.0,"centreY":2.0,"ringCx":3.0,"ringCy":4.0,""" +
                """"ringSemiMajor":5.0,"ringSemiMinor":6.0,"ringRotationRad":0.5}""" in sentBody,
            sentBody,
        )
    }

    @Test
    fun clearResetsToIdleAndDropsThePendingSeries() {
        val recorder = recorder(stored = Caliber.NONE)
        recorder.onSeriesDetected(scores, image, null)

        recorder.clear()

        assertEquals(SaveStatus.Idle, recorder.status.value)

        // Nothing left to save: neither a commit nor a caliber posts anything.
        recorder.commit(noPicks)
        assertEquals(SaveStatus.Idle, recorder.status.value)
        recorder.selectCaliber(Caliber.MM9)
        runBlocking { recorder.caliber.first { it == Caliber.MM9 } }
        assertEquals(SaveStatus.Idle, recorder.status.value)
        assertTrue(recorded.isEmpty())
    }
}
