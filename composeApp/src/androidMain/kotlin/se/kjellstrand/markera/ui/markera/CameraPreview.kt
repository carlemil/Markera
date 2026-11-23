package se.kjellstrand.markera.ui.markera

import android.util.Rational
import androidx.camera.core.CameraSelector
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
 * when the user taps Scan; [bindGate] reports when that capture is bound.
 */
@Composable
fun CameraPreview(
    previewView: PreviewView,
    imageCapture: ImageCapture,
    bindGate: CameraBindGate,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val session = bindGate.open()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            // Disposed before the provider was ready (e.g. Back right after opening the
            // scan screen cold): binding now would keep the camera on behind Home.
            if (!session.mayBind()) return@addListener
            try {
                val provider = providerFuture.get()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(selector)
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
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
                session.bound()
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX bind failed", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // The listener runs on the main thread too, so it either has not run yet (and
            // will see the session disposed) or has bound, in which case the future is done
            // and get() does not block.
            if (session.dispose() && providerFuture.isDone) {
                try {
                    providerFuture.get().unbindAll()
                } catch (_: Throwable) {
                    // best-effort cleanup
                }
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
