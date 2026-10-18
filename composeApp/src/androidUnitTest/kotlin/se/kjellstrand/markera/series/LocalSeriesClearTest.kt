package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.series.stats.*
import se.kjellstrand.markera.ui.history.*
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Real persisted local rows, independent of the still-unimplemented keepLocal path. */
@OptIn(ExperimentalTime::class)
class LocalSeriesClearTest {
    private val photo = byteArrayOf(1, 4, 9)
    private val stamp = "2026-10-01 10:00:00.000"
    private class Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = MarkeraDb(driver).also { MarkeraDb.Schema.create(driver) }
        val images = FakeImageCache()
        val localImages = FakeImageCache()
        val requests = mutableListOf<String>()
        var response = "[]"
        var deleteStatus = HttpStatusCode.NoContent
        val api = SeriesApi(createSeriesHttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            when {
                request.url.encodedPath == "/account" -> respond("", deleteStatus)
                request.method == HttpMethod.Get -> respond(response, HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("", HttpStatusCode.NoContent)
            }
        }), "http://host") { "tok" }
        val store = InMemoryBackendTokenStore(BackendAuth("tok", 1L, "dev"))
        val session = BackendSessionRepository(api, store).also { runBlocking { it.restore() } }
        val repo = SeriesRepository(api, db, images, session, localImages)
        fun seedLocal(owner: String? = null): Long {
            val request = SeriesRequest(timestamp = "2026-10-18T10:00:00Z", caliber = "22lr",
                holes = listOf(HoleDto(500.0, 500.0, 10, true, 0.0)),
                geometry = GeometryDto(500.0,500.0,500.0,500.0,100.0,100.0,0.0),
                tag = "practice", clientId = "clear-test")
            driver.execute(null, "INSERT INTO local_series (client_id,owner_user_id,request,image_width,image_height,has_image,created_at) VALUES (?, ?, ?, 1000,1000,1,?)", 4) {
                bindString(0, request.clientId); bindString(1, owner)
                bindString(2, seriesJson.encodeToString(request)); bindString(3, request.timestamp)
            }
            return db.seriesQueries.selectLocal().executeAsOne().local_id.also {
                localImages.write(it, byteArrayOf(1,4,9))
            }
        }
        fun seedSynced() {
            val s = SeriesDto(45, "2026-10-01T10:00:00Z", "22lr", emptyList(), hasImage = true)
            db.seriesQueries.upsert(s.id, s.timestamp, seriesJson.encodeToString(s))
            db.seriesQueries.setLastSync("1", "2026-10-01 10:00:00.000")
            images.write(45, byteArrayOf(7))
        }
    }
    private fun assertLocalRetained(f: Fixture, id: Long) {
        assertEquals(id, f.db.seriesQueries.selectLocal().executeAsOne().local_id)
        assertContentEquals(photo, f.localImages.read(id))
        val local = assertNotNull(f.repo.series.value.singleOrNull { it.id == -id }, "Retained local row must stay visible after clearing account cache")
        assertEquals(LocalMark.UNCLAIMED, local.local)
        assertTrue(local.hasImage)
    }
    @Test fun signOutClearsSyncedCacheButKeepsLocalRowsAndPhotos() = runBlocking {
        val f = Fixture(); val id = f.seedLocal(); f.seedSynced()
        f.session.signOut(); f.repo.clear()
        assertNull(f.session.auth.value)
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().isEmpty())
        assertNull(f.db.seriesQueries.lastSync().executeAsOneOrNull())
        assertNull(f.images.read(45))
        assertLocalRetained(f, id)
    }
    // This is the AccountRow operation order; rendering/confirmation stays a #654 phone check.
    @Test fun successfulAccountDeleteKeepsUnsentWork() = runBlocking {
        val f = Fixture(); val id = f.seedLocal(); f.seedSynced()
        f.api.deleteAccount(); f.session.signOut(); f.repo.clear()
        assertTrue(f.requests.first().startsWith("DELETE http://host/account"))
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().isEmpty())
        assertNull(f.images.read(45)); assertLocalRetained(f, id)
    }
    @Test fun failedAccountDeleteClearsNothing() = runBlocking {
        val f = Fixture(); val id = f.seedLocal(); f.seedSynced()
        f.deleteStatus = HttpStatusCode.ServiceUnavailable
        assertFailsWith<SeriesApiException> {
            f.api.deleteAccount(); f.session.signOut(); f.repo.clear()
        }
        assertNotNull(f.session.auth.value)
        assertEquals(45L, f.db.seriesQueries.selectAll().executeAsOne().id)
        assertContentEquals(byteArrayOf(7), f.images.read(45))
        assertEquals(id, f.db.seriesQueries.selectLocal().executeAsOne().local_id)
        assertContentEquals(photo, f.localImages.read(id))
    }
    @Test fun accountSwitchWipesOnlyPreviousAccountCache() = runBlocking {
        val f = Fixture(); val id = f.seedLocal(); f.seedSynced()
        f.store.write(BackendAuth("other", 2L, "dev")); f.session.restore()
        assertNull(f.repo.refresh())
        assertNull(f.images.read(45))
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().isEmpty())
        assertLocalRetained(f, id)
    }
    @Test fun localDatesNeverBecomeSinceAndServerTombstonesCannotDeleteLocalRows() = runBlocking {
        val f = Fixture(); val id = f.seedLocal(); f.seedSynced()
        f.response = """[{"id":$id,"timestamp":"","caliber":"","holes":[],"deleted":true,"updatedAt":"2026-10-02 10:00:00.000"}]"""
        assertNull(f.repo.refresh())
        assertTrue(f.requests.single().contains("since=2026-10-01"))
        assertEquals("2026-10-02 10:00:00.000", f.db.seriesQueries.lastSync().executeAsOne().last_sync)
        assertLocalRetained(f, id)
    }
    @Test fun negativeIdsReadOnlyPersistentLocalPhotosAndThumbnails() = runBlocking {
        val f = Fixture(); val id = f.seedLocal()
        f.localImages.writeThumb(id, byteArrayOf(8, 8))
        assertContentEquals(photo, f.repo.image(-id))
        assertContentEquals(byteArrayOf(8, 8), f.repo.thumbnail(-id, 64))
        assertNull(f.repo.image(-9999))
        assertTrue(f.requests.isEmpty(), "Missing local photos must not trigger server requests")
        assertTrue(f.images.files.isEmpty(), "Local photos must not pollute synced cache")
    }
    @Test fun historyStatsCountAndExportIncludeMarkedLocalRowsAndOriginalPhoto() = runBlocking {
        val f = Fixture(); val id = f.seedLocal()
        f.repo.clear() // reload local-only content while clearing the account cache
        val rows = f.repo.series.value
        assertEquals(listOf(-id), rows.map { it.id })
        assertEquals(LocalMark.UNCLAIMED, rows.single().local)
        assertEquals(1, rows.filteredBy(HistoryFilter(tags=setOf("practice")), Instant.parse("2026-10-18T12:00:00Z")).size)
        assertEquals(1, rows.plotSeries(StatsFilter(tags=setOf("practice"))).size)
        assertEquals(10, rows.single().total())
        val dir = Files.createTempDirectory("markera-local-export")
        try {
            val archive = exportSeriesZip(f.repo, kotlinx.io.files.Path(dir.toString()))
            ZipFile(archive.toString()).use { zip ->
                assertContentEquals(photo, zip.getInputStream(zip.getEntry("images/${-id}.jpg")).readBytes())
                assertTrue(zip.getInputStream(zip.getEntry("series.csv")).reader().readText().contains("${-id};2026-10-18"))
                assertTrue(zip.getInputStream(zip.getEntry("holes.csv")).reader().readText().contains("${-id};0;"))
            }
        } finally { dir.toFile().deleteRecursively() }
        assertTrue(f.requests.isEmpty(), "Local image export must never download a negative server id")
    }
}
