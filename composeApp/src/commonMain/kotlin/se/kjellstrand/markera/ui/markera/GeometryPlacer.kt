package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.INNER_RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetPlane
import se.kjellstrand.markera.vision.fit67RingFromDigits
import se.kjellstrand.markera.vision.ringOutline

/**
 * The 6/7 ring the user is placing by hand, in source-image px: four points on
 * the black's edge in line with the digit rows — left, right, top, bottom, the
 * same four the automatic probes find — and the centre. The ring is the exact
 * perspective image of the 100 mm circle through the four ([TargetPlane.fromCross]),
 * so a tilted photo is fitted as well as a straight one.
 *
 * The centre follows the points (the crossing of the two diameters) until the
 * user drags it, or when the scan read it from the digits ([centreFixed]).
 */
data class GeometryDraft(
    val xs: List<Float>,
    val ys: List<Float>,
    val centreX: Float,
    val centreY: Float,
    val centreFixed: Boolean,
) {
    private val plane: TargetPlane? by lazy {
        TargetPlane.fromCross(
            DoubleArray(4) { xs[it].toDouble() },
            DoubleArray(4) { ys[it].toDouble() },
            TARGET_BLACK_RING_RADIUS_MM,
        )
    }

    /** The 6/7 ellipse through the four points; null while they make no ellipse (three in a line, crossed over). */
    val ring: FittedEllipse? by lazy { plane?.circleOutline(TARGET_BLACK_RING_RADIUS_MM) }

    val centre: CentreEstimate get() = CentreEstimate(centreX, centreY, CentreMethod.MANUAL)

    /** Point [i] (0..3 = left, right, top, bottom) moved to [x],[y]; a free centre follows. */
    fun withPoint(i: Int, x: Float, y: Float): GeometryDraft =
        copy(xs = xs.toMutableList().apply { this[i] = x }, ys = ys.toMutableList().apply { this[i] = y })
            .followCentre()

    /** The centre dragged to [x],[y]: from now on it stays where the user put it. */
    fun withCentre(x: Float, y: Float): GeometryDraft = copy(centreX = x, centreY = y, centreFixed = true)

    /** Everything shifted by [dx],[dy]. */
    fun movedBy(dx: Float, dy: Float): GeometryDraft = copy(
        xs = xs.map { it + dx },
        ys = ys.map { it + dy },
        centreX = centreX + dx,
        centreY = centreY + dy,
    )

    private fun followCentre(): GeometryDraft {
        if (centreFixed) return this
        val (x, y) = plane?.toImage(0.0, 0.0) ?: return this
        return copy(centreX = x.toFloat(), centreY = y.toFloat())
    }

    companion object {
        /** A circle of [radius] about [cx],[cy]: its four axis points. */
        fun circle(cx: Float, cy: Float, radius: Float, centreFixed: Boolean) = GeometryDraft(
            xs = listOf(cx - radius, cx + radius, cx, cx),
            ys = listOf(cy, cy, cy - radius, cy + radius),
            centreX = cx,
            centreY = cy,
            centreFixed = centreFixed,
        )
    }
}

/**
 * Where the hand placement starts: on what the scan did find. A ring and centre
 * give their own four points; a centre alone (the digits were read, the ring
 * was not) a circle sized from the digits; nothing, a circle in the middle of
 * the frame at the live guide's size. A centre read from the digits stays put.
 */
fun initialGeometryDraft(state: MarkeraUiState): GeometryDraft {
    val w = state.imageWidth.toFloat()
    val h = state.imageHeight.toFloat()
    val centre = state.centre?.takeIf { it.method != CentreMethod.NONE }
    val ring = state.ring
    if (centre != null && ring != null) {
        TargetPlane.of(centre, ring)?.let { plane ->
            val r = TARGET_BLACK_RING_RADIUS_MM
            val pts = listOf(-r to 0.0, r to 0.0, 0.0 to -r, 0.0 to r).map { plane.toImage(it.first, it.second) }
            return GeometryDraft(
                xs = pts.map { it.first.toFloat() },
                ys = pts.map { it.second.toFloat() },
                centreX = centre.x,
                centreY = centre.y,
                centreFixed = true,
            )
        }
    }
    // The live guide circle is 35% of the short side: a target framed to it starts close.
    val radius = ring?.semiMajor
        ?: centre?.let { fit67RingFromDigits(state.digits, it)?.semiMajor }
        ?: (0.35f * min(w, h))
    return GeometryDraft.circle(
        cx = centre?.x ?: ring?.cx ?: (w / 2f),
        cy = centre?.y ?: ring?.cy ?: (h / 2f),
        radius = radius,
        centreFixed = centre != null,
    )
}

