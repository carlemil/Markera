package se.kjellstrand.markera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.series.AppleReturn
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

    /** Sign in with Apple coming back from the browser (singleTop, so this is the same instance). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppleReturn.deliver(intent.data)
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser with nothing delivered: the user backed out, so stop the spinner.
        AppleReturn.cancel()
    }

    private fun start(modelPath: String) {
        // Built outside composition. The series services (DB, DataStore) are the
        // process's, so a recreation reuses them.
        val app = AppServices(
            series = (application as MarkeraApplication).series,
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
