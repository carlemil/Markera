package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable

/** Camera permission gate shared by every scanning screen. */
class CameraPermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
expect fun rememberCameraPermission(frameSource: FrameSource): CameraPermissionState
