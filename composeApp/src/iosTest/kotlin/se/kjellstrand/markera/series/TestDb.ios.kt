package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import se.kjellstrand.markera.series.db.MarkeraDb

actual fun testSeriesDb(): MarkeraDb =
    MarkeraDb(
        NativeSqliteDriver(
            MarkeraDb.Schema,
            "markera-test.db",
            onConfiguration = { it.copy(inMemory = true) },
        ),
    )
