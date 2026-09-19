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
