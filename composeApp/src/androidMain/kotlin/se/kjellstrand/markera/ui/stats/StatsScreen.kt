package se.kjellstrand.markera.ui.stats

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.R
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.SeriesStatistics
import se.kjellstrand.markera.series.stats.StatsFilter
import se.kjellstrand.markera.series.stats.plotSeries
import se.kjellstrand.markera.series.stats.statistics
import se.kjellstrand.markera.ui.competition.CompetitionTopBar
import se.kjellstrand.markera.ui.history.localStamp
import se.kjellstrand.markera.vision.INNER_TEN_RADIUS_MM
import se.kjellstrand.markera.vision.RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM

/** Ring 5's outer edge — the whole drawn target, and the canvas' mm half-width. */
private const val PLOT_RADIUS_MM = 150f

private val PAPER = Color(0xFFE8DEC8)
private val BLACK = Color(0xFF15151A)
private val LINE_ON_BLACK = Color(0xFFEDEDED)
private val LINE_ON_PAPER = Color(0xFF6B6455)
/**
 * Oldest → newest hit colours, also the legend's gradient bar. Saturated the whole
 * way so every stop stands out against both the cream paper and the black centre.
 */
private val HIT_SCALE = listOf(
    Color(0xFF7C4DFF), // violet
    Color(0xFF00B0FF), // blue
    Color(0xFF00E5CC), // cyan
    Color(0xFFFFD600), // yellow
    Color(0xFFFF3D00), // orange-red
)
private val MEAN_MARK = Color(0xFFFFC107)
private val MEDIAN_MARK = Color(0xFFFF4081)

/** Marker geometry, shared by the target and the legend (see [drawMark]). */
private val MARK_ARM = 5.dp
private val MARK_HALO = 2.dp
private val MARK_STROKE = 1.dp

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * All saved series with geometry, filtered and drawn on one target: every hit
 * un-projected to target millimetres, coloured old → new, with the group
 * measurements underneath. The maths lives in `series/stats/`; this only draws.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun StatsScreen(services: SeriesServices, onBack: () -> Unit) {
    val auth by services.session.auth.collectAsState()
    val series by services.repository.series.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var caliber by remember { mutableStateOf<Caliber?>(null) }
    var preset by remember { mutableStateOf(DatePreset.ALL) }
    // Custom window as the picker hands it over: UTC start-of-day millis.
    var customRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // null = "Alla": no hole-count filter, which is the default.
    var hits by remember { mutableStateOf<Int?>(null) }
    var pickingDates by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }

    // The cache holds every series already; opening only asks for the delta.
    LaunchedEffect(auth, reload) {
        if (auth == null) return@LaunchedEffect
        loading = true
        error = services.repository.refresh()?.let { it.message ?: it.toString() }
        loading = false
    }

    val filter = remember(caliber, preset, customRange, hits) {
        val range = customRange.takeIf { preset == DatePreset.CUSTOM }
        val from = range?.let { Instant.fromEpochMilliseconds(it.first) }
        val to = range?.let { Instant.fromEpochMilliseconds(it.second + DAY_MS - 1) }
        val (presetFrom, presetTo) = preset.range(Clock.System.now())
        StatsFilter(caliber = caliber, from = from ?: presetFrom, to = to ?: presetTo, hits = hits)
    }
    val plotted = remember(series, filter) { series.plotSeries(filter) }
    val stats = remember(plotted) { plotted.statistics() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            CompetitionTopBar(
                title = stringResource(R.string.stats_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showingHelp = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.stats_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            when {
                auth == null -> Centered { Text(stringResource(R.string.stats_signed_out)) }

                // Cached series still plot: an error only shows with nothing to draw.
                error != null && series.isEmpty() -> Centered {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { reload++ }) { Text(stringResource(R.string.stats_retry)) }
                    }
                }

                loading && series.isEmpty() -> Centered { CircularProgressIndicator() }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FilterRow(
                        calibers = series.calibersWithGeometry(),
                        caliber = caliber,
                        onCaliber = { caliber = it },
                        preset = preset,
                        customRange = customRange,
                        onPreset = { preset = it },
                        onPickDates = { pickingDates = true },
                        hits = hits,
                        onHits = { hits = it?.coerceIn(1, 20) },
                    )
                    if (stats == null) {
                        Text(
                            stringResource(R.string.stats_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TargetCanvas(plotted, stats)
                        AgeLegend(plotted)
                        MarkerLegend()
                        MeasurementRows(stats)
                    }
                }
            }
        }
    }

    if (pickingDates) {
        DateRangeDialog(
            onDismiss = { pickingDates = false },
            onPicked = { start, end ->
                customRange = start to end
                preset = DatePreset.CUSTOM
                pickingDates = false
            },
        )
    }
    if (showingHelp) HelpDialog(onDismiss = { showingHelp = false })
}

