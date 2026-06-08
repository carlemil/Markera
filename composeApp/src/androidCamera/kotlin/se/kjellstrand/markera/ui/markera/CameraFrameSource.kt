package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * [FrameSource] for the `camera` flavor: a live CameraX preview. The caller
 * owns the [PreviewView] so [capture] can pull an on-demand snapshot.
 */
private class CameraFrameSource(private val previewView: PreviewView) : FrameSource {
    override val requiresCameraPermission = true

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        CameraPreview(
            previewView = previewView,
            onError = onError,
            modifier = modifier,
        )
    }

    // PreviewView.getBitmap() returns a fresh copy, so the pipeline may
    // recycle it freely.
    override fun capture(): Bitmap? = previewView.bitmap

    // The live preview resumes on its own once the frozen snapshot clears.
    override fun onResumeLive() = Unit
}

@Composable
fun rememberFrameSource(): FrameSource {
    val context = LocalContext.current
    val previewView = remember {
        // Fit-centre so the live preview matches the frozen snapshot and the
        // fit-centre DetectionOverlay (boxes align on non-square frames).
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    return remember { CameraFrameSource(previewView) }
}
