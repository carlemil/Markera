package se.kjellstrand.markera.ui.markera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Rational
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.util.Log

private const val TAG = "Markera"

/**
 * Live camera preview, no per-frame inference. The caller owns the
 * [PreviewView] and the [imageCapture] it takes the full-resolution still from
 * when the user taps Scan. A non-null [analysis] (continuous scan watching) is
 * bound too, with the camera at its lowest frame rate to spare the battery.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    previewView: PreviewView,
    imageCapture: ImageCapture,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    analysis: ImageAnalysis? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, analysis) {
        val handler = Handler(Looper.getMainLooper())
        var camera: Camera? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val previewBuilder = Preview.Builder().setResolutionSelector(selector)
                if (analysis != null) {
                    lowestFpsRange(provider)?.let { range ->
                        Log.i(TAG, "Continuous scan: camera at $range fps")
                        Camera2Interop.Extender(previewBuilder)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    }
                }
                val preview = previewBuilder
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                // The PreviewView is a square FILL_CENTER view, so a 1:1
                // FILL_CENTER viewport crops the still to exactly what the
                // preview shows (previewView.viewPort would need it laid out).
                val rotation = previewView.display?.rotation
                    ?: ContextCompat.getDisplayOrDefault(context).rotation
                imageCapture.targetRotation = rotation
                val useCases = UseCaseGroup.Builder()
                    .setViewPort(
                        ViewPort.Builder(Rational(1, 1), rotation)
                            .setScaleType(ViewPort.FILL_CENTER)
                            .build(),
                    )
                    .addUseCase(preview)
                    .addUseCase(imageCapture)
                    .apply { analysis?.let { addUseCase(it) } }
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    useCases,
                ).also { if (analysis != null) lockWhileWatching(it, handler) }
                analysis?.resolutionInfo?.let { Log.i(TAG, "Continuous scan: analysis ${it.resolution} crop ${it.cropRect}") }
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX bind failed", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            handler.removeCallbacksAndMessages(null)
            camera?.let { unlock(it) }
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Throwable) {
                // best-effort cleanup
            }
        }
    }

    // The fill-cropped frame overflows the square; clip it so it cannot cover
    // the top bar (needs the TextureView mode set in CameraFrameSource).
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The back camera's slowest advertised auto-exposure frame-rate range. */
@OptIn(ExperimentalCamera2Interop::class)
private fun lowestFpsRange(provider: ProcessCameraProvider): Range<Int>? {
    val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
        ?: return null
    return Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
}

/** Time the auto-exposure and white balance get to settle before they are locked. */
private const val LOCK_AFTER_MS = 1500L

/**
 * Continuous scan: a still target must not change on its own, so focus once on
 * the centre and hold it, then lock exposure and white balance once they have
 * settled. Otherwise the sensor's own hunting reads as "moving" or as a global change.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun lockWhileWatching(camera: Camera, handler: Handler) {
    val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
    camera.cameraControl.startFocusAndMetering(
        FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).disableAutoCancel().build(),
    )
    handler.postDelayed({
        Log.i(TAG, "Continuous scan: locking AE + AWB")
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build(),
        )
    }, LOCK_AFTER_MS)
}

/** Hands focus, exposure and white balance back to the camera's auto modes. */
@OptIn(ExperimentalCamera2Interop::class)
private fun unlock(camera: Camera) {
    camera.cameraControl.cancelFocusAndMetering()
    Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
}
