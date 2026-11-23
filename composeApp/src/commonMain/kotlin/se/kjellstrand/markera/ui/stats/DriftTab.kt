package se.kjellstrand.markera.ui.stats

import se.kjellstrand.markera.ui.Segmented
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.stats.DriftPoint
import se.kjellstrand.markera.series.stats.smoothed
import se.kjellstrand.markera.ui.AppChip

/**
 * Old to new: a muted grey fading into a sharp blue, so the recent days stand out. The dark
 * halo under every mark and line keeps the grey readable on the paper.
 */
private val DRIFT_COLOURS = listOf(Color(0xFFA0A4AA), Color(0xFF1E6BFF))

/** A point's colour: [age] 0 (the oldest point shown) to 1 (the newest) along [DRIFT_COLOURS]. */
internal fun driftColour(age: Float): Color = lerp(DRIFT_COLOURS[0], DRIFT_COLOURS[1], age.coerceIn(0f, 1f))

/**
 * How many mm from the centre the canvas shows: the farthest point with some room around
 * it, never tighter than the 9 ring's outside nor wider than the whole target. Points of
 * impact sit near the centre, so the full target would squeeze them into a speck.
 */
internal fun driftViewMm(days: List<DriftPoint>): Float {
    val far = days.maxOfOrNull { maxOf(hypot(it.meanXMm, it.meanYMm), hypot(it.medianXMm, it.medianYMm)) } ?: 0.0
    return (far.toFloat() * 1.3f + 10f).coerceIn(MIN_VIEW_MM, PLOT_RADIUS_MM)
}

/** How opaque point [i] is drawn with [selected] picked: full on it, fading back in time, faint after it. */
internal fun driftAlpha(i: Int, selected: Int): Float = when {
    i > selected -> 0.16f
    i == selected -> 1f
    else -> 0.45f + 0.5f * i / selected
}

private const val MIN_VIEW_MM = 60f
private val DOT = 3.5.dp
private val DOT_SELECTED = 6.5.dp
private val RING = 4.dp
private val RING_SELECTED = 6.dp
private val LINE = 1.5.dp
private val HALO = 1.5.dp
private val TAP_REACH = 24.dp
private val KNOB = 22.dp
private val PILL = Color(0xE6073042)

/**
 * The Drift tab: one mean point (filled dot) and one median point (ring) per day, or per
 * series with [perSeries], joined in time order by a thin line (solid / dashed), coloured
 * from the oldest point to the newest. [points] comes from `dailyImpact` / `seriesImpact`;
 * at least one of [showMean] / [showMedian] stays on. One point is always picked (the
 * newest to begin with): the slider or a tap on the target moves it, the card reads it out.
 */
@Composable
internal fun DriftTab(
    points: List<DriftPoint>,
    perSeries: Boolean,
    onPerSeries: (Boolean) -> Unit,
    smooth: Boolean,
    onSmooth: (Boolean) -> Unit,
    showMean: Boolean,
    showMedian: Boolean,
    onShow: (mean: Boolean, median: Boolean) -> Unit,
) {
    if (points.isEmpty()) return
    val smoothing = perSeries && smooth
    val shown = remember(points, smoothing) { if (smoothing) points.smoothed() else points }
    // Keyed on the raw points, so smoothing keeps the pick; new data parks it on the newest.
    var picked by remember(points) { mutableIntStateOf(points.lastIndex) }
    val selected = picked.coerceIn(0, shown.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(summary(shown), style = MaterialTheme.typography.titleSmall)
        DriftCanvas(points, shown, selected, showMean, showMedian) { picked = it }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(
                options = listOf(stringResource(Res.string.stats_drift_per_day), stringResource(Res.string.stats_drift_per_series)),
                selected = if (perSeries) 1 else 0,
                onSelect = { onPerSeries(it == 1) },
            )
            // Switching off the one still on would leave an empty target, so that chip just stays on.
            AppChip(
                selected = showMean,
                onClick = { if (!showMean || showMedian) onShow(!showMean, showMedian) },
                label = stringResource(Res.string.stats_legend_mean),
                leadingIcon = { Symbol(median = false) },
            )
            AppChip(
                selected = showMedian,
                onClick = { if (!showMedian || showMean) onShow(showMean, !showMedian) },
                label = stringResource(Res.string.stats_legend_median),
                leadingIcon = { Symbol(median = true) },
            )
            // One series is a handful of shots; a day's worth is steady enough as it is.
            if (perSeries) {
                AppChip(selected = smooth, onClick = { onSmooth(!smooth) }, label = stringResource(Res.string.stats_drift_smooth))
            }
        }
        TimeSlider(shown, selected) { picked = it }
        Readout(shown, selected, perSeries, smoothing)
        Strips(shown, selected, useMedian = !showMean)
    }
}

