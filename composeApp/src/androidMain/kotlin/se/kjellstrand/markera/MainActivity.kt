package se.kjellstrand.markera

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File
import se.kjellstrand.markera.series.shareFile
import se.kjellstrand.markera.ui.MarkeraApp
import se.kjellstrand.markera.ui.competition.rememberCompetitionHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Built outside composition. The series services (DB, DataStore) are the
        // process's, so a recreation reuses them.
        val app = AppServices(
            series = (application as MarkeraApplication).series,
            modelPath = prepareModel(this),
            shareFile = { shareFile(this@MainActivity, File(it)) },
        )
        setContent {
            MarkeraApp(app, rememberCompetitionHost())
        }
    }
}

private const val MODEL_ASSET = "best.onnx"

/**
 * Streams the model asset to a plain file once and returns its path: the native
 * ONNX runtime reads the ~40 MB model directly, instead of readBytes() staging
 * it on the Java heap (which OOMed small heaps). Re-copied after each app update
 * (the asset may have changed); the temp-file + rename keeps an interrupted copy
 * from being trusted.
 */
fun prepareModel(context: Context): String {
    val modelFile = File(context.filesDir, MODEL_ASSET)
    val apkTime = context.packageManager
        .getPackageInfo(context.packageName, 0).lastUpdateTime
    if (!modelFile.exists() || modelFile.lastModified() < apkTime) {
        val tmp = File(context.filesDir, "$MODEL_ASSET.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        check(tmp.renameTo(modelFile)) { "could not move $tmp into place" }
    }
    return modelFile.absolutePath
}
