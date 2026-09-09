package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
) : FrameSource {
    override val requiresCameraPermission = true

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        CameraPreview(
            previewView = previewView,
            imageCapture = imageCapture,
            onError = onError,
            modifier = modifier,
        )
    }

    override suspend fun capture(): Bitmap? = try {
        takePicture()
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
    return remember { CameraFrameSource(previewView, imageCapture) }
}
