package se.kjellstrand.markera

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import se.kjellstrand.markera.series.DataStoreBackendTokenStore
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.series.shareFile
import se.kjellstrand.markera.ui.MarkeraApp
import se.kjellstrand.markera.ui.competition.rememberCompetitionHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The first launch after an install copies ~40 MB, so off the main thread;
        // the window shows the theme background meanwhile.
        lifecycleScope.launch {
            val modelPath = withContext(Dispatchers.IO) { prepareModel(this@MainActivity) }
            start(modelPath)
        }
    }

    private fun start(modelPath: String) {
        // Built once, outside composition: it owns the DB, the DataStore and the
        // unpacked model file.
        val app = AppServices(
            series = SeriesServices(
                engine = OkHttp.create(),
                baseUrl = BuildConfig.BACKEND_URL,
                store = DataStoreBackendTokenStore(this),
                driver = AndroidSqliteDriver(MarkeraDb.Schema, this, "markera-series.db"),
                cacheDir = Path(cacheDir.path),
            ),
            modelPath = modelPath,
            shareFile = { shareFile(this@MainActivity, File(it)) },
        )
        setContent {
            MarkeraApp(app, rememberCompetitionHost())
        }
    }
}

private const val MODEL_ASSET = "best.onnx"

// An activity recreated mid-copy would otherwise start a second copy into the same .tmp.
private val modelLock = Any()

/**
 * Streams the model asset to a plain file once and returns its path: the native
 * ONNX runtime reads the ~40 MB model directly, instead of readBytes() staging
 * it on the Java heap (which OOMed small heaps). Re-copied after each app update
 * (the asset may have changed); the temp-file + rename keeps an interrupted copy
 * from being trusted. Lives in noBackupFilesDir: it is over Auto Backup's 25 MB
 * limit and ships in the APK anyway. Blocking; call it off the main thread.
 */
fun prepareModel(context: Context): String = synchronized(modelLock) {
    // Installs before noBackupFilesDir kept it in filesDir, where it broke the backup.
    File(context.filesDir, MODEL_ASSET).delete()
    File(context.filesDir, "$MODEL_ASSET.tmp").delete()
    val modelFile = File(context.noBackupFilesDir, MODEL_ASSET)
    val apkTime = context.packageManager
        .getPackageInfo(context.packageName, 0).lastUpdateTime
    if (!modelFile.exists() || modelFile.lastModified() < apkTime) {
        val tmp = File(context.noBackupFilesDir, "$MODEL_ASSET.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        check(tmp.renameTo(modelFile)) { "could not move $tmp into place" }
    }
    modelFile.absolutePath
}