/** What every measurement under the target actually means. */
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.stats_help_close)) }
        },
        title = { Text(stringResource(R.string.stats_help)) },
        text = {
            // Scrolls inside the dialog: the list is taller than a phone screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    R.string.stats_series to R.string.stats_help_series,
                    R.string.stats_hits to R.string.stats_help_hits,
                    R.string.stats_mean_distance to R.string.stats_help_mean_distance,
                    R.string.stats_mean_pairwise to R.string.stats_help_mean_pairwise,
                    R.string.stats_group_size to R.string.stats_help_group_size,
                    R.string.stats_impact to R.string.stats_help_impact,
                    R.string.stats_impact_median to R.string.stats_help_impact_median,
                    R.string.stats_mean_score to R.string.stats_help_mean_score,
                    R.string.stats_tens_share to R.string.stats_help_tens_share,
                    R.string.stats_help_extremes to R.string.stats_help_extremes_body,
                    R.string.stats_help_colours to R.string.stats_help_colours_body,
                ).forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** The calibers actually worth offering: those on plottable (geometry-carrying) series. */
private fun List<SeriesDto>.calibersWithGeometry(): List<Caliber> =
    filter { it.geometry != null }
        .map { Caliber.fromLabel(it.caliber) }
        .filter { it != Caliber.NONE }
        .distinct()
        .sortedBy { it.ordinal }

@OptIn(ExperimentalLayoutApi::class, ExperimentalTime::class)
@Composable
private fun FilterRow(
    calibers: List<Caliber>,
    caliber: Caliber?,
    onCaliber: (Caliber?) -> Unit,
    preset: DatePreset,
    customRange: Pair<Long, Long>?,
    onPreset: (DatePreset) -> Unit,
    onPickDates: () -> Unit,
    hits: Int?,
    onHits: (Int?) -> Unit,
) {
    // One child of the caller's 16 dp column, so only the filter rows sit tight.
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = caliber == null,
                onClick = { onCaliber(null) },
                label = { Text(stringResource(R.string.stats_caliber_all)) },
            )
            calibers.forEach {
                FilterChip(
                    selected = caliber == it,
                    onClick = { onCaliber(it) },
                    label = { Text(it.label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DatePreset.ALL to R.string.stats_date_all,
                DatePreset.WEEK to R.string.stats_date_week,
                DatePreset.MONTH to R.string.stats_date_month,
                DatePreset.YEAR to R.string.stats_date_year,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = preset == value,
                    onClick = { onPreset(value) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = hits == null,
                onClick = { onHits(null) },
                label = { Text(stringResource(R.string.stats_caliber_all)) },
            )
            // From "Alla" either button picks up the usual five-shot series.
            OutlinedIconButton(
                onClick = { onHits(hits?.minus(1) ?: 5) },
                enabled = hits == null || hits > 1,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.stats_hits_fewer),
                )
            }
            OutlinedIconButton(
                onClick = { onHits(hits?.plus(1) ?: 5) },
                enabled = hits == null || hits < 20,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.stats_hits_more),
                )
            }
            Text(
                // Greyed at the count the buttons would land on while "Alla" is picked.
                stringResource(R.string.stats_hits_label, hits ?: 5),
                style = MaterialTheme.typography.bodyLarge,
                color = if (hits == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = preset == DatePreset.CUSTOM,
                onClick = onPickDates,
                label = {
                    Text(
                        if (preset == DatePreset.CUSTOM && customRange != null) {
                            stringResource(
                                R.string.stats_date_range,
                                utcDay(customRange.first),
                                utcDay(customRange.second),
                            )
                        } else {
                            stringResource(R.string.stats_date_custom)
                        },
                    )
                },
            )
        }
    }
}

