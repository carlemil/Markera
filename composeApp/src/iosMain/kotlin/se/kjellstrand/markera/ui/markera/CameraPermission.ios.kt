package se.kjellstrand.markera.ui.markera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberCameraPermission(frameSource: FrameSource): CameraPermissionState {
    val requiresPermission = frameSource.requiresCameraPermission
    // `granted` doubles as the "ready to use" gate.
    var status by remember {
        mutableStateOf(
            if (requiresPermission) {
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
            } else {
                AVAuthorizationStatusAuthorized
            },
        )
    }
    LaunchedEffect(Unit) {
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { allowed ->
                dispatch_async(dispatch_get_main_queue()) {
                    status = if (allowed) {
                        AVAuthorizationStatusAuthorized
                    } else {
                        AVAuthorizationStatusDenied
                    }
                }
            }
        }
    }
    return CameraPermissionState(granted = status == AVAuthorizationStatusAuthorized) {
        // iOS asks once; after a denial the switch only exists in Settings.
        UIApplication.sharedApplication.openURL(
            NSURL(string = UIApplicationOpenSettingsURLString),
            options = mapOf<Any?, Any>(),
            completionHandler = null,
        )
    }
}
