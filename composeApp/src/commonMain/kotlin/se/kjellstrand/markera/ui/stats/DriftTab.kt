package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.stats.DayImpact

/**
 * Old to new: blue, yellow, red. Bright enough to read on the black, and the dark halo
 * under every mark and line keeps the yellow readable on the paper.
 */
private val DRIFT_COLOURS = listOf(Color(0xFF2979FF), Color(0xFFFFD600), Color(0xFFFF3D00))

/** A day's colour: [age] 0 (the oldest day shown) to 1 (the newest) along [DRIFT_COLOURS]. */
internal fun driftColour(age: Float): Color {
    val a = age.coerceIn(0f, 1f)
    return if (a <= 0.5f) lerp(DRIFT_COLOURS[0], DRIFT_COLOURS[1], a * 2f) else lerp(DRIFT_COLOURS[1], DRIFT_COLOURS[2], (a - 0.5f) * 2f)
}

/**
 * How many mm from the centre the canvas shows: the farthest point with some room around
 * it, never tighter than the 9 ring's outside nor wider than the whole target. Points of
 * impact sit near the centre, so the full target would squeeze them into a speck.
 */
internal fun driftViewMm(days: List<DayImpact>): Float {
    val far = days.maxOfOrNull { maxOf(hypot(it.meanXMm, it.meanYMm), hypot(it.medianXMm, it.medianYMm)) } ?: 0.0
    return (far.toFloat() * 1.3f + 10f).coerceIn(MIN_VIEW_MM, PLOT_RADIUS_MM)
}

private const val MIN_VIEW_MM = 60f
private val MEAN_DOT = 5.dp
private val MEDIAN_RING = 5.dp
private val LINE = 1.5.dp
private val HALO = 1.5.dp

/**
 * The Drift tab: one mean point (filled dot) and one median point (ring) per day, joined
 * in date order by a thin line (solid / dashed), coloured from the oldest day to the newest.
 * [days] comes from `dailyImpact`; at least one of [showMean] / [showMedian] stays on.
 */
@Composable
internal fun DriftTab(
    days: List<DayImpact>,
    seriesCount: Int,
    showMean: Boolean,
    showMedian: Boolean,
    onShow: (mean: Boolean, median: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DriftCanvas(days, showMean, showMedian)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Switching off the one still on would leave an empty target, so that chip just stays on.
            FilterChip(
                selected = showMean,
                onClick = { if (!showMean || showMedian) onShow(!showMean, showMedian) },
                label = { Text(stringResource(Res.string.stats_legend_mean)) },
                leadingIcon = { Symbol(median = false) },
            )
            FilterChip(
                selected = showMedian,
                onClick = { if (!showMedian || showMean) onShow(showMean, !showMedian) },
                label = { Text(stringResource(Res.string.stats_legend_median)) },
                leadingIcon = { Symbol(median = true) },
            )
        }
        AgeKey(days)
        Text(
            stringResource(Res.string.stats_drift_counts, days.size, seriesCount, days.sumOf { it.hitCount }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DriftCanvas(days: List<DayImpact>, showMean: Boolean, showMedian: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val viewMm = remember(days) { driftViewMm(days) }
    // New data starts unzoomed again.
    var zoom by remember(days) { mutableStateOf(1f) }
    var pan by remember(days) { mutableStateOf(Offset.Zero) }
    val description = stringResource(Res.string.stats_drift_description, days.size)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(days) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) }
            .pointerInput(days) {
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
        val means = days.map { at(it.meanXMm, it.meanYMm) }
        val medians = days.map { at(it.medianXMm, it.medianYMm) }
        // Lines under the marks, and the newest day's marks on top of the older ones.
        if (showMean) drawPath(days, means, k, null)
        if (showMedian) drawPath(days, medians, k, dash)
        days.forEachIndexed { i, day ->
            val colour = driftColour(day.age)
            if (showMean) {
                drawCircle(BLACK, radius = (MEAN_DOT + HALO).toPx() * k, center = means[i])
                drawCircle(colour, radius = MEAN_DOT.toPx() * k, center = means[i])
            }
            if (showMedian) {
                drawCircle(BLACK, radius = MEDIAN_RING.toPx() * k, center = medians[i], style = Stroke((2.dp + HALO * 2).toPx() * k))
                drawCircle(colour, radius = MEDIAN_RING.toPx() * k, center = medians[i], style = Stroke(2.dp.toPx() * k))
            }
        }
    }
}

/** The day-to-day line: each segment in the colour halfway between its two days, over a dark halo. */
private fun DrawScope.drawPath(days: List<DayImpact>, points: List<Offset>, k: Float, effect: PathEffect?) {
    for (i in 1 until points.size) {
        val colour = driftColour((days[i - 1].age + days[i].age) / 2f)
        drawLine(BLACK.copy(alpha = 0.6f), points[i - 1], points[i], strokeWidth = (LINE + HALO).toPx() * k, pathEffect = effect)
        drawLine(colour, points[i - 1], points[i], strokeWidth = LINE.toPx() * k, pathEffect = effect)
    }
}

/** The chip's symbol: the same filled dot or ring as on the target, in the text colour. */
@Composable
private fun Symbol(median: Boolean) {
    val colour = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(MEAN_DOT * 3)) {
        if (median) {
            drawCircle(colour, radius = MEDIAN_RING.toPx(), style = Stroke(2.dp.toPx()))
        } else {
            drawCircle(colour, radius = MEAN_DOT.toPx())
        }
    }
}

/** The colour key: the gradient from the oldest day shown to the newest, dated at both ends. */
@Composable
private fun AgeKey(days: List<DayImpact>) {
    if (days.isEmpty()) return
    val label = @Composable { text: String ->
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Brush.horizontalGradient(DRIFT_COLOURS), RoundedCornerShape(4.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            label(stringResource(Res.string.stats_drift_oldest) + " " + days.first().date)
            if (days.size > 1) label(stringResource(Res.string.stats_drift_newest) + " " + days.last().date)
        }
    }
}
