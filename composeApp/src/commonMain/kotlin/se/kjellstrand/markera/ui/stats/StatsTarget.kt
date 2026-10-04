package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HIT_DOT_ALPHA
import se.kjellstrand.markera.series.hitDotRadiusMm
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.SeriesStatistics
import se.kjellstrand.markera.ui.markera.customCalibers
import se.kjellstrand.markera.vision.INNER_TEN_RADIUS_MM
import se.kjellstrand.markera.vision.RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.ringDigitRadiusMm

/** Ring 1's outer edge — the whole drawn target, and the canvas' mm half-width. */
private const val PLOT_RADIUS_MM = 250f
private const val MAX_ZOOM = 6f

private val PAPER = Color(0xFFE8DEC8)
private val BLACK = Color(0xFF15151A)
private val LINE_ON_BLACK = Color(0xFFEDEDED)
private val LINE_ON_PAPER = Color(0xFF6B6455)
/** Grey for a series with no caliber ("-"): it gets no slot in the palette. */
private val NO_CALIBER = Color(0xFF9E9E9E)

/** A caliber's colour: its slot in [calibers], so Tavla and Trend agree on it. */
internal fun caliberColour(caliber: Caliber, calibers: List<Caliber>): Color =
    calibers.indexOf(caliber).let {
        if (it < 0) NO_CALIBER else TREND_COLOURS[it.mod(TREND_COLOURS.size)]
    }

// Green sits far from the palette, magenta off to its pink side; the
// + / × shapes and drawMark's dark halo still tell them apart for colour-blind readers.
private val MEAN_MARK = Color(0xFFFF00C8)
private val MEDIAN_MARK = Color(0xFF00C853)

/** Marker geometry, shared by the target and the legend (see [drawMark]). */
private val MARK_ARM = 5.dp
private val MARK_HALO = 2.dp
private val MARK_STROKE = 1.dp

/** The timeline's knob: round, squat over the 8 dp bar. */
private val KNOB = DpSize(20.dp, 20.dp)
/** The timeline's height, and the reach of a tap on the knob: a finger, not the drawn knob. */
private val KNOB_TOUCH = 40.dp

/**
 * The whole target (rings 1-10) with every filtered hit on it. Offsets are in
 * image axes (y down), so they map straight onto canvas coordinates. Pinch to
 * zoom, drag to pan once zoomed, double-tap to reset.
 */
