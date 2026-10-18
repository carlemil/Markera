package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import se.kjellstrand.markera.series.db.MarkeraDb
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesMigrationTest {
    @Test
    fun v1CacheSurvivesTheColumnDrop() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The shipped version-1 schema, as it sits on phones today.
        driver.execute(
            null,
            """CREATE TABLE series (id INTEGER NOT NULL PRIMARY KEY, timestamp TEXT NOT NULL,
               caliber TEXT NOT NULL, updated_at TEXT NOT NULL, has_image INTEGER NOT NULL,
               json TEXT NOT NULL)""",
            0,
        )
        driver.execute(null, "CREATE TABLE sync (user_id TEXT NOT NULL PRIMARY KEY, last_sync TEXT NOT NULL)", 0)
        driver.execute(null, "INSERT INTO series VALUES (1, '2026-09-01T10:00:00Z', '.22', 'x', 1, '{\"a\":1}')", 0)

        MarkeraDb.Schema.migrate(driver, 1, MarkeraDb.Schema.version)

        val q = MarkeraDb(driver).seriesQueries
        assertEquals(listOf("{\"a\":1}"), q.selectAll().executeAsList().map { it.json })
        q.upsert(2, "2026-09-02T10:00:00Z", "{\"b\":2}")
        assertEquals(listOf("{\"b\":2}", "{\"a\":1}"), q.selectAll().executeAsList().map { it.json })
    }
}

/** Literal shipped v3 schema, not Schema.create (which would silently test v4 -> v4). */
class LocalSeriesMigrationTest {
    private fun v3(driver: JdbcSqliteDriver) {
        driver.execute(null, "CREATE TABLE series (id INTEGER NOT NULL PRIMARY KEY, timestamp TEXT NOT NULL, json TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE sync (user_id TEXT NOT NULL PRIMARY KEY, last_sync TEXT NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, request TEXT NOT NULL, series_id INTEGER, image BLOB, image_width INTEGER, image_height INTEGER)", 0)
        val synced = SeriesDto(17, "2026-10-01T10:00:00Z", "22lr", emptyList())
        driver.execute(null, "INSERT INTO series VALUES(17, '2026-10-01T10:00:00Z', ?)", 1) { bindString(0, seriesJson.encodeToString(synced)) }
        driver.execute(null, "INSERT INTO sync VALUES('1','2026-10-01 10:00:00.000')", 0)
        for ((client, server) in listOf("unsent" to null, "photo-only" to 17L)) {
            val request = SeriesRequest(timestamp="2026-10-02T10:00:00Z", caliber="22lr", holes=emptyList(), clientId=client)
            driver.execute(null, "INSERT INTO outbox(user_id,request,series_id,image,image_width,image_height) VALUES('1',?,?,?,?,?)", 5) {
                bindString(0, seriesJson.encodeToString(request)); bindLong(1, server)
                bindBytes(2, byteArrayOf(3,2,1)); bindLong(3, 3000); bindLong(4, 2000)
            }
        }
    }
    @Test fun shippedV3UpgradePreservesSyncedStampAndBothKindsOfOutbox() {
        val file = java.nio.file.Files.createTempFile("markera-v3", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:$file").use { driver -> v3(driver) }
            JdbcSqliteDriver("jdbc:sqlite:$file").use { driver ->
                MarkeraDb.Schema.migrate(driver, 3, MarkeraDb.Schema.version)
                val q = MarkeraDb(driver).seriesQueries
                assertEquals(listOf(17L), q.selectAll().executeAsList().map { seriesJson.decodeFromString<SeriesDto>(it.json).id })
                assertEquals("2026-10-01 10:00:00.000", q.lastSync().executeAsOne().last_sync)
                assertEquals("1", q.lastSync().executeAsOne().user_id)
                val pending = q.outboxFor("1").executeAsList()
                assertEquals(listOf(null, 17L), pending.map { it.series_id })
                assertEquals(listOf("unsent", "photo-only"), pending.map { seriesJson.decodeFromString<SeriesRequest>(it.request).clientId })
                pending.forEach { row ->
                    kotlin.test.assertContentEquals(byteArrayOf(3,2,1), row.image)
                    assertEquals(3000L, row.image_width); assertEquals(2000L, row.image_height)
                }
                assertEquals(emptyList(), q.selectLocal().executeAsList())
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }
    @Test fun adoptionMovesLegacyBlobsToLocalFilesWithoutChangingOwnerOrClientIdAndIsIdempotent() = kotlinx.coroutines.runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            v3(driver); MarkeraDb.Schema.migrate(driver, 3, MarkeraDb.Schema.version)
            val db = MarkeraDb(driver)
            val api = SeriesApi(createSeriesHttpClient(io.ktor.client.engine.mock.MockEngine {
                error("Adopting local storage must not contact the backend")
            }), "http://host") { null }
            val localImages = FakeImageCache()
            val repo = SeriesRepository(api, db, FakeImageCache(), BackendSessionRepository(api, InMemoryBackendTokenStore()), localImages)
            repo.adoptLegacyOutbox()
            repo.adoptLegacyOutbox()
            val rows = db.seriesQueries.selectLocal().executeAsList()
            assertEquals(listOf("unsent", "photo-only"), rows.map { it.client_id })
            assertEquals(listOf("1", "1"), rows.map { it.owner_user_id })
            assertEquals(listOf(null, 17L), rows.map { it.server_id })
            rows.forEach {
                assertEquals(it.client_id, seriesJson.decodeFromString<SeriesRequest>(it.request).clientId)
                assertEquals(1L, it.has_image)
                assertEquals(3000L, it.image_width); assertEquals(2000L, it.image_height)
                kotlin.test.assertContentEquals(byteArrayOf(3,2,1), localImages.read(it.local_id))
            }
            assertEquals(emptyList(), db.seriesQueries.outboxFor("1").executeAsList())
            assertEquals(1, db.seriesQueries.selectAll().executeAsList().size)
        }
    }
}
