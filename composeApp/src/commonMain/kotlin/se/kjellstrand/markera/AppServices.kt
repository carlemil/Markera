package se.kjellstrand.markera

import se.kjellstrand.markera.series.SeriesServices

/**
 * Everything the UI needs that only the platform can build. The entry point
 * (`MainActivity` on Android) creates one and hands it to the nav root.
 */
class AppServices(
    val series: SeriesServices,
    /** The hole-detection ONNX model, already unpacked to a readable file. */
    val modelPath: String,
    val shareFile: suspend (path: String) -> Unit,
)