/** "8 mm low, 5 mm right": the offset from the centre the way a shooter says it (y grows downwards). */
@Composable
private fun offsetText(xMm: Double, yMm: Double): String {
    val x = xMm.roundToInt()
    val y = yMm.roundToInt()
    val parts = listOfNotNull(
        when {
            y > 0 -> stringResource(Res.string.stats_drift_low, y)
            y < 0 -> stringResource(Res.string.stats_drift_high, -y)
            else -> null
        },
        when {
            x > 0 -> stringResource(Res.string.stats_drift_right, x)
            x < 0 -> stringResource(Res.string.stats_drift_left, -x)
            else -> null
        },
    )
    return if (parts.isEmpty()) stringResource(Res.string.stats_drift_on_centre) else parts.joinToString(", ")
}

/** Where the newest mean point sits, and how its distance from the centre compares with the oldest's. */
@Composable
private fun summary(points: List<DriftPoint>): String {
    val first = points.first()
    val last = points.last()
    val where = offsetText(last.meanXMm, last.meanYMm)
    val out = hypot(last.meanXMm, last.meanYMm)
    val moved = (hypot(first.meanXMm, first.meanYMm) - out).roundToInt()
    val since = first.date.toString()
    return when {
        points.size < 2 -> stringResource(Res.string.stats_drift_summary_one, where, out.roundToInt())
        moved > 0 -> stringResource(Res.string.stats_drift_summary_closer, where, out.roundToInt(), moved, since)
        moved < 0 -> stringResource(Res.string.stats_drift_summary_further, where, out.roundToInt(), -moved, since)
        else -> stringResource(Res.string.stats_drift_summary_same, where, out.roundToInt(), since)
    }
}