/** How near (in dp) a drag must start to a point to move that point alone. */
private val GRAB = 32.dp

/** What a drag holds: one of the four edge points, the centre, or the whole thing. */
private sealed interface Grip {
    data class Point(val i: Int) : Grip
    data object Centre : Grip
    data object All : Grip
}

/**
 * Over the frozen frame, the four edge points and the centre the user fits to
 * the target. A drag that starts on a point moves that point (and so reshapes
 * the ellipse), one on the cross moves the centre, any other moves it all.
 * Everything follows the finger's motion, not its position, so the finger
 * never hides what is being aligned. The lines inside the black (7..10) are
 * drawn as the scoring will see them.
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
    // The gesture loop starts once: it reads the latest draft through these.
    val current by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier = modifier.pointerInput(imageWidth, imageHeight) {
            if (imageWidth <= 0 || imageHeight <= 0) return@pointerInput
            var grip: Grip = Grip.All
            detectDragGestures(
                onDragStart = { start ->
                    val scale = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
                    val ox = (size.width - imageWidth * scale) / 2f
                    val oy = (size.height - imageHeight * scale) / 2f
                    val d = current
                    fun dist(x: Float, y: Float) = hypot(start.x - (x * scale + ox), start.y - (y * scale + oy))
                    // The nearest handle within reach wins; the centre counts as one.
                    val candidates = (0 until 4).map { Grip.Point(it) to dist(d.xs[it], d.ys[it]) } +
                        (Grip.Centre to dist(d.centreX, d.centreY))
                    grip = candidates.filter { it.second < GRAB.toPx() }.minByOrNull { it.second }?.first ?: Grip.All
                },
            ) { pointer, drag ->
                pointer.consume()
                val scale = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
                val dx = drag.x / scale
                val dy = drag.y / scale
                val d = current
                fun cx(x: Float) = x.coerceIn(0f, imageWidth.toFloat())
                fun cy(y: Float) = y.coerceIn(0f, imageHeight.toFloat())
                change(
                    when (val g = grip) {
                        is Grip.Point -> d.withPoint(g.i, cx(d.xs[g.i] + dx), cy(d.ys[g.i] + dy))
                        Grip.Centre -> d.withCentre(cx(d.centreX + dx), cy(d.centreY + dy))
                        Grip.All -> d.movedBy(dx, dy)
                    },
                )
            }
        },
    ) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val ox = (size.width - imageWidth * scale) / 2f
        val oy = (size.height - imageHeight * scale) / 2f
        fun at(x: Float, y: Float) = Offset(x * scale + ox, y * scale + oy)
        val stroke = 3.dp.toPx()
        // A dark halo under each line keeps it visible on the white and on the black.
        val halo = Color.Black.copy(alpha = 0.5f)
        val ring = draft.ring
        if (ring != null) {
            drawTargetRing(ring, scale, ox, oy, halo, stroke + 2.dp.toPx())
            drawTargetRing(ring, scale, ox, oy, color, stroke)
            // The lines inside the black, from the same centre + ring the scoring uses.
            for (mm in INNER_RING_RADII_MM) {
                drawTargetRing(ringOutline(ring, draft.centre, mm), scale, ox, oy, color.copy(alpha = 0.5f), stroke * 0.5f)
            }
        }
        // The two diameters the points sit on, faint: they should run through the digit rows.
        for ((a, b) in listOf(0 to 1, 2 to 3)) {
            drawLine(color.copy(alpha = 0.35f), at(draft.xs[a], draft.ys[a]), at(draft.xs[b], draft.ys[b]), stroke * 0.5f)
        }
        val c = at(draft.centreX, draft.centreY)
        val arm = 14.dp.toPx()
        drawLine(halo, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke + 2.dp.toPx())
        drawLine(halo, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke + 2.dp.toPx())
        drawLine(color, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke)
        drawLine(color, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke)
        for (i in 0 until 4) {
            val p = at(draft.xs[i], draft.ys[i])
            drawCircle(halo, 10.dp.toPx(), p)
            drawCircle(color, 8.dp.toPx(), p)
            drawCircle(halo, 2.dp.toPx(), p)
        }
    }
}