@Composable
internal fun TargetCanvas(
    plotted: List<PlottedSeries>,
    stats: SeriesStatistics?,
    /** Every caliber on any plottable series — see [caliberColour]. */
    calibers: List<Caliber>,
) {
    val textMeasurer = rememberTextMeasurer()
    // The user's own calibers carry their diameter only on this device.
    val custom = customCalibers()
    // The layer scales about its top-left corner, so zooming about the pinch
    // centroid is a plain "keep the centroid still" rescale of the pan.
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // The numbers are spelled out in the measurement rows below; this names the picture.
    val description = stringResource(Res.string.stats_target_description, stats?.hitCount ?: 0, plotted.size)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger on the unzoomed target still scrolls the page.
                        // The centroid is unspecified once the last finger lifts;
                        // using it would turn the pan into NaN and blank the layer.
                        val centroid = event.calculateCentroid()
                        if (centroid.isSpecified && (event.changes.size > 1 || zoom > 1f)) {
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            val moved = (pan - centroid) * (newZoom / zoom) + centroid + event.calculatePan()
                            zoom = newZoom
                            pan = Offset(
                                moved.x.coerceIn(size.width * (1f - newZoom), 0f),
                                moved.y.coerceIn(size.height * (1f - newZoom), 0f),
                            )
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
        val scale = min(size.width, size.height) / 2f / PLOT_RADIUS_MM
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun r(mm: Double) = mm.toFloat() * scale

        drawRect(PAPER)
        drawCircle(BLACK, radius = r(TARGET_BLACK_RING_RADIUS_MM), center = centre)
        // Every ring line, plus the inner-ten circle.
        (RING_RADII_MM.filter { it <= PLOT_RADIUS_MM } + INNER_TEN_RADIUS_MM).forEach { mm ->
            drawCircle(
                color = if (mm <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else LINE_ON_PAPER,
                radius = r(mm),
                center = centre,
                style = Stroke(width = 1.5f),
            )
        }

        // Ring digits 1..9, centred in their band on all four axis arms.
        val digitSize = 11f * scale
        // Centred horizontally; the third of the text size centres vertically.
        fun digit(ring: Int, colour: Color, x: Float, y: Float) {
            val layout = textMeasurer.measure(
                ring.toString(),
                TextStyle(color = colour, fontSize = digitSize.toSp()),
            )
            drawText(
                layout,
                topLeft = Offset(
                    x - layout.size.width / 2f,
                    y + digitSize / 3f - layout.firstBaseline,
                ),
            )
        }
        for (ring in 1..9) {
            val mid = ringDigitRadiusMm(ring)
            val colour = if (mid <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else LINE_ON_PAPER
            digit(ring, colour, centre.x - r(mid), centre.y)
            digit(ring, colour, centre.x + r(mid), centre.y)
            digit(ring, colour, centre.x, centre.y - r(mid))
            digit(ring, colour, centre.x, centre.y + r(mid))
        }

        plotted.forEach { series ->
            val caliber = Caliber.fromLabel(series.series.caliber, custom)
            val colour = caliberColour(caliber, calibers).copy(alpha = HIT_DOT_ALPHA)
            // scale is px/mm here, so the caliber's mm radius converts directly.
            val dotRadius = caliber.hitDotRadiusMm() * scale
            series.hits.forEach { hit ->
                val at = Offset(centre.x + r(hit.xMm), centre.y + r(hit.yMm))
                drawCircle(colour, radius = dotRadius, center = at)
                drawCircle(
                    hitOutline(hypot(hit.xMm, hit.yMm)),
                    radius = dotRadius,
                    center = at,
                    style = Stroke(width = 1f),
                )
            }
        }

        // Mean (+) and median (×) point of impact, on top of the hits.
        if (stats != null) {
            drawMark(
                Offset(centre.x + r(stats.impactXMm), centre.y + r(stats.impactYMm)),
                MEAN_MARK,
                diagonal = false,
            )
            drawMark(
                Offset(centre.x + r(stats.medianXMm), centre.y + r(stats.medianYMm)),
                MEDIAN_MARK,
                diagonal = true,
            )
        }
    }
}

/**
 * A hit's outline: the inverse of the paper it lands on — light inside the black
 * 6/7 disk, dark on the cream outside it — so the dot's edge stays crisp either
 * way. Same split as the ring lines and digits above.
 *
 * ponytail: one colour for the whole circle, so a dot straddling the 100 mm edge
 * picks the side its centre is on; clip-draw it twice if that ever reads wrong.
 */
private fun hitOutline(radiusMm: Double) =
    (if (radiusMm <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else BLACK)
        .copy(alpha = HIT_DOT_ALPHA)

/**
 * The point-of-impact mark: "+" on the axes, "×" on the diagonals. Sized in dp so
 * the target and the legend below it draw the identical symbol.
 */
private fun DrawScope.drawMark(at: Offset, colour: Color, diagonal: Boolean) {
    val arm = MARK_ARM.toPx()
    // The diagonal arms are shortened so both symbols span the same extent.
    val d = arm / sqrt(2f)
    val (a, b) = if (diagonal) {
        Offset(d, d) to Offset(d, -d)
    } else {
        Offset(arm, 0f) to Offset(0f, arm)
    }
    // Dark pass first, so the marker reads on both paper and black.
    listOf(BLACK to MARK_HALO, colour to MARK_STROKE).forEach { (c, width) ->
        drawLine(c, at - a, at + a, strokeWidth = width.toPx())
        drawLine(c, at - b, at + b, strokeWidth = width.toPx())
    }
}

/**
 * The key to the target: a swatch per caliber on show — only worth drawing when the
 * segment holds two or more, since one caliber means every dot is the same colour —
 * then what the two point-of-impact markers mean.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkerLegend(plotted: List<PlottedSeries>, calibers: List<Caliber>) {
    val shown = remember(plotted) {
        plotted.map { Caliber.fromLabel(it.series.caliber) }.distinct().sortedBy { it.ordinal }
    }
    if (shown.size >= 2) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { caliber ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(10.dp).background(caliberColour(caliber, calibers), CircleShape))
                    Text(
                        caliber.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            false to (MEAN_MARK to Res.string.stats_legend_mean),
            true to (MEDIAN_MARK to Res.string.stats_legend_median),
        ).forEach { (diagonal, it) ->
            val (colour, label) = it
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same draw call as on the target, so the symbols match exactly.
                // Wide enough that the diagonal arms aren't clipped by the bounds.
                Canvas(modifier = Modifier.size(MARK_ARM * 4)) {
                    drawMark(center, colour, diagonal)
                }
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The series timeline: a bar from the oldest series (left) to the newest (right), a dot
 * per series ([PlottedSeries.age]), and one knob on series [knob]. Tapping the knob
 * switches it [on] (the target and measurements above show only that series) or off
 * (all of [plotted]); dragging it, or tapping elsewhere on the bar, moves it to the
 * nearest series and switches it on, since moving it means looking at that series.
 */
@Composable
internal fun AgeLegend(plotted: List<PlottedSeries>, knob: Int, on: Boolean, onKnob: (index: Int, on: Boolean) -> Unit) {
    if (plotted.isEmpty()) return
    val stamp = @Composable { s: PlottedSeries ->
        Text(
            localStamp(s.series.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Off: the span on show; on: the one series (localStamp carries the time of day,
        // so two series on the same date still read apart).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (on) Arrangement.Center else Arrangement.SpaceBetween,
        ) {
            if (on) {
                stamp(plotted[knob])
            } else {
                stamp(plotted.first())
                stamp(plotted.last())
            }
        }
        val track = MaterialTheme.colorScheme.surfaceVariant
        val dot = MaterialTheme.colorScheme.onSurfaceVariant
        val accent = MaterialTheme.colorScheme.primary
        val muted = MaterialTheme.colorScheme.surface
        val outline = MaterialTheme.colorScheme.outline
        // The gestures are not restarted per recomposition, so they read these.
        val current by rememberUpdatedState(knob to on)
        val count = plotted.size
        val timeline = stringResource(Res.string.stats_timeline)
        val knobStamp = localStamp(plotted[knob].series.timestamp)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(KNOB_TOUCH)
                // A slider to a screen reader: swipe up/down steps through the series.
                .semantics {
                    contentDescription = timeline
                    stateDescription = knobStamp
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        knob.toFloat(), 0f..(count - 1).coerceAtLeast(0).toFloat(), (count - 2).coerceAtLeast(0),
                    )
                    setProgress { v -> onKnob(v.roundToInt().coerceIn(0, count - 1), true); true }
                }
                .pointerInput(count) {
                    // The knob's centre travels between the insets, so it never overhangs the bar.
                    val inset = KNOB.width.toPx() / 2f
                    fun indexAt(x: Float) = knobIndex(x - inset, size.width - 2f * inset, count)
                    fun knobX(i: Int) = inset + (size.width - 2f * inset) * (if (count > 1) i.toFloat() / (count - 1) else 0f)
                    detectTapGestures { at ->
                        val (k, wasOn) = current
                        if (abs(at.x - knobX(k)) <= KNOB_TOUCH.toPx() / 2f) {
                            onKnob(k, !wasOn)
                        } else {
                            onKnob(indexAt(at.x), true)
                        }
                    }
                }
                .pointerInput(count) {
                    val inset = KNOB.width.toPx() / 2f
                    fun indexAt(x: Float) = knobIndex(x - inset, size.width - 2f * inset, count)
                    // Horizontal only, so a vertical swipe over the bar still scrolls the page.
                    detectHorizontalDragGestures(
                        onDragStart = { onKnob(indexAt(it.x), true) },
                    ) { change, _ ->
                        change.consume()
                        onKnob(indexAt(change.position.x), true)
                    }
                },
        ) {
            val inset = KNOB.width.toPx() / 2f
            val span = size.width - 2f * inset
            val mid = size.height / 2f
            val barHeight = 8.dp.toPx()
            drawRoundRect(
                track,
                topLeft = Offset(inset, mid - barHeight / 2f),
                size = Size(span, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f),
            )
            val dotRadius = 2.dp.toPx()
            plotted.forEach { s ->
                drawCircle(dot, radius = dotRadius, center = Offset(inset + span * s.age, mid))
            }
            // On: a filled accent knob; off: a hollow one, so the state reads at a glance.
            val at = Offset(inset + span * plotted[knob].age, mid)
            drawCircle(if (on) accent else muted, radius = inset, center = at)
            drawCircle(if (on) accent else outline, radius = inset - 1.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
        }
    }
}

/** The series index nearest [x] along a bar [width] wide holding [count] evenly spaced series. */
internal fun knobIndex(x: Float, width: Float, count: Int): Int =
    if (count <= 1 || width <= 0f) 0 else (x / width * (count - 1)).roundToInt().coerceIn(0, count - 1)

/** What Tavla draws: the knob's one series when it is [on], else every series in [plotted]. */
internal fun <T> knobSelection(plotted: List<T>, knob: Int, on: Boolean): List<T> =
    if (on) listOfNotNull(plotted.getOrNull(knob)) else plotted