@Composable
private fun DriftCanvas(
    raw: List<DriftPoint>,
    points: List<DriftPoint>,
    selected: Int,
    showMean: Boolean,
    showMedian: Boolean,
    onPick: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    // From the raw points: smoothing only pulls points inwards, and the view should not jump with it.
    val viewMm = remember(raw) { driftViewMm(raw) }
    // New data starts unzoomed again.
    var zoom by remember(raw) { mutableStateOf(1f) }
    var pan by remember(raw) { mutableStateOf(Offset.Zero) }
    val description = pluralStringResource(Res.plurals.stats_drift_description, points.size, points.size)
    val latest = stringResource(Res.string.stats_drift_latest)
    // The gestures are not restarted per recomposition, so they read these.
    val current by rememberUpdatedState(points to showMean)
    val currentOnPick by rememberUpdatedState(onPick)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(raw) {
                detectTapGestures(
                    onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                    onTap = { tap ->
                        // The layer is scaled about its top-left by `zoom` and shifted by `pan`.
                        val (shown, mean) = current
                        val scale = min(size.width, size.height) / 2f / viewMm
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val nearest = shown.indices.minByOrNull { i ->
                            val p = shown[i]
                            val at = Offset(
                                centre.x + (if (mean) p.meanXMm else p.medianXMm).toFloat() * scale,
                                centre.y + (if (mean) p.meanYMm else p.medianYMm).toFloat() * scale,
                            ) * zoom + pan
                            (at - tap).getDistance()
                        }
                        if (nearest != null) {
                            val p = shown[nearest]
                            val at = Offset(
                                centre.x + (if (mean) p.meanXMm else p.medianXMm).toFloat() * scale,
                                centre.y + (if (mean) p.meanYMm else p.medianYMm).toFloat() * scale,
                            ) * zoom + pan
                            if ((at - tap).getDistance() <= TAP_REACH.toPx()) currentOnPick(nearest)
                        }
                    },
                )
            }
            .pointerInput(raw) {
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
        val scale = min(size.width, size.height) / 2f / viewMm
        drawTargetFace(textMeasurer, scale)
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun at(xMm: Double, yMm: Double) = Offset(centre.x + xMm.toFloat() * scale, centre.y + yMm.toFloat() * scale)
        // The layer scales everything; marks and lines keep their screen size.
        val k = 1f / zoom
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx() * k, 4.dp.toPx() * k))
        val means = points.map { at(it.meanXMm, it.meanYMm) }
        val medians = points.map { at(it.medianXMm, it.medianYMm) }
        val main = if (showMean) means else medians

        // The net movement, oldest point to the picked one.
        if (selected > 0) {
            val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx() * k, 4.dp.toPx() * k))
            drawLine(BLACK.copy(alpha = 0.3f), main[0], main[selected], strokeWidth = 2.5.dp.toPx() * k)
            drawLine(LINE_ON_BLACK.copy(alpha = 0.5f), main[0], main[selected], strokeWidth = 1.dp.toPx() * k, pathEffect = dots)
        }
        // Lines under the marks, up to the picked point, fading back in time.
        if (showMedian) drawTrail(points, medians, selected, k, dash, arrows = false)
        if (showMean) drawTrail(points, means, selected, k, null, arrows = true)
        points.forEachIndexed { i, point ->
            val alpha = driftAlpha(i, selected)
            val colour = driftColour(point.age).copy(alpha = alpha)
            val halo = BLACK.copy(alpha = alpha)
            val on = i == selected
            if (showMedian) {
                val r = (if (on) RING_SELECTED else RING).toPx() * k
                drawCircle(halo, radius = r, center = medians[i], style = Stroke((2.dp + HALO * 2).toPx() * k))
                drawCircle(colour, radius = r, center = medians[i], style = Stroke(2.dp.toPx() * k))
            }
            if (showMean) {
                val r = (if (on) DOT_SELECTED else DOT).toPx() * k
                drawCircle(halo, radius = r + HALO.toPx() * k, center = means[i])
                drawCircle(colour, radius = r, center = means[i])
            }
        }
        // The picked point: a ring around it and its name beside it.
        val p = main[selected]
        drawCircle(BLACK.copy(alpha = 0.5f), radius = 11.dp.toPx() * k, center = p, style = Stroke(3.dp.toPx() * k))
        drawCircle(LINE_ON_BLACK, radius = 11.dp.toPx() * k, center = p, style = Stroke(1.5.dp.toPx() * k))
        val name = if (selected == points.lastIndex) latest else points[selected].date.toString()
        drawNameTag(textMeasurer, name, p, k)
    }
}

/**
 * The line from point to point up to [selected]: each segment in the colour halfway between
 * its two points, over a dark halo, more see-through the older it is; with [arrows], a small
 * arrowhead on each segment long enough to carry one shows which way time runs.
 */
private fun DrawScope.drawTrail(
    days: List<DriftPoint>,
    points: List<Offset>,
    selected: Int,
    k: Float,
    effect: PathEffect?,
    arrows: Boolean,
) {
    for (i in 1..selected) {
        val t = i.toFloat() / selected
        val alpha = 0.25f + 0.75f * t * t
        val colour = driftColour((days[i - 1].age + days[i].age) / 2f).copy(alpha = alpha)
        val from = points[i - 1]
        val to = points[i]
        drawLine(BLACK.copy(alpha = 0.6f * alpha), from, to, strokeWidth = (LINE + HALO).toPx() * k, pathEffect = effect)
        drawLine(colour, from, to, strokeWidth = LINE.toPx() * k, pathEffect = effect)
        if (arrows && (to - from).getDistance() >= 18.dp.toPx() * k) {
            val mid = (from + to) / 2f
            val degrees = atan2(to.y - from.y, to.x - from.x) * 180f / kotlin.math.PI.toFloat()
            val a = 4.5.dp.toPx() * k
            val head = Path().apply {
                moveTo(a, 0f); lineTo(-a, -a * 0.8f); lineTo(-a, a * 0.8f); close()
            }
            withTransform({ translate(mid.x, mid.y); rotate(degrees, pivot = Offset.Zero) }) {
                drawPath(head, BLACK.copy(alpha = alpha), style = Stroke(1.5.dp.toPx() * k, join = StrokeJoin.Round))
                drawPath(head, colour)
            }
        }
    }
}

