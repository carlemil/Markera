package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.kjellstrand.markera.series.stats.TrendPoint
import se.kjellstrand.markera.series.stats.fit

/** One series of marks on the chart: a caliber (or the whole selection) over time. */
data class TrendLine(val label: String, val colour: Color, val points: List<TrendPoint>)

/**
 * Categorical colours in fixed [se.kjellstrand.markera.series.Caliber] order, so a
 * caliber keeps its colour whatever the filter leaves in. Validated CVD-safe on the
 * dark surface (the dataviz reference palette's dark column).
 */
val TREND_COLOURS = listOf(
    Color(0xFF3987E5), // blue
    Color(0xFFD95926), // orange
    Color(0xFF199E70), // aqua
    Color(0xFFC98500), // yellow
    Color(0xFFD55181), // magenta
    Color(0xFF008300), // green
    Color(0xFF9085E9), // violet
    Color(0xFFE66767), // red
)

private val CHART_HEIGHT = 220.dp
private val Y_GUTTER = 52.dp
private val X_GUTTER = 20.dp
private val MARK = 5.dp
private val FIT_LINE = 2.dp
private val MEAN_LINE = 1.dp
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * A scatterplot of [lines] over time with a recessive grid, first/last date on the
 * x axis and [format]ted ticks on the y axis. Each series of marks gets its
 * least-squares trend line, a dashed mean and a ±1 SD band. Tap near a point to read it off
 * ([pointText] words it); the legend only appears with two or more lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrendChart(
    lines: List<TrendLine>,
    format: (Double) -> String,
    pointText: (TrendLine, TrendPoint) -> String,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = MaterialTheme.typography.labelSmall.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val gridColour = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val ringColour = MaterialTheme.colorScheme.background
    var selected by remember(lines) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Pixel positions of every point, kept from the last draw so a tap can find the nearest.
    val placed = remember(lines) { mutableStateOf<List<Triple<Int, Int, Offset>>>(emptyList()) }

    val all = lines.flatMap { it.points }
    if (all.isEmpty()) return
    val ticks = remember(lines) { niceTicks(all.minOf { it.value }, all.maxOf { it.value }) }
    val xMin = all.minOf { it.at.toEpochMilliseconds() }
    val xMax = all.maxOf { it.at.toEpochMilliseconds() }.let { if (it == xMin) it + DAY_MS else it }
    val yMin = ticks.first()
    val yMax = ticks.last()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .pointerInput(lines) {
                    detectTapGestures { tap ->
                        selected = placed.value
                            .minByOrNull { (_, _, at) -> hypot(tap.x - at.x, tap.y - at.y) }
                            ?.let { (line, point, _) -> line to point }
                    }
                },
        ) {
            val left = Y_GUTTER.toPx()
            val bottom = size.height - X_GUTTER.toPx()
            val top = MARK.toPx() * 2
            val right = size.width - MARK.toPx() * 2
            fun x(ms: Long) = left + (ms - xMin).toFloat() / (xMax - xMin) * (right - left)
            fun y(v: Double) = (bottom - ((v - yMin) / (yMax - yMin) * (bottom - top))).toFloat()

            // Recessive grid with its tick label to the left.
            for (tick in ticks) {
                val py = y(tick)
                drawLine(gridColour, Offset(left, py), Offset(right, py), 1.dp.toPx())
                val text = textMeasurer.measure(format(tick), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), py - text.size.height / 2))
            }
            // First and last date under the axis.
            val first = textMeasurer.measure(day(xMin), labelStyle)
            val last = textMeasurer.measure(day(xMax), labelStyle)
            drawText(first, topLeft = Offset(left, bottom + 4.dp.toPx()))
            drawText(last, topLeft = Offset(right - last.size.width, bottom + 4.dp.toPx()))

            // Per series of marks, under them: a ±1 SD band, the mean (dashed) and the trend (solid).
            val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            for (line in lines) {
                val fit = line.points.fit() ?: continue
                val faded = line.colour.copy(alpha = 0.7f)
                val meanY = y(fit.mean)
                // Clamped to the plot: the axis spans the points, and a band can poke past them.
                val bandTop = y(fit.mean + fit.sd).coerceIn(top, bottom)
                val bandBottom = y(fit.mean - fit.sd).coerceIn(top, bottom)
                drawRect(
                    line.colour.copy(alpha = 0.12f),
                    topLeft = Offset(left, bandTop),
                    size = Size(right - left, bandBottom - bandTop),
                )
                drawLine(faded, Offset(left, meanY), Offset(right, meanY), MEAN_LINE.toPx(), pathEffect = dashes)
                val x0 = line.points.first().at
                val x1 = line.points.last().at
                drawLine(
                    faded,
                    Offset(x(x0.toEpochMilliseconds()), y(fit.at(x0))),
                    Offset(x(x1.toEpochMilliseconds()), y(fit.at(x1))),
                    FIT_LINE.toPx(),
                )
            }
            val positions = lines.flatMapIndexed { li, line ->
                line.points.mapIndexed { pi, p -> Triple(li, pi, Offset(x(p.at.toEpochMilliseconds()), y(p.value))) }
            }
            for ((li, pi, at) in positions) {
                val hit = selected == li to pi
                // A surface ring so overlapping marks from two lines stay apart.
                drawCircle(ringColour, MARK.toPx() + 1.dp.toPx(), at)
                drawCircle(lines[li].colour, if (hit) MARK.toPx() * 1.6f else MARK.toPx(), at)
                if (hit) drawLine(lines[li].colour, Offset(at.x, top), Offset(at.x, bottom), 1.dp.toPx())
            }
            placed.value = positions
        }
        Text(
            text = selected?.let { (li, pi) -> pointText(lines[li], lines[li].points[pi]) } ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (lines.size >= 2) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                lines.forEach { line ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(line.colour, CircleShape))
                        Text(
                            line.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun day(ms: Long): String =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/** 3-6 round tick values spanning [lo]..[hi]; a flat line still gets a band around it. */
internal fun niceTicks(lo: Double, hi: Double): List<Double> {
    var min = lo
    var max = hi
    if (max - min < 1e-9) {
        min -= 1.0
        max += 1.0
    }
    val raw = (max - min) / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { (max - min) / it <= 5.5 }
    val start = floor(min / step) * step
    val end = ceil(max / step) * step
    val count = ((end - start) / step + 0.5).toInt()
    return List(count + 1) { i -> (start + i * step).let { if (abs(it) < step / 1e6) 0.0 else it } }
}
