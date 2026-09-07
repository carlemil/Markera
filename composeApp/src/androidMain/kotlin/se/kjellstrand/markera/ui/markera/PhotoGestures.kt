package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.min

/** Deep enough to place a hole precisely, shallow enough to stay sharp. */
private const val MAX_ZOOM = 5f

/**
 * Zoom/pan of a photo layer: [zoom] about the top-left corner, [panX]/[panY] in
 * viewport px. Hoisted so the (non-recomposed) gesture loop and the
 * [graphicsLayer] read the same values.
 */
class ZoomPan {
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
}

/** A fresh photo starts unzoomed: [key] is the image it belongs to. */
@Composable
fun rememberZoomPan(key: Any?): ZoomPan = remember(key) { ZoomPan() }

/** The transform [photoGestures] drives; put it on the layer holding photo + overlay. */
fun Modifier.zoomPan(t: ZoomPan): Modifier = graphicsLayer {
    scaleX = t.zoom
    scaleY = t.zoom
    translationX = t.panX
    translationY = t.panY
    transformOrigin = TransformOrigin(0f, 0f)
}

/**
 * One gesture loop for every photo interaction, on the *untransformed* box: two
 * fingers zoom/pan, one finger on a hole ([grabPx] on-screen reach, whatever the
 * zoom) drags it, one finger elsewhere pans while zoomed in, a tap on empty
 * target adds a hole and a long press (measured on release — a still finger
 * sends no events) removes the hole it started on. At zoom 1 an unhandled drag
 * stays unconsumed, so a surrounding column still scrolls.
 *
 * All coordinates handed to the callbacks are source-image px, mapped through
 * the same fit-centre letterbox `DetectionOverlay` draws with; [holeAt] and
 * [onAdd] also get the grab reach in those px. [key] restarts the loop when the
 * photo it works on changes — the callbacks must read current state themselves.
 */
fun Modifier.photoGestures(
    t: ZoomPan,
    imageW: Int,
    imageH: Int,
    grabPx: Float,
    key: Any?,
    holeAt: (x: Float, y: Float, reach: Float) -> Int,
    onMove: (index: Int, x: Float, y: Float) -> Unit,
    onAdd: (x: Float, y: Float, reach: Float) -> Unit,
    onRemove: (index: Int) -> Unit = {},
): Modifier = clipToBounds().pointerInput(key, imageW, imageH) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        fun imageAt(o: Offset) =
            viewportToImage((o.x - t.panX) / t.zoom, (o.y - t.panY) / t.zoom, w, h, imageW, imageH)
        // Constant on-screen grab reach, in image px.
        val reach = grabPx / t.zoom / min(w / imageW, h / imageH)
        var holeIndex = imageAt(down.position)?.let { holeAt(it.first, it.second, reach) } ?: -1
        var travel = 0f
        var pinched = false
        var upTime = down.uptimeMillis

        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                pinched = true
                holeIndex = -1
                val centroid = event.calculateCentroid(useCurrent = false)
                val pan = event.calculatePan()
                val next = (t.zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                // Keep the content under the centroid put.
                val k = next / t.zoom
                t.panX = centroid.x + pan.x - (centroid.x - t.panX) * k
                t.panY = centroid.y + pan.y - (centroid.y - t.panY) * k
                t.zoom = next
                t.panX = clampPan(t.panX, t.zoom, w)
                t.panY = clampPan(t.panY, t.zoom, h)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            upTime = change.uptimeMillis
            val delta = change.positionChange()
            travel += delta.getDistance()
            when {
                holeIndex >= 0 -> {
                    // Below the slop it is still a press on the hole, so don't nudge it.
                    if (travel > slop) {
                        imageAt(change.position)?.let { onMove(holeIndex, it.first, it.second) }
                    }
                    change.consume()
                }

                t.zoom > 1f -> {
                    t.panX = clampPan(t.panX + delta.x, t.zoom, w)
                    t.panY = clampPan(t.panY + delta.y, t.zoom, h)
                    change.consume()
                }

                pinched -> change.consume()
            }
        } while (event.changes.any { it.pressed })

        if (pinched || travel > slop) return@awaitEachGesture
        if (holeIndex >= 0) {
            if (upTime - down.uptimeMillis >= longPress) onRemove(holeIndex)
        } else {
            // A clean tap on empty target: place a hole there.
            imageAt(down.position)?.let { onAdd(it.first, it.second, reach) }
        }
    }
}

/** Translation that keeps the scaled square covering the viewport; 0 at zoom 1. */
private fun clampPan(t: Float, zoom: Float, size: Float): Float =
    t.coerceIn(-(zoom - 1f) * size, 0f)