/** A small dark pill with [text] beside [at], kept on the canvas and at screen size whatever the zoom. */
private fun DrawScope.drawNameTag(textMeasurer: TextMeasurer, text: String, at: Offset, k: Float) {
    val layout = textMeasurer.measure(text, TextStyle(color = LINE_ON_BLACK, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
    val padX = 7.dp.toPx()
    val padY = 3.dp.toPx()
    val w = layout.size.width + 2 * padX
    val h = layout.size.height + 2 * padY
    val gap = 14.dp.toPx()
    // Drawn in screen pixels around the point, then scaled back by the layer's zoom.
    val right = at.x + (gap + w) * k <= size.width
    val above = at.y - (gap + h) * k >= 0f
    val left = if (right) gap else -gap - w
    val top = if (above) -gap - h else gap
    withTransform({ translate(at.x, at.y); scale(k, k, pivot = Offset.Zero) }) {
        drawRoundRect(PILL, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(h / 2f))
        drawText(layout, topLeft = Offset(left + padX, top + padY))
    }
}

/** The chip's symbol: the same filled dot or ring as on the target, in the chip's content colour. */
@Composable
private fun Symbol(median: Boolean) {
    val colour = LocalContentColor.current
    Canvas(Modifier.size(15.dp)) {
        if (median) {
            drawCircle(colour, radius = 5.dp.toPx(), style = Stroke(2.dp.toPx()))
        } else {
            drawCircle(colour, radius = 5.dp.toPx())
        }
    }
}

/**
 * The colour key as a slider: the gradient from the oldest point to the newest with a knob
 * on the picked one. Dragging, or tapping the bar, picks the nearest point.
 */
@Composable
private fun TimeSlider(points: List<DriftPoint>, selected: Int, onPick: (Int) -> Unit) {
    val count = points.size
    val knob = MaterialTheme.colorScheme.onSurface
    val rim = MaterialTheme.colorScheme.background
    val name = stringResource(Res.string.stats_drift_slider)
    val state = points[selected].date.toString()
    val currentOnPick by rememberUpdatedState(onPick)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                // A slider to a screen reader: swipe up/down steps through the points.
                .semantics {
                    contentDescription = name
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        selected.toFloat(), 0f..(count - 1).coerceAtLeast(0).toFloat(), (count - 2).coerceAtLeast(0),
                    )
                    setProgress { v -> currentOnPick(v.roundToInt().coerceIn(0, count - 1)); true }
                }
                .pointerInput(count) {
                    val inset = KNOB.toPx() / 2f
                    detectTapGestures { currentOnPick(knobIndex(it.x - inset, size.width - 2f * inset, count)) }
                }
                .pointerInput(count) {
                    val inset = KNOB.toPx() / 2f
                    fun indexAt(x: Float) = knobIndex(x - inset, size.width - 2f * inset, count)
                    // Horizontal only, so a vertical swipe over the bar still scrolls the page.
                    detectHorizontalDragGestures(onDragStart = { currentOnPick(indexAt(it.x)) }) { change, _ ->
                        change.consume()
                        currentOnPick(indexAt(change.position.x))
                    }
                },
        ) {
            // The knob's centre travels between the insets, so it never overhangs the bar.
            val inset = KNOB.toPx() / 2f
            val span = size.width - 2f * inset
            val mid = size.height / 2f
            val bar = 8.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(DRIFT_COLOURS, startX = inset, endX = inset + span),
                topLeft = Offset(inset, mid - bar / 2f),
                size = Size(span, bar),
                cornerRadius = CornerRadius(bar / 2f),
            )
            val at = Offset(inset + span * (if (count > 1) selected.toFloat() / (count - 1) else 1f), mid)
            drawCircle(rim, radius = inset, center = at)
            drawCircle(knob, radius = inset - 3.dp.toPx(), center = at)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.bodySmall
            val colour = MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(Res.string.stats_drift_oldest) + " " + points.first().date, style = style, color = colour)
            if (count > 1) Text(stringResource(Res.string.stats_drift_newest) + " " + points.last().date, style = style, color = colour)
        }
    }
}

