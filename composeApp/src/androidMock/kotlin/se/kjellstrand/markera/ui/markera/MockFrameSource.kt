package se.kjellstrand.markera.ui.markera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlin.random.Random

private const val TAG = "MarkeraMock"
private const val FRAMES_DIR = "fake_frames"
private const val INDEX = "$FRAMES_DIR/index.txt"

/**
 * [FrameSource] for the `mock` flavor: instead of a camera it shows a random
 * image bundled from the hole dataset (see the `prepareMockFrames` Gradle
 * task). Tapping the preview — or resuming live after a detection — picks a
 * new random image, so you can exercise many frames without restarting.
 */
private class MockFrameSource(
    private val appContext: Context,
    private val names: List<String>,
) : FrameSource {
    override val requiresCameraPermission = false

    private var current by mutableStateOf(pickRandom())

    // Bumped on every new image so the screen auto-detects it (no tap). The
    // initial 0 triggers detection of the first frame on first composition.
    private var frameKey by mutableStateOf(0)
    override val autoDetectKey: Int? get() = frameKey

    private fun pickRandom(): Bitmap? {
        if (names.isEmpty()) return null
        return decodeFrame(appContext, names[Random.nextInt(names.size)])
    }

    private fun shuffle() {
        current = pickRandom()
        frameKey++
    }

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        val bmp = current
        Box(modifier = modifier.clickable { shuffle() }) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    // Fit-centre to match the frozen snapshot + DetectionOverlay,
                    // so framing doesn't jump and boxes stay aligned.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // Hand the pipeline a private copy: it recycles what it gets, and we
    // keep [current] alive for the preview.
    override fun capture(): Bitmap? {
        val src = current ?: return null
        return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
    }

    override fun onResumeLive() = shuffle()
}

private fun loadFrameNames(context: Context): List<String> = try {
    context.assets.open(INDEX).bufferedReader().useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }
} catch (t: Throwable) {
    Log.w(TAG, "no $INDEX; listing $FRAMES_DIR directly", t)
    runCatching { context.assets.list(FRAMES_DIR)?.toList() }.getOrNull().orEmpty()
        .filter { it != "index.txt" }
}

private fun decodeFrame(context: Context, name: String): Bitmap? = try {
    context.assets.open("$FRAMES_DIR/$name").use { BitmapFactory.decodeStream(it) }
} catch (t: Throwable) {
    Log.w(TAG, "failed to decode $name", t)
    null
}

@Composable
fun rememberFrameSource(): FrameSource {
    val context = LocalContext.current
    return remember {
        MockFrameSource(context.applicationContext, loadFrameNames(context))
    }
}
