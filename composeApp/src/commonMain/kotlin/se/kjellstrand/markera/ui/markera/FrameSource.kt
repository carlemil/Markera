package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import se.kjellstrand.markera.vision.PlatformImage

/**
 * Source of the frame fed into hole detection, see [rememberFrameSource]: a live
 * CameraX preview on Android, AVFoundation or the photo picker on iOS.
 */
@Stable
interface FrameSource {
    /** Whether the CAMERA runtime permission must be granted before use. */
    val requiresCameraPermission: Boolean

    /** The live (non-frozen) preview shown in the viewport. */
    @Composable
    fun Preview(onError: (Throwable) -> Unit, modifier: Modifier)

    /** Grab the frame currently framed, or null if the camera is not ready. */
    suspend fun capture(): PlatformImage?

    /** Called when the user taps "resume live" to drop a frozen snapshot. */
    fun onResumeLive()

    /** Whether this source has a live feed a continuous scan can watch. */
    val supportsContinuousScan: Boolean get() = false

    /**
     * A [size]x[size] row-major luma thumbnail of the live feed, for change
     * detection; null when no frame is available yet.
     */
    suspend fun peekLuma(size: Int): ByteArray? = null
}

@Composable
expect fun rememberFrameSource(): FrameSource