/** The picked point spelled out: when, how much shooting is behind it, and both offsets. */
@Composable
private fun Readout(points: List<DriftPoint>, selected: Int, perSeries: Boolean, smoothed: Boolean) {
    val p = points[selected]
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = if (perSeries) {
                pluralStringResource(Res.plurals.stats_drift_point_series, p.hitCount, p.date.toString(), selected + 1, points.size, p.hitCount)
            } else {
                pluralStringResource(
                    Res.plurals.stats_drift_point_day,
                    p.seriesCount,
                    p.date.toString(),
                    p.seriesCount,
                    pluralStringResource(Res.plurals.stats_drift_point_day_hits, p.hitCount, p.hitCount),
                )
            }
            Text(
                if (smoothed) title + " · " + stringResource(Res.string.stats_drift_smoothed) else title,
                style = MaterialTheme.typography.labelLarge,
            )
            val line = @Composable { label: String, x: Double, y: Double ->
                Text(
                    stringResource(Res.string.stats_drift_value, label, offsetText(x, y)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            line(stringResource(Res.string.stats_legend_mean), p.meanXMm, p.meanYMm)
            line(stringResource(Res.string.stats_legend_median), p.medianXMm, p.medianYMm)
        }
    }
}

/**
 * The drift split in two: left/right and high/low over time, the points evenly spaced like
 * on the slider. On the target a long path tangles; here a steady creep one way shows.
 */
@Composable
private fun Strips(points: List<DriftPoint>, selected: Int, useMedian: Boolean) {
    if (points.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(if (useMedian) Res.string.stats_drift_strips_median else Res.string.stats_drift_strips),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val xs = points.map { if (useMedian) it.medianXMm else it.meanXMm }
        val ys = points.map { if (useMedian) it.medianYMm else it.meanYMm }
        // One scale for both, so a millimetre sideways looks like a millimetre up or down.
        val reach = maxOf(30.0, (xs + ys).maxOf { abs(it) } * 1.1).toFloat()
        Strip(xs, selected, reach, stringResource(Res.string.stats_drift_axis_left), stringResource(Res.string.stats_drift_axis_right))
        Strip(ys, selected, reach, stringResource(Res.string.stats_drift_axis_high), stringResource(Res.string.stats_drift_axis_low))
    }
}

/** One strip: [values] in mm around a zero line, negative towards [top], with the picked point marked. */
@Composable
private fun Strip(values: List<Double>, selected: Int, reach: Float, top: String, bottom: String) {
    val zero = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.onSurface
    val rim = MaterialTheme.colorScheme.background
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(52.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(top, style = MaterialTheme.typography.labelSmall, color = guide, maxLines = 1)
            Text(bottom, style = MaterialTheme.typography.labelSmall, color = guide, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val pad = 5.dp.toPx()
            val mid = size.height / 2f
            fun x(i: Int) = pad + (size.width - 2f * pad) * i / (values.size - 1)
            fun y(v: Double) = mid + (v.toFloat() / reach).coerceIn(-1f, 1f) * (mid - pad)
            drawLine(zero, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
            val path = Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            drawPath(path, DRIFT_COLOURS[1], style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val at = Offset(x(selected), y(values[selected]))
            drawLine(
                guide, Offset(at.x, 0f), Offset(at.x, size.height), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
            drawCircle(rim, radius = 5.dp.toPx(), center = at)
            drawCircle(mark, radius = 3.5.dp.toPx(), center = at)
        }
    }
}
