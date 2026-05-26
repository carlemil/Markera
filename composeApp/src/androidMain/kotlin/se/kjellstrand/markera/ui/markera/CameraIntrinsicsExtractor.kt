package se.kjellstrand.markera.ui.markera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import se.kjellstrand.markera.vision.CameraIntrinsics
import kotlin.math.PI
import kotlin.math.tan

/**
 * Pull camera intrinsics from CameraX's Camera2 interop. Three-tier
 * fallback: vendor-calibrated → derived from focal length + sensor size
 * → assumed 70° horizontal FOV. Always returns sensor-coords intrinsics
 * tagged with the source; the caller applies sensor→display rotation
 * and bitmap scaling via [CameraIntrinsics.scaledToBitmap].
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
fun extractIntrinsics(camera: Camera): CameraIntrinsics? {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val activeArray = info.getCameraCharacteristic(
        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
    ) ?: return null
    val sw = activeArray.width().toFloat()
    val sh = activeArray.height().toFloat()
    val rotation = camera.cameraInfo.sensorRotationDegrees

    info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        ?.takeIf { it.size >= 4 && it[0] > 0f && it[1] > 0f }
        ?.let { k ->
            return CameraIntrinsics(
                fx = k[0], fy = k[1], cx = k[2], cy = k[3],
                sensorWidthPx = sw, sensorHeightPx = sh,
                sensorRotationDeg = rotation,
                source = CameraIntrinsics.Source.CALIBRATED,
            )
        }

    val focal = info.getCameraCharacteristic(
        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
    )
    val phys = info.getCameraCharacteristic(
        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
    )
    if (focal != null && focal.isNotEmpty() && phys != null && phys.width > 0f && phys.height > 0f) {
        val fxPx = focal[0] / phys.width * sw
        val fyPx = focal[0] / phys.height * sh
        return CameraIntrinsics(
            fx = fxPx, fy = fyPx,
            cx = sw / 2f, cy = sh / 2f,
            sensorWidthPx = sw, sensorHeightPx = sh,
            sensorRotationDeg = rotation,
            source = CameraIntrinsics.Source.DERIVED,
        )
    }

    val fovRad = 70.0 * PI / 180.0
    val fxPx = (sw / 2.0 / tan(fovRad / 2.0)).toFloat()
    return CameraIntrinsics(
        fx = fxPx, fy = fxPx,
        cx = sw / 2f, cy = sh / 2f,
        sensorWidthPx = sw, sensorHeightPx = sh,
        sensorRotationDeg = rotation,
        source = CameraIntrinsics.Source.ASSUMED,
    )
}
