package se.kjellstrand.markera

import androidx.compose.runtime.Composable
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendSessionRepository
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.ui.markera.CameraPermissionState
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.ImageSource
import se.kjellstrand.markera.ui.markera.PickedImage
import se.kjellstrand.markera.ui.markera.ScanEngine

/**
 * Everything the UI needs that only the platform can build. The entry point
 * (`MainActivity` on Android) creates one and hands it to the nav root.
 */
class AppServices(
    val series: SeriesServices,
    /** The hole-detection ONNX model, already unpacked to a readable file. */
    val modelPath: String,
    val shareFile: suspend (path: String) -> Unit,
    /** UI tests only: a stand-in camera and detectors. Null runs the device's own. */
    val scanStandIns: ScanStandIns? = null,
    /** UI tests only: answers every Sign in button in place of the provider chooser. Null runs the real one. */
    val signInStandIn: (suspend (BackendSessionRepository) -> BackendAuth)? = null,
)

/**
 * A fixed [frameSource] for the camera and an [engine] for the two detectors, see [AppServices.scanStandIns].
 * [pickImages], when set, answers Import in place of the system picker, and [cameraPermission]
 * the scan screen's camera permission in place of the system's.
 */
class ScanStandIns(
    val frameSource: FrameSource,
    val pickImages: ((ImageSource) -> List<PickedImage>)? = null,
    val cameraPermission: (@Composable () -> CameraPermissionState)? = null,
    val engine: () -> ScanEngine,
)
