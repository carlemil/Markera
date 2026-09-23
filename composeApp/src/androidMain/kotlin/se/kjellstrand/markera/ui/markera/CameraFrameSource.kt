package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "Markera"

/**
 * [FrameSource] backed by a live CameraX preview. [capture] takes a still at the
 * sensor's full resolution through the [ImageCapture] bound next to the preview
 * (the 1:1 viewport crops it to exactly what the square preview shows), so
 * detection and the saved photo run on far more pixels than the ~1440 px screen
 * render the preview would give.
 */
private class CameraFrameSource(
    private val previewView: PreviewView,
    private val imageCapture: ImageCapture,
    private val imageAnalysis: ImageAnalysis,
    private val watchDir: File,
) : FrameSource {
    override val requiresCameraPermission = true

    // Continuous scan only: binds the analysis stream (and the low frame rate).
    private var analyzing by mutableStateOf(false)
    private val latest = AtomicReference<LumaFrame?>(null)
    private var lastSampleAt = 0L

    init {
        imageAnalysis.setAnalyzer(Dispatchers.Default.asExecutor(), ::analyze)
    }

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        CameraPreview(
            previewView = previewView,
            imageCapture = imageCapture,
            analysis = if (analyzing) imageAnalysis else null,
            onError = onError,
            modifier = modifier,
        )
    }

    override suspend fun capture(): Bitmap? = try {
        takePicture()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Never break a scan over the still: PreviewView.getBitmap() returns a
        // fresh (low-resolution) copy of what is on screen.
        Log.w(TAG, "takePicture failed, falling back to the preview bitmap", t)
        previewView.bitmap
    }

    private suspend fun takePicture(): Bitmap = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            Dispatchers.Default.asExecutor(),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        cont.resume(image.toUprightBitmap())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    // The live preview resumes on its own once the frozen snapshot clears.
    override fun onResumeLive() = Unit

    override val supportsContinuousScan = true

    override fun setWatching(on: Boolean) {
        analyzing = on
        if (!on) latest.set(null)
    }

    override fun takeLuma(): LumaFrame? = latest.getAndSet(null)

    /**
     * Writes set `<n>-ref.pgm`, `<n>-prev.pgm`, `<n>-cur.pgm`, `<n>-verdict.txt` to
     * `filesDir/watch/` (n one past the highest kept, zero-padded) and drops all but
     * the newest [WATCH_SETS_KEPT] sets. Pull them from a debug build with
     *
     *     adb exec-out run-as se.kjellstrand.markera tar c files/watch > watch.tar
     *
     * and replay them with ContinuousScanReplayTest (`-Dwatch.frames=<dir>`).
     */
    override fun recordWatch(files: Map<String, ByteArray>) {
        try {
            watchDir.mkdirs()
            val kept = watchDir.list().orEmpty().mapNotNull { it.substringBefore('-').toIntOrNull() }.toSet()
            val n = (kept.maxOrNull() ?: 0) + 1
            val prefix = n.toString().padStart(5, '0')
            for ((suffix, bytes) in files) File(watchDir, "$prefix-$suffix").writeBytes(bytes)
            watchDir.listFiles().orEmpty()
                .filter { (it.name.substringBefore('-').toIntOrNull() ?: n) <= n - WATCH_SETS_KEPT }
                .forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "recording watch set failed", e)
        }
    }

    /** Keeps about two frames a second, box-averaged down to the watch grid. */
    private fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleAt < ANALYSIS_INTERVAL_MS) return
            lastSampleAt = now
            latest.set(it.lumaFrame())
        }
    }
}

// ponytail: fixed ~2 samples/s off whatever rate the sensor runs at (most phones
// go no lower than ~5-7 fps); if battery is still bad, unbind the preview between samples.
private const val ANALYSIS_INTERVAL_MS = 500L

/** Debug recordings of the watch: this many sets stay in `filesDir/watch/`. */
private const val WATCH_SETS_KEPT = 30

/** The Y plane inside [ImageProxy.getCropRect], box-averaged to about [WATCH_GRID] a side. */
private fun ImageProxy.lumaFrame(): LumaFrame {
    val crop = cropRect
    val f = maxOf(1, maxOf(crop.width(), crop.height()) / WATCH_GRID)
    val w = crop.width() / f
    val h = crop.height() / f
    val plane = planes[0]
    val buffer = plane.buffer
    val row = ByteArray(w * f)
    val sums = IntArray(w * h)
    for (y in 0 until h * f) {
        buffer.position((crop.top + y) * plane.rowStride + crop.left * plane.pixelStride)
        if (plane.pixelStride == 1) {
            buffer.get(row, 0, row.size)
        } else {
            for (x in row.indices) row[x] = buffer.get(buffer.position() + x * plane.pixelStride)
        }
        val base = (y / f) * w
        for (x in row.indices) sums[base + x / f] += row[x].toInt() and 0xFF
    }
    val n = f * f
    return LumaFrame(w, h, ByteArray(sums.size) { (sums[it] / n).toByte() }, imageInfo.rotationDegrees)
}

@Composable
actual fun rememberFrameSource(): FrameSource {
    val context = LocalContext.current
    val previewView = remember {
        // Fill-centre so the live preview spans the full viewport width (the
        // portrait frame is cropped top/bottom rather than letterboxed). The
        // frozen snapshot and DetectionOverlay still use fit-centre, so boxes
        // align on the captured full frame.
        // COMPATIBLE (TextureView): the default SurfaceView punches its own hole
        // in the window and ignores clipping, so the fill-cropped frame spilled
        // over the top bar and the results area.
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            // Only while attached: the live preview, and in continuous mode the
            // preview kept behind a frozen frame, so a tripod-mounted series
            // does not sleep mid-way.
            keepScreenOn = true
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    return remember { CameraFrameSource(previewView, imageCapture, imageAnalysis, File(context.filesDir, "watch")) }
}
