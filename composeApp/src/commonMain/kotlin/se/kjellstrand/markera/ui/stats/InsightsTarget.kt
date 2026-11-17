package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.stats_insights_target_description
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.stats.InsightMark
import se.kjellstrand.markera.series.stats.InsightSection
import se.kjellstrand.markera.ui.markera.customCalibers

/** The ellipse, the arrow and the flier rings: fixed colours, like every photo overlay, in both themes. */
internal val ELLIPSE_MARK = Color(0xFFFFB300)
internal val ARROW_MARK = MEAN_MARK
internal val FLIER_MARK = Color(0xFFFF3D00)
/** How much of its opacity an overlay keeps while another finding's mark is highlighted. */
private const val DIMMED = 0.3f
/** The close-up never shows less than the black and a ring around it, nor more than the whole target. */
private const val MIN_VIEW_MM = 75.0
private const val VIEW_MARGIN = 1.15

/**
 * The radius in mm the Insights target shows: the group, its ellipse and the centre with a margin,
 * between [MIN_VIEW_MM] and the whole target, so a tight group is not a speck in the middle.
 */
internal fun insightViewMm(section: InsightSection): Double {
    var reach = 0.0
    section.series.forEach { s -> s.hits.forEach { reach = max(reach, hypot(it.xMm, it.yMm)) } }
    section.ellipse?.let { reach = max(reach, hypot(it.cxMm, it.cyMm) + it.semiMajorMm) }
    return (reach * VIEW_MARGIN).coerceIn(MIN_VIEW_MM, PLOT_RADIUS_MM.toDouble())
}

/**
 * One caliber's hits on the target face Tavla draws, with the covariance ellipse, an arrow from
 * the centre to the mean point of impact and, while the fliers finding is picked, a ring round
 * each flier. [highlight] is the picked finding's mark: it is drawn bolder and the others dim.
 * Pinch to zoom, drag to pan once zoomed, double-tap to reset, as on Tavla.
 */
@Composable
internal fun InsightsTarget(section: InsightSection, calibers: List<Caliber>, highlight: InsightMark) {
    val textMeasurer = rememberTextMeasurer()
    val custom = customCalibers()
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val viewMm = remember(section) { insightViewMm(section) }
    val description = stringResource(Res.string.stats_insights_target_description, section.hitCount)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    trackPinch({ zoom to pan }) { z, p -> zoom = z; pan = p }
                }
            }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        val scale = (min(size.width, size.height) / 2.0 / viewMm).toFloat()
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun at(xMm: Double, yMm: Double) = Offset(centre.x + xMm.toFloat() * scale, centre.y + yMm.toFloat() * scale)
        fun alpha(mark: InsightMark) = if (highlight == InsightMark.NONE || highlight == mark) 1f else DIMMED
        fun width(mark: InsightMark) = (if (highlight == mark) 4.dp else 2.dp).toPx()

        drawTargetFace(textMeasurer, scale)
        drawHits(section.series, calibers, custom, scale)

        section.ellipse?.let { e ->
            val c = at(e.cxMm, e.cyMm)
            val a = e.semiMajorMm.toFloat() * scale
            val b = e.semiMinorMm.toFloat() * scale
            // The axis is counter-clockwise with y up; the canvas' y runs down.
            rotate(-e.axisDeg.toFloat(), pivot = c) {
                listOf(BLACK to width(InsightMark.ELLIPSE) + 2.dp.toPx(), ELLIPSE_MARK to width(InsightMark.ELLIPSE)).forEach { (colour, w) ->
                    drawOval(
                        colour.copy(alpha = alpha(InsightMark.ELLIPSE) * if (colour == BLACK) 0.6f else 1f),
                        topLeft = Offset(c.x - a, c.y - b),
                        size = Size(2 * a, 2 * b),
                        style = Stroke(width = w),
                    )
                }
            }
        }

        section.arrow?.let { p -> drawArrow(centre, at(p.xMm, p.yMm), alpha(InsightMark.ARROW), width(InsightMark.ARROW)) }

        if (highlight == InsightMark.FLIERS) {
            section.fliers.forEach { hit ->
                val p = at(hit.xMm, hit.yMm)
                drawCircle(BLACK, radius = 12.dp.toPx(), center = p, style = Stroke(width = 4.dp.toPx()))
                drawCircle(FLIER_MARK, radius = 12.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** A line from [from] to [to] with a head at [to]; a dot when the two nearly meet. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, alpha: Float, width: Float) {
    val length = (to - from).getDistance()
    val head = 10.dp.toPx()
    listOf(BLACK to width + 2.dp.toPx(), ARROW_MARK to width).forEach { (colour, w) ->
        val c = colour.copy(alpha = alpha * if (colour == BLACK) 0.6f else 1f)
        if (length < head) {
            drawCircle(c, radius = w, center = to)
            return@forEach
        }
        drawLine(c, from, to, strokeWidth = w, cap = StrokeCap.Round)
        val angle = atan2(to.y - from.y, to.x - from.x)
        listOf(angle + 2.6f, angle - 2.6f).forEach { a ->
            drawLine(c, to, Offset(to.x + head * cos(a), to.y + head * sin(a)), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}
