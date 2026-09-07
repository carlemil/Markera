package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * [FrameSource] backed by a live CameraX preview. The caller owns the
 * [PreviewView] so [capture] can pull an on-demand snapshot.
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
    return remember { CameraFrameSource(previewView) }
}