/** `yyyy-MM-dd` of a UTC start-of-day millis, which is what the range picker returns. */
private fun utcDay(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(onDismiss: () -> Unit, onPicked: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            val end = state.selectedEndDateMillis
            TextButton(
                onClick = { if (start != null && end != null) onPicked(start, end) },
                enabled = start != null && end != null,
            ) { Text(stringResource(R.string.stats_date_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.stats_date_cancel)) }
        },
    ) {
        // Weighted so the tall range picker scrolls inside the dialog instead of
        // pushing the buttons off a short screen.
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

/**
 * The drawn target out to ring 5 with every filtered hit on it. Offsets are in
 * image axes (y down), so they map straight onto canvas coordinates.
 */
@Composable
private fun TargetCanvas(plotted: List<PlottedSeries>, stats: SeriesStatistics?) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        val scale = min(size.width, size.height) / 2f / PLOT_RADIUS_MM
        val centre = Offset(size.width / 2f, size.height / 2f)
        fun r(mm: Double) = mm.toFloat() * scale

        drawCircle(PAPER, radius = PLOT_RADIUS_MM * scale, center = centre)
        drawCircle(BLACK, radius = r(TARGET_BLACK_RING_RADIUS_MM), center = centre)
        // Ring lines out to ring 5, plus the inner-ten circle.
        (RING_RADII_MM.filter { it <= PLOT_RADIUS_MM } + INNER_TEN_RADIUS_MM).forEach { mm ->
            drawCircle(
                color = if (mm <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else LINE_ON_PAPER,
                radius = r(mm),
                center = centre,
                style = Stroke(width = 1.5f),
            )
        }

        // Ring digits 5..9, centred in their band on all four axis arms.
        val digitPaint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = 11f * scale
        }
        // textAlign centres horizontally; the third of the text size centres vertically.
        fun digit(ring: Int, x: Float, y: Float) = drawContext.canvas.nativeCanvas.drawText(
            ring.toString(), x, y + digitPaint.textSize / 3f, digitPaint,
        )
        for (ring in 5..9) {
            val mid = (RING_RADII_MM[10 - ring] + RING_RADII_MM[9 - ring]) / 2.0
            digitPaint.color =
                (if (mid <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else LINE_ON_PAPER).toArgb()
            digit(ring, centre.x - r(mid), centre.y)
            digit(ring, centre.x + r(mid), centre.y)
            digit(ring, centre.x, centre.y - r(mid))
            digit(ring, centre.x, centre.y + r(mid))
        }

        plotted.forEach { series ->
            val colour = hitColour(series.age)
            series.hits.forEach { hit ->
                val at = Offset(centre.x + r(hit.xMm), centre.y + r(hit.yMm))
                drawCircle(colour, radius = 2.5f * scale, center = at)
                drawCircle(BLACK, radius = 2.5f * scale, center = at, style = Stroke(width = 1f))
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

/** [HIT_SCALE] sampled at [age] (0 = oldest, 1 = newest): a lerp within one segment. */
private fun hitColour(age: Float): Color {
    val t = age.coerceIn(0f, 1f) * (HIT_SCALE.size - 1)
    val i = t.toInt().coerceAtMost(HIT_SCALE.size - 2)
    return lerp(HIT_SCALE[i], HIT_SCALE[i + 1], t - i)
}

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

/** What the two point-of-impact markers on the target mean. */
@Composable
private fun MarkerLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            false to (MEAN_MARK to R.string.stats_legend_mean),
            true to (MEDIAN_MARK to R.string.stats_legend_median),
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

/** Which end of the colour scale is which date. */
@Composable
private fun AgeLegend(plotted: List<PlottedSeries>) {
    if (plotted.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(HIT_SCALE)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                localStamp(plotted.first().series.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                localStamp(plotted.last().series.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MeasurementRows(stats: SeriesStatistics) {
    val mm = @Composable { value: Double -> stringResource(R.string.stats_mm, value.roundToInt()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Measurement(stringResource(R.string.stats_series), stats.seriesCount.toString())
        Measurement(stringResource(R.string.stats_hits), stats.hitCount.toString())
        Measurement(stringResource(R.string.stats_mean_distance), mm(stats.meanDistanceMm))
        Measurement(stringResource(R.string.stats_mean_pairwise), mm(stats.meanPairwiseMm))
        Measurement(stringResource(R.string.stats_group_size), mm(stats.meanGroupSizeMm))
        Measurement(
            stringResource(R.string.stats_impact),
            stringResource(
                R.string.stats_impact_value,
                stats.impactXMm.roundToInt(),
                stats.impactYMm.roundToInt(),
            ),
        )
        Measurement(
            stringResource(R.string.stats_impact_median),
            stringResource(
                R.string.stats_impact_value,
                stats.medianXMm.roundToInt(),
                stats.medianYMm.roundToInt(),
            ),
        )
        Measurement(stringResource(R.string.stats_mean_score), "%.1f".format(stats.meanScore))
        Measurement(
            stringResource(R.string.stats_tens_share),
            stringResource(R.string.stats_percent, (stats.tensShare * 100).roundToInt()),
        )
        stats.best?.let { (series, total) ->
            Measurement(
                stringResource(R.string.stats_best),
                stringResource(R.string.stats_series_value, total, localStamp(series.timestamp)),
            )
        }
        stats.worst?.let { (series, total) ->
            Measurement(
                stringResource(R.string.stats_worst),
                stringResource(R.string.stats_series_value, total, localStamp(series.timestamp)),
            )
        }
    }
}

@Composable
private fun Measurement(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
