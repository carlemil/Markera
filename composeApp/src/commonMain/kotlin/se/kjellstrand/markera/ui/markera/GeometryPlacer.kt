package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.INNER_RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM

/** The 6/7 ring the user is placing by hand: a circle in source-image px, its middle the centre. */
data class GeometryDraft(val cx: Float, val cy: Float, val radius: Float)

/**
 * Where the hand placement starts: on what the scan did find (its centre, its
 * ring), else in the middle of the frame at the guide circle's size.
 */
fun initialGeometryDraft(state: MarkeraUiState): GeometryDraft {
    val w = state.imageWidth.toFloat()
    val h = state.imageHeight.toFloat()
    val centre = state.centre?.takeIf { it.method != CentreMethod.NONE }
    val ring = state.ring
    return GeometryDraft(
        cx = centre?.x ?: ring?.cx ?: (w / 2f),
        cy = centre?.y ?: ring?.cy ?: (h / 2f),
        // The live guide circle is 35% of the short side: a target framed to it starts close.
        radius = ring?.semiMajor ?: (0.35f * min(w, h)),
    )
}

/** Smallest ring the user can shrink to, in image px: a dot could not be grabbed again. */
private const val MIN_RADIUS_PX = 40f

/** How near the edge (in dp) a drag must start to resize instead of move. */
private val EDGE_GRAB = 28.dp

/**
 * Over the frozen frame, a circle the user fits to the 6/7 edge: a drag that
 * starts near its edge resizes it, any other drag moves it. Both follow the
 * finger's motion, not its position, so the finger never hides what is aligned.
 * The lines inside the black (7..10) are drawn too, to check the fit against.
 */
@Composable
fun GeometryPlacer(
    draft: GeometryDraft,
    imageWidth: Int,
    imageHeight: Int,
    onChange: (GeometryDraft) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MANUAL_HIT_COLOR,
) {
    // The gesture loop starts once: it reads the latest draft through this.
    val current by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier = modifier.pointerInput(imageWidth, imageHeight) {
            if (imageWidth <= 0 || imageHeight <= 0) return@pointerInput
            var resizing = false
            detectDragGestures(
                onDragStart = { start ->
                    val scale = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
                    val ox = (size.width - imageWidth * scale) / 2f
                    val oy = (size.height - imageHeight * scale) / 2f
                    val d = current
                    val fromCentre = hypot(start.x - (d.cx * scale + ox), start.y - (d.cy * scale + oy))
                    resizing = abs(fromCentre - d.radius * scale) < EDGE_GRAB.toPx()
                },
            ) { pointer, drag ->
                pointer.consume()
                val scale = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
                val ox = (size.width - imageWidth * scale) / 2f
                val oy = (size.height - imageHeight * scale) / 2f
                val d = current
                if (resizing) {
                    // The drag's component away from the centre grows the ring.
                    val ux = pointer.position.x - (d.cx * scale + ox)
                    val uy = pointer.position.y - (d.cy * scale + oy)
                    val len = hypot(ux, uy)
                    if (len < 1f) return@detectDragGestures
                    val grow = (drag.x * ux + drag.y * uy) / len / scale
                    change(d.copy(radius = (d.radius + grow).coerceAtLeast(MIN_RADIUS_PX)))
                } else {
                    change(
                        d.copy(
                            cx = (d.cx + drag.x / scale).coerceIn(0f, imageWidth.toFloat()),
                            cy = (d.cy + drag.y / scale).coerceIn(0f, imageHeight.toFloat()),
                        ),
                    )
                }
            }
        },
    ) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val ox = (size.width - imageWidth * scale) / 2f
        val oy = (size.height - imageHeight * scale) / 2f
        val c = Offset(draft.cx * scale + ox, draft.cy * scale + oy)
        val r = draft.radius * scale
        val stroke = 3.dp.toPx()
        // A dark halo under each line keeps it visible on the white and on the black.
        val halo = Color.Black.copy(alpha = 0.5f)
        drawCircle(halo, r, c, style = Stroke(stroke + 2.dp.toPx()))
        drawCircle(color, r, c, style = Stroke(stroke))
        for (mm in INNER_RING_RADII_MM) {
            drawCircle(color.copy(alpha = 0.5f), (mm / TARGET_BLACK_RING_RADIUS_MM).toFloat() * r, c, style = Stroke(stroke * 0.5f))
        }
        val arm = 14.dp.toPx()
        drawLine(halo, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke + 2.dp.toPx())
        drawLine(halo, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke + 2.dp.toPx())
        drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke)
        drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke)
        // Resize handles where the edge crosses the diagonals: a cue that the edge is grabbable.
        val k = SQRT1_2 * r
        for ((sx, sy) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) {
            val p = Offset(c.x + sx * k, c.y + sy * k)
            drawCircle(halo, 9.dp.toPx(), p)
            drawCircle(color, 7.dp.toPx(), p)
        }
    }
}

/** cos 45°: where the edge crosses the diagonals. */
private const val SQRT1_2 = 0.70710677f
