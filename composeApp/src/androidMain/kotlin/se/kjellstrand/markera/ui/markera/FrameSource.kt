package se.kjellstrand.markera.ui.markera

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier

/**
 * Source of the frame fed into hole detection: a live CameraX preview, see
 * [rememberFrameSource].
 */
@Stable
interface FrameSource {
    /** Whether the CAMERA runtime permission must be granted before use. */
    val requiresCameraPermission: Boolean

    /** The live (non-frozen) preview shown in the viewport. */
    @Composable
    fun Preview(onError: (Throwable) -> Unit, modifier: Modifier)

    /** Grab the frame currently framed, or null if the camera is not ready. */
    suspend fun capture(): Bitmap?

    /** Called when the user taps "resume live" to drop a frozen snapshot. */
    fun onResumeLive()
}
