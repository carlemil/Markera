package se.kjellstrand.markera

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.io.files.Path
import se.kjellstrand.markera.series.DataStoreBackendTokenStore
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb

class MarkeraApplication : Application() {
    /**
     * One per process, not per activity: a recreation (rotation, dark-mode
     * toggle) must not open a second SQLite driver.
     */
    val series by lazy {
        SeriesServices(
            engine = OkHttp.create(),
            baseUrl = BuildConfig.BACKEND_URL,
            store = DataStoreBackendTokenStore(this),
            driver = AndroidSqliteDriver(MarkeraDb.Schema, this, "markera-series.db"),
            cacheDir = Path(cacheDir.path),
        )
    }
}
