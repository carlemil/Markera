package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesRecorder

/**
 * Editing the holes on the frozen frame: add one where the user taps, move the
 * one they drag, remove the one they long-press. All coordinates are
 * source-image px. Null on screens that don't offer editing (then the frame
 * doesn't zoom either).
 */
class HoleEditing(
    val add: (x: Float, y: Float, reach: Float) -> Unit,
    val move: (index: Int, x: Float, y: Float) -> Int,
    val remove: (index: Int) -> Unit,
)

/** How close a touch has to land to a hole to grab it. */
private val GRAB_RADIUS = 24.dp

/**
 * The scanning viewport: live preview (or frozen snapshot) + detection
 * overlays.
 *
 * With [editing] set, a frozen frame that has been scored also pinch-zooms and
 * takes hole edits (see [HoleEditing]); the live preview never does.
 * A camera failure is logged and reported to [onError] as a user-facing message.
 */
@Composable
fun TargetScanner(
    frameSource: FrameSource,
    snapshotVm: MarkeraSnapshotViewModel,
    uiState: MarkeraUiState,
    showDebug: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    editing: HoleEditing? = null,
    keepPreviewAlive: Boolean = false,
) {
    val frozen = snapshotVm.snapshot
    // Editing (and with it the zoom) only on a frozen frame that has been scored.
    val editable = editing != null && frozen != null &&
        uiState.centre != null && uiState.ring != null && uiState.phase == ScanPhase.IDLE
    // The gesture loop is not recomposed, so it must read these through the
    // latest-value states rather than the values captured when it started.
    val state by rememberUpdatedState(uiState)
    val edit by rememberUpdatedState(editing)
    // A new frame (or clearing one) starts unzoomed.
    val zoomPan = rememberZoomPan(frozen)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }
    Box(
        modifier = if (editable) {
            modifier.photoGestures(
                t = zoomPan,
                imageW = uiState.imageWidth,
                imageH = uiState.imageHeight,
                grabPx = grabPx,
                // Keyed on the frame, not the lambdas: a lambda key would
                // restart the gesture loop on every recomposition.
                key = frozen,
                holeAt = { x, y, reach ->
                    nearestDetectionIndex(x, y, state.detections, reach)
                },
                onMove = { i, x, y -> edit?.move(i, x, y) ?: -1 },
                onAdd = { x, y, reach -> edit?.add(x, y, reach) },
                onRemove = { i -> edit?.remove(i) },
            )
        } else {
            modifier
        },
    ) {
        // The preview stays outside the zoom layer: it is a SurfaceView, which
        // ignores a graphicsLayer transform anyway, and it never zooms.
        // Behind a frozen frame it stays bound only for a continuous scan, which
        // needs the live feed to watch and the ImageCapture (bound beside it) to
        // capture with. The frozen photo covers it: it is centre-squared into
        // this square viewport and the zoom is clamped to keep it covering.
        if (frozen == null || keepPreviewAlive) {
            val cameraError = stringResource(Res.string.markera_error_camera)
            frameSource.Preview(
                onError = {
                    println("$TAG: camera failed $it")
                    onError(cameraError)
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Framing guide on the live viewfinder only — a centred circle
            // (~70% of the viewport) with a small crosshair at its centre.
            if (frozen == null) ViewfinderGuide(modifier = Modifier.fillMaxSize())
        }
        Box(modifier = Modifier.fillMaxSize().zoomPan(zoomPan)) {
            if (frozen != null) {
                val bitmap = remember(frozen) { frozen.toImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    // Fit-centre so the DetectionOverlay boxes (also fit-centre)
                    // line up with the holes in this non-square snapshot.
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DetectionOverlay(
                detections = uiState.detections,
                digits = uiState.digits,
                centre = uiState.centre,
                ring = uiState.ring,
                scores = uiState.scores,
                // The caliber the scan will be saved under; no recorder (a
                // preview) just means the calibration size.
                caliber = LocalSeriesRecorder.current?.caliber?.collectAsState()?.value
                    ?: Caliber.NONE,
                showDebug = showDebug,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (uiState.phase == ScanPhase.HOLES) {
            ScanningOverlay(
                centre = uiState.centre,
                ring = uiState.ring,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The auto-save recorder, provided by the nav host so the shared viewport can
 * show the caliber chip without every screen plumbing it through. Null when
 * there is nothing to save to (e.g. a preview).
 */
val LocalSeriesRecorder = staticCompositionLocalOf<SeriesRecorder?> { null }

/** The user's own calibers, so a stored label resolves to its diameter; empty with no recorder. */
@Composable
fun customCalibers(): List<Caliber> =
    LocalSeriesRecorder.current?.customCalibers?.collectAsState()?.value.orEmpty()

@Composable
fun CameraPermissionPrompt(onGrantClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(Res.string.markera_camera_permission_required),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onGrantClick) {
                Text(stringResource(Res.string.markera_grant_permission))
            }
        }
    }
}
