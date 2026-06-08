package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Source of the frame fed into hole detection.
 *
 * The `camera` flavor backs this with a live CameraX preview; the `mock`
 * flavor replays sample images bundled from the training/validation set so
 * the app runs in an emulator with no working camera. The implementation is
 * chosen at build time by the product flavor — each flavor source set
 * (`src/camera`, `src/mock`) provides its own [rememberFrameSource].
 */
@Stable
interface FrameSource {
    /** Whether the CAMERA runtime permission must be granted before use. */
    val requiresCameraPermission: Boolean

    /** The live (non-frozen) preview shown in the viewport. */
    @Composable
    fun Preview(onError: (Throwable) -> Unit, modifier: Modifier)

    /** Grab the frame currently shown, or null if not ready yet. */
    fun capture(): Bitmap?

    /** Called when the user taps "resume live" to drop a frozen snapshot. */
    fun onResumeLive()
}
