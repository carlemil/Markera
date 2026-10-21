package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.vision.HitScore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Red specifications for #654. No feature behavior or artificial fixture TODOs. */
class LocalSignedOutSaveTest {
    private val jpeg = byteArrayOf(-1, -40, 4, 2, -1, -39)
    private val encoded get() = EncodedImage(jpeg, 4000, 4000)
    private val timestamp = "2026-10-13T10:20:30Z"
    private val geometry = GeometryDto(100.0, 110.0, 105.0, 109.0, 90.0, 80.0, 0.1)
    private val detected = HitScore(100f, 110f, 105f, 4.0, 10, true, boxW = 12f, boxH = 14f, conf = .9f)
    private val manual = HitScore(140f, 150f, 145f, 40.0, 9, false, manual = true)

    private class Fixture(
        val db: MarkeraDb = testSeriesDb(),
        val localImages: ImageCache = FakeImageCache(),
        signedIn: Boolean = false,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val images = FakeImageCache()
        val api = SeriesApi(createSeriesHttpClient(MockEngine { request ->
            requests += request
            respond(
                if (request.url.encodedPath.endsWith("/image")) "" else """{"id":41}""",
                if (request.url.encodedPath.endsWith("/image")) HttpStatusCode.NoContent else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }), "http://host:8090") { if (signedIn) "tok" else null }
        val session = BackendSessionRepository(api, InMemoryBackendTokenStore(
            if (signedIn) BackendAuth("tok", 1, "dev") else null,
        )).also { runBlocking { it.restore() } }
        val repository = SeriesRepository(api, db, images, session, localImages)
    }

    private suspend fun Fixture.recorder(scope: CoroutineScope, caliber: Caliber = Caliber.DEFAULT): SeriesRecorder =
        SeriesRecorder(repository, session, { caliber }, {}, { "training" }, {}, { encoded }, scope).also {
            it.caliber.first { value -> value == caliber }
            it.tag.first { value -> value == "training" }
            it.takenAt = timestamp
        }

    private suspend fun SeriesRecorder.awaitLocalSave() {
        // The old recorder drops signed-out scans synchronously. Fail promptly with that
        // contract conflict, rather than time out or hide it behind a fixture stub.
        assertNotEquals(SaveStatus.SignedOut, status.value, "#654 must retain a signed-out pending scan")
        withTimeout(5_000) { status.first { it is SaveStatus.SavedOnPhone || it is SaveStatus.Failed } }
        assertEquals(SaveStatus.SavedOnPhone(Caliber.DEFAULT), status.value)
    }

    @Test fun recorderKeepsConfirmedPicksDetectionMetadataAndPhotoWithoutHttp() = runBlocking {
        val fixture = Fixture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val recorder = fixture.recorder(scope)
        try {
            recorder.onSeriesDetected(listOf(detected), testImage(), geometry)
            recorder.commit(listOf(9))
            recorder.awaitLocalSave()
            val row = fixture.db.seriesQueries.selectLocal().executeAsOne()
            val request = seriesJson.decodeFromString<SeriesRequest>(row.request)
            assertEquals(timestamp, request.timestamp)
            assertEquals("training", request.tag)
            assertEquals(Caliber.DEFAULT.label, request.caliber)
            assertEquals(geometry, request.geometry)
            assertEquals(9, request.holes.single().ring)
            assertFalse(request.holes.single().innerTen)
            assertEquals(10, request.holes.single().detectedRing)
            assertEquals(true, request.holes.single().detectedInnerTen)
            assertEquals(12.0, request.holes.single().detectedW)
            assertEquals(14.0, request.holes.single().detectedH)
            assertNotNull(request.holes.single().detectedConf)
            assertNull(row.owner_user_id)
            assertContentEquals(jpeg, fixture.localImages.read(row.local_id))
            assertEquals(4000L, row.image_width) // Source coordinates, not downscaled JPEG size.
            val visible = fixture.repository.series.value.single()
            assertEquals(-row.local_id, visible.id)
            assertEquals(LocalMark.UNCLAIMED, visible.local)
            assertContentEquals(jpeg, fixture.repository.image(visible.id))
            assertTrue(fixture.requests.isEmpty())
        } finally { recorder.dispose(); scope.cancel() }
    }

    @Test fun recorderKeepsManualTypedAndRemovedHolesWithTheirProvenance() = runBlocking {
        val fixture = Fixture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val recorder = fixture.recorder(scope)
        try {
            recorder.onSeriesDetected(listOf(detected, manual), testImage(), geometry, removed = listOf(detected))
            recorder.commit(listOf(11, 8, 7))
            recorder.awaitLocalSave()
            val request = seriesJson.decodeFromString<SeriesRequest>(fixture.db.seriesQueries.selectLocal().executeAsOne().request)
            val live = request.holes.filterNot { it.deleted }
            assertEquals(listOf(10, 8, 7), live.map { it.ring })
            assertTrue(live.first().innerTen)
            for (hole in live.drop(1)) {
                assertNull(hole.detectedRing); assertNull(hole.detectedInnerTen)
                assertNull(hole.detectedX); assertNull(hole.detectedY)
                assertNull(hole.detectedW); assertNull(hole.detectedH); assertNull(hole.detectedConf)
            }
            assertNotNull(live[1].x)
            assertNull(live[2].x)
            assertEquals(detected.toHoleDto().copy(deleted = true), request.holes.single { it.deleted })
            assertTrue(fixture.repository.series.value.single().holes.none { it.deleted })
            assertTrue(fixture.requests.isEmpty())
        } finally { recorder.dispose(); scope.cancel() }
    }

    @Test fun recorderRepeatedCommitKeepsOneRowButAnotherScanKeepsAnother() = runBlocking {
        val fixture = Fixture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val recorder = fixture.recorder(scope)
        try {
            recorder.onSeriesDetected(listOf(detected), testImage(), geometry)
            recorder.commit(listOf(10))
            recorder.commit(listOf(10))
            recorder.awaitLocalSave()
            recorder.commit(listOf(10)) // Repeating Save after completion is idempotent too.
            assertEquals(1, fixture.db.seriesQueries.selectLocal().executeAsList().size)
            recorder.onSeriesDetected(listOf(manual), testImage(), geometry)
            recorder.commit(listOf(8))
            recorder.awaitLocalSave()
            val rows = fixture.db.seriesQueries.selectLocal().executeAsList()
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.client_id }.distinct().size)
            assertEquals(2, fixture.repository.series.value.size)
            assertTrue(fixture.requests.isEmpty())
        } finally { recorder.dispose(); scope.cancel() }
    }

    @Test fun missingCaliberAsksFirstAndChoosingItStillSavesTheSignedOutScan() = runBlocking {
        val fixture = Fixture()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val recorder = fixture.recorder(scope, Caliber.NONE)
        try {
            recorder.onSeriesDetected(listOf(detected), testImage(), geometry)
            recorder.commit(listOf(8))
            assertEquals(SaveStatus.NeedsCaliber, recorder.status.value)
            assertTrue(recorder.caliberDialogOpen.value)
            assertTrue(fixture.db.seriesQueries.selectLocal().executeAsList().isEmpty())
            assertTrue(fixture.requests.isEmpty())
            recorder.dismissCaliberDialog()
            recorder.commit(listOf(8))
            recorder.selectCaliber(Caliber.DEFAULT)
            recorder.awaitLocalSave()
            val request = seriesJson.decodeFromString<SeriesRequest>(fixture.db.seriesQueries.selectLocal().executeAsOne().request)
            assertEquals(8, request.holes.single().ring)
            assertEquals(Caliber.DEFAULT.label, request.caliber)
        } finally { recorder.dispose(); scope.cancel() }
    }

    private fun request(clientId: String = "scan-1") = SeriesRequest(
        timestamp, Caliber.DEFAULT.label, listOf(detected.toHoleDto()), geometry, "training", clientId,
    )

    @Test fun localStorePublishesUnclaimedSeriesAndUsesTheLocalImageStore() = runBlocking {
        val fixture = Fixture()
        val request = request()
        val localId = fixture.repository.keepLocal(request, encoded, owner = null)
        assertTrue(localId > 0)
        val row = fixture.db.seriesQueries.localById(localId).executeAsOne()
        assertEquals(request, seriesJson.decodeFromString<SeriesRequest>(row.request))
        assertEquals("scan-1", row.client_id)
        assertNull(row.owner_user_id)
        assertEquals(1L, row.has_image)
        assertContentEquals(jpeg, fixture.localImages.read(localId))
        assertTrue(fixture.images.files.isEmpty())
        val visible = fixture.repository.series.value.single()
        assertEquals(-localId, visible.id)
        assertEquals(LocalMark.UNCLAIMED, visible.local)
        assertContentEquals(jpeg, fixture.repository.image(visible.id))
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun localStoreDeduplicatesByClientIdAndOrdersDifferentScansNewestFirst() = runBlocking {
        val fixture = Fixture()
        val first = fixture.repository.keepLocal(request(), encoded)
        assertEquals(first, fixture.repository.keepLocal(request(), encoded))
        val next = fixture.repository.keepLocal(request("scan-2").copy(timestamp = "2026-10-14T10:20:30Z"), null)
        assertNotEquals(first, next)
        assertEquals(listOf(-next, -first), fixture.repository.series.value.map { it.id })
        assertEquals(2, fixture.db.seriesQueries.selectLocal().executeAsList().size)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun reopeningDatabaseAndDurableImageStoreRetainsTheSignedOutScan() = runBlocking {
        val dir = Files.createTempDirectory("markera-local-save-")
        val url = "jdbc:sqlite:${dir.resolve("series.db")}"
        var driver: JdbcSqliteDriver? = JdbcSqliteDriver(url)
        try {
            MarkeraDb.Schema.create(driver!!)
            val fixture = Fixture(MarkeraDb(driver!!), LocalImageStore(Path(dir.toString())))
            val id = fixture.repository.keepLocal(request(), encoded)
            driver!!.close()
            driver = JdbcSqliteDriver(url)
            val reopened = Fixture(MarkeraDb(driver!!), LocalImageStore(Path(dir.toString())))
            assertNull(reopened.repository.refresh())
            assertEquals(-id, reopened.repository.series.value.single().id)
            assertEquals(LocalMark.UNCLAIMED, reopened.repository.series.value.single().local)
            assertContentEquals(jpeg, reopened.repository.image(-id))
            assertEquals(request(), seriesJson.decodeFromString<SeriesRequest>(reopened.db.seriesQueries.localById(id).executeAsOne().request))
            assertTrue(reopened.requests.isEmpty())
        } finally {
            driver?.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun signedInSuccessfulRecorderStillPostsSeriesAndPhotoAndPublishesSyncedRow() = runBlocking {
        val fixture = Fixture(signedIn = true)
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val recorder = fixture.recorder(scope)
        try {
            recorder.onSeriesDetected(listOf(detected), testImage(), geometry)
            recorder.commit(listOf(9))
            withTimeout(5_000) { recorder.status.first { it is SaveStatus.Saved || it is SaveStatus.Failed } }
            assertIs<SaveStatus.Saved>(recorder.status.value)
            withTimeout(5_000) { fixture.repository.series.first { it.singleOrNull()?.hasImage == true } }
            assertEquals(41L, fixture.repository.series.value.single().id)
            assertNull(fixture.repository.series.value.single().local)
            assertEquals(2, fixture.requests.count { it.method == HttpMethod.Post })
            assertContentEquals(jpeg, fixture.images.read(41))
            assertTrue(fixture.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        } finally { recorder.dispose(); scope.cancel() }
    }
}
