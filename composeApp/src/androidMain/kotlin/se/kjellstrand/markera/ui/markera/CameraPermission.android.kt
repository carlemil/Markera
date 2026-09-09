package se.kjellstrand.markera.ui.markera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberCameraPermission(frameSource: FrameSource): CameraPermissionState {
    val context = LocalContext.current
    val requiresPermission = frameSource.requiresCameraPermission
    // `granted` doubles as the "ready to use" gate.
    var granted by remember {
        mutableStateOf(
            !requiresPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (requiresPermission && !granted) launcher.launch(Manifest.permission.CAMERA)
    }
    return CameraPermissionState(granted) { launcher.launch(Manifest.permission.CAMERA) }
}
