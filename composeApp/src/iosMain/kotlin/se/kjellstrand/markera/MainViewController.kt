package se.kjellstrand.markera

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.ktor.client.engine.darwin.Darwin
import kotlinx.io.files.Path
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.UserDefaultsBackendTokenStore
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.ui.AppNavHost
import se.kjellstrand.markera.ui.theme.MarkeraTheme

/** Built once: it owns the DB, the token store and the bundled model file. */
private val app by lazy {
    AppServices(
        series = SeriesServices(
            engine = Darwin.create(),
            baseUrl = (NSBundle.mainBundle.objectForInfoDictionaryKey("MarkeraBackendUrl") as? String)
                ?.takeIf { it.isNotBlank() }
                ?: "https://markera.duckdns.org",
            store = UserDefaultsBackendTokenStore(),
            driver = NativeSqliteDriver(MarkeraDb.Schema, "markera-series.db"),
            cacheDir = Path(
                NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                    .first() as String,
            ),
        ),
        modelPath = NSBundle.mainBundle.pathForResource("best", "onnx") ?: "",
        shareFile = { /* B4: UIActivityViewController */ },
    )
}

@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    MarkeraTheme { AppNavHost(app) }
}
