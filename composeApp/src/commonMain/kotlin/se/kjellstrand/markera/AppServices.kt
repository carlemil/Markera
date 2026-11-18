package se.kjellstrand.markera

import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.ui.markera.FrameSource
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
)

/** A fixed [frameSource] for the camera and an [engine] for the two detectors, see [AppServices.scanStandIns]. */
class ScanStandIns(val frameSource: FrameSource, val engine: () -> ScanEngine)
