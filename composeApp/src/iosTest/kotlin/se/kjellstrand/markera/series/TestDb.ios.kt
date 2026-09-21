package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.concurrent.AtomicInt
import se.kjellstrand.markera.series.db.MarkeraDb

// One name meant one shared in-memory database for the whole suite: rows from an
// earlier test were still there in the next one.
private val dbCount = AtomicInt(0)

actual fun testSeriesDb(): MarkeraDb =
    MarkeraDb(
        NativeSqliteDriver(
            MarkeraDb.Schema,
            "markera-test-${dbCount.addAndGet(1)}.db",
            onConfiguration = { it.copy(inMemory = true) },
        ),
    )
