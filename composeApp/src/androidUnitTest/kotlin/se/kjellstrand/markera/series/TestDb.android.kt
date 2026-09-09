package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import se.kjellstrand.markera.series.db.MarkeraDb

actual fun testSeriesDb(): MarkeraDb =
    MarkeraDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MarkeraDb.Schema.create(it) })
