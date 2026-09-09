package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCameraPermission(frameSource: FrameSource): CameraPermissionState =
    CameraPermissionState(granted = true) {}
