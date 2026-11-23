package se.kjellstrand.markera.ui

import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.encodeSeriesJpeg
import se.kjellstrand.markera.ui.markera.ScanEngine
import se.kjellstrand.markera.ui.markera.TargetScanController

/**
 * What must outlive an activity recreation (dark mode, language, font scale, a resize):
 * the scan controller (the single ONNX session and its re-entry guard, which a native run
 * still in flight holds), the recorder (its pending scan belongs to the frozen frame the
 * scan screen's own view models keep) and the back stack. Holds only process-wide
 * services, never the activity. Closed when the activity finishes for good.
 */
internal class AppViewModel(series: SeriesServices, engine: ScanEngine) : ViewModel() {
    val scanController = TargetScanController(engine)

    // Auto-save: every scan the shared controller completes is offered to the
    // recorder, whichever screen started it.
    val recorder = SeriesRecorder(
        repository = series.repository,
        session = series.session,
        readCaliber = series.store::readCaliber,
        writeCaliber = series.store::writeCaliber,
        readTag = series.store::readTag,
        writeTag = series.store::writeTag,
        encodeJpeg = ::encodeSeriesJpeg,
        scope = viewModelScope,
        readCustomCalibers = series.store::readCustomCalibers,
        writeCustomCalibers = series.store::writeCustomCalibers,
    ).also {
        scanController.onSeriesDetected = it::onSeriesDetected
        scanController.onScanStarted = it::clear
    }

    /** The live back stack; [backStackSaver] hands it back after a configuration change. */
    var stack: MutableState<List<Screen>>? = null

    override fun onCleared() {
        recorder.dispose()
        // Mid-scan, the scan's own finally closes the session once the native run returns.
        scanController.close()
    }
}
