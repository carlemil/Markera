package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HIT_DOT_ALPHA
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.hitDotRadiusMm
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.utcDay
import se.kjellstrand.markera.series.stats.Bucket
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.stats.Metric
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.SeriesStatistics
import se.kjellstrand.markera.series.stats.StatsFilter
import se.kjellstrand.markera.series.stats.plotSeries
import se.kjellstrand.markera.series.stats.statistics
import se.kjellstrand.markera.series.stats.trend
import se.kjellstrand.markera.series.stats.trendByCaliber
import se.kjellstrand.markera.series.stats.window
import androidx.compose.material.icons.Icons
import se.kjellstrand.markera.ui.AppChip
import se.kjellstrand.markera.ui.MenuItem
import se.kjellstrand.markera.ui.StateMessage
import se.kjellstrand.markera.ui.userMessage
import se.kjellstrand.markera.ui.rememberBackendSignIn
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.vision.INNER_TEN_RADIUS_MM
import se.kjellstrand.markera.vision.RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM

/** Ring 1's outer edge — the whole drawn target, and the canvas' mm half-width. */
private const val PLOT_RADIUS_MM = 250f
private const val MAX_ZOOM = 6f

private val PAPER = Color(0xFFE8DEC8)
private val BLACK = Color(0xFF15151A)
private val LINE_ON_BLACK = Color(0xFFEDEDED)
private val LINE_ON_PAPER = Color(0xFF6B6455)
/**
 * Oldest → newest hit colours, also the legend's gradient bar: one continuous
 * blue → red gradient, its two far-apart ends keeping early and late series
 * easy to tell apart. The black outline on each hit keeps both ends crisp
 * against the cream paper.
 */
private val HIT_SCALE = listOf(
    Color(0xFF304FFE), // blue
    Color(0xFFFF1A1A), // red
)
// Green sits far from both ends, magenta off to the pink side of the red one; the
// + / × shapes and drawMark's dark halo still tell them apart for colour-blind readers.
private val MEAN_MARK = Color(0xFFFF00C8)
private val MEDIAN_MARK = Color(0xFF00C853)

/** Marker geometry, shared by the target and the legend (see [drawMark]). */
private val MARK_ARM = 5.dp
private val MARK_HALO = 2.dp
private val MARK_STROKE = 1.dp

/** The age slider's grab handles: a round knob, squat over the 8 dp bar. */
private val KNOB = DpSize(20.dp, 20.dp)

/**
 * All saved series with geometry, filtered and drawn on one target: every hit
 * un-projected to target millimetres, coloured old → new, with the group
 * measurements underneath. The maths lives in `series/stats/`; this only draws.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun StatsScreen(services: SeriesServices, onBack: () -> Unit, onMarkera: () -> Unit = {}) {
    val auth by services.session.auth.collectAsState()
    val series by services.repository.series.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val (signingIn, signIn) = rememberBackendSignIn(services)

    var selectedCalibers by remember { mutableStateOf(emptySet<Caliber>()) }
    // Multi-select like Historik's tag chips; empty = no tag filtering at all.
    var tags by remember { mutableStateOf(emptySet<String>()) }
    var preset by remember { mutableStateOf(DatePreset.ALL) }
    // Custom window as the picker hands it over: UTC start-of-day millis.
    var customRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var pickingDates by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // 0 = the target with the hit cloud, 1 = the measurements charted over time.
    var tab by remember { mutableIntStateOf(0) }
    var metric by remember { mutableStateOf(Metric.SCORE) }
    var bucket by remember { mutableStateOf(Bucket.SERIES) }
    var splitByCaliber by remember { mutableStateOf(true) }

    // The cache holds every series already; opening only asks for the delta.
    LaunchedEffect(auth, reload) {
        if (auth == null) return@LaunchedEffect
        loading = true
        error = services.repository.refresh()?.userMessage()
        loading = false
    }

    val filter = remember(selectedCalibers, tags, preset, customRange) {
        val (from, to) = preset.window(customRange, Clock.System.now())
        StatsFilter(calibers = selectedCalibers, from = from, to = to, tags = tags)
    }
    val plotted = remember(series, filter) { series.plotSeries(filter) }
    val stats = remember(plotted) { plotted.statistics() }
    // Tavla-only: which series (by index into `plotted`, oldest..newest) the target and
    // measurements are narrowed to. Resets to the full range whenever `plotted` itself
    // changes (a new filter, or a data refresh), same as the age colours it rides on.
    var selection by remember(plotted) { mutableStateOf(0..plotted.lastIndex.coerceAtLeast(0)) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            AppTopBar(
                title = stringResource(Res.string.stats_title),
                onBack = onBack,
                menuItems = listOf(MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) { showingHelp = true }),
            )
            TabRow(selectedTabIndex = tab) {
                listOf(Res.string.stats_tab_target, Res.string.stats_tab_trend).forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) })
                }
            }
            when {
                auth == null -> if (signingIn) StateMessage(loading = true) else StateMessage(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(Res.string.stats_signed_out),
                    hint = stringResource(Res.string.signed_out_hint),
                    actionLabel = stringResource(Res.string.home_sign_in),
                    onAction = signIn,
                )

                // Cached series still plot: an error only shows with nothing to draw.
                error != null && series.isEmpty() -> StateMessage(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(Res.string.state_error_title),
                    hint = error?.let { stringResource(it) },
                    actionLabel = stringResource(Res.string.retry),
                    onAction = { reload++ },
                )

                loading && series.isEmpty() -> StateMessage(loading = true)

                series.isEmpty() -> StateMessage(
                    icon = Icons.Default.QueryStats,
                    title = stringResource(Res.string.stats_no_series),
                    hint = stringResource(Res.string.empty_markera_hint),
                    actionLabel = stringResource(Res.string.home_free_marking),
                    onAction = onMarkera,
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Hoisted above the tabs in state, drawn below them: one row, both tabs.
                    FilterRow(
                        calibers = series.calibersWithGeometry(),
                        selectedCalibers = selectedCalibers,
                        onCalibers = { selectedCalibers = it },
                        tags = series.tagsWithGeometry(),
                        selectedTags = tags,
                        onTags = { tags = it },
                        preset = preset,
                        customRange = customRange,
                        onPreset = { preset = it },
                        onPickDates = { pickingDates = true },
                    )
                    if (stats == null) {
                        StateMessage(
                            icon = Icons.Default.FilterAltOff,
                            title = stringResource(Res.string.stats_empty),
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    } else if (tab == 0) {
                        val segment = remember(plotted, selection) {
                            plotted.subList(selection.first, selection.last + 1)
                        }
                        val segmentStats = remember(segment) { segment.statistics() }
                        TargetCanvas(segment, segmentStats)
                        AgeLegend(plotted, selection) { selection = it }
                        MarkerLegend()
                        segmentStats?.let { MeasurementRows(it) }
                    } else {
                        TrendTab(
                            plotted = plotted,
                            calibers = series.calibersWithGeometry(),
                            metric = metric,
                            onMetric = { metric = it },
                            bucket = bucket,
                            onBucket = { bucket = it },
                            // One caliber filtered in leaves nothing to split.
                            splitOffered = selectedCalibers.size != 1 && series.calibersWithGeometry().size >= 2,
                            split = splitByCaliber,
                            onSplit = { splitByCaliber = it },
                        )
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
    if (showingHelp) {
        // What every measurement under the target actually means.
        HelpDialog(
            title = stringResource(Res.string.stats_help),
            sections = listOf(
                Res.string.stats_series to Res.string.stats_help_series,
                Res.string.stats_hits to Res.string.stats_help_hits,
                Res.string.stats_mean_distance to Res.string.stats_help_mean_distance,
                Res.string.stats_mean_pairwise to Res.string.stats_help_mean_pairwise,
                Res.string.stats_group_size to Res.string.stats_help_group_size,
                Res.string.stats_mean_radius to Res.string.stats_help_mean_radius,
                Res.string.stats_radial_sd to Res.string.stats_help_radial_sd,
                Res.string.stats_impact to Res.string.stats_help_impact,
                Res.string.stats_impact_median to Res.string.stats_help_impact_median,
                Res.string.stats_mean_score to Res.string.stats_help_mean_score,
                Res.string.stats_help_colours to Res.string.stats_help_colours_body,
                Res.string.stats_help_trend to Res.string.stats_help_trend_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }
}

/** The calibers actually worth offering: those on plottable (geometry-carrying) series. */
private fun List<SeriesDto>.calibersWithGeometry(): List<Caliber> =
    filter { it.geometry != null }
        .map { Caliber.fromLabel(it.caliber) }
        .filter { it != Caliber.NONE }
        .distinct()
        .sortedBy { it.ordinal }

/** The same for tags, alphabetical; an untagged series contributes none. */
private fun List<SeriesDto>.tagsWithGeometry(): List<String> =
    filter { it.geometry != null }.mapNotNull { it.tag }.distinct().sorted()

@OptIn(ExperimentalLayoutApi::class, ExperimentalTime::class)
@Composable
private fun FilterRow(
    calibers: List<Caliber>,
    selectedCalibers: Set<Caliber>,
    onCalibers: (Set<Caliber>) -> Unit,
    tags: List<String>,
    selectedTags: Set<String>,
    onTags: (Set<String>) -> Unit,
    preset: DatePreset,
    customRange: Pair<Long, Long>?,
    onPreset: (DatePreset) -> Unit,
    onPickDates: () -> Unit,
) {
    // One child of the caller's 16 dp column, so only the filter rows sit tight.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(Res.string.stats_group_caliber))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(
                selected = selectedCalibers.isEmpty(),
                onClick = { onCalibers(emptySet()) },
                label = stringResource(Res.string.stats_caliber_all),
            )
            // A caliber picked before a refresh dropped its last series stays deselectable.
            (calibers + selectedCalibers).distinct().sortedBy { it.ordinal }.forEach {
                AppChip(
                    selected = it in selectedCalibers,
                    onClick = {
                        onCalibers(if (it in selectedCalibers) selectedCalibers - it else selectedCalibers + it)
                    },
                    label = it.label,
                )
            }
        }
        // No tag on any plottable series (and none selected) means no section at all.
        // A tag picked before a refresh dropped its last series still gets a chip, so
        // the filter stays deselectable.
        val offeredTags = (tags + selectedTags).distinct().sorted()
        if (offeredTags.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.stats_group_tag))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(
                    selected = selectedTags.isEmpty(),
                    onClick = { onTags(emptySet()) },
                    label = stringResource(Res.string.stats_caliber_all),
                )
                offeredTags.forEach { tag ->
                    AppChip(
                        selected = tag in selectedTags,
                        onClick = {
                            onTags(if (tag in selectedTags) selectedTags - tag else selectedTags + tag)
                        },
                        label = tag,
                    )
                }
            }
        }
        SectionHeader(stringResource(Res.string.stats_group_date))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presetChip = @Composable { value: DatePreset, label: StringResource ->
                AppChip(
                    selected = preset == value,
                    onClick = { onPreset(value) },
                    label = stringResource(label),
                )
            }
            presetChip(DatePreset.ALL, Res.string.stats_date_all)
            // "Välj…" right after "Alla", so the custom range is one tap away on every width.
            AppChip(
                selected = preset == DatePreset.CUSTOM,
                onClick = onPickDates,
                label = if (preset == DatePreset.CUSTOM && customRange != null) {
                    stringResource(
                        Res.string.stats_date_range,
                        utcDay(customRange.first),
                        utcDay(customRange.second),
                    )
                } else {
                    stringResource(Res.string.stats_date_custom)
                },
            )
            presetChip(DatePreset.WEEK, Res.string.stats_date_week)
            presetChip(DatePreset.MONTH, Res.string.stats_date_month)
            presetChip(DatePreset.YEAR, Res.string.stats_date_year)
        }
    }
}

/** The Trend tab: which row, how the x axis buckets series, one line or one per caliber. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendTab(
    plotted: List<PlottedSeries>,
    /** Every caliber on any plottable series: a caliber's colour is its slot here, whatever the filter keeps. */
    calibers: List<Caliber>,
    metric: Metric,
    onMetric: (Metric) -> Unit,
    bucket: Bucket,
    onBucket: (Bucket) -> Unit,
    splitOffered: Boolean,
    split: Boolean,
    onSplit: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(Res.string.stats_group_metric))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric.entries.forEach {
                AppChip(
                    selected = metric == it,
                    onClick = { onMetric(it) },
                    label = stringResource(it.label()),
                )
            }
        }
        SectionHeader(stringResource(Res.string.stats_group_bucket))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Bucket.SERIES to Res.string.stats_bucket_series,
                Bucket.DAY to Res.string.stats_bucket_day,
                Bucket.WEEK to Res.string.stats_bucket_week,
                Bucket.MONTH to Res.string.stats_bucket_month,
            ).forEach { (value, label) ->
                AppChip(
                    selected = bucket == value,
                    onClick = { onBucket(value) },
                    label = stringResource(label),
                )
            }
            if (splitOffered) {
                AppChip(
                    selected = split,
                    onClick = { onSplit(!split) },
                    label = stringResource(Res.string.stats_split_caliber),
                )
            }
        }
    }
    val lines = remember(plotted, calibers, metric, bucket, split, splitOffered) {
        if (split && splitOffered) {
            plotted.trendByCaliber(metric, bucket).map { (caliber, points) ->
                TrendLine(caliber.label, TREND_COLOURS[calibers.indexOf(caliber).mod(TREND_COLOURS.size)], points)
            }
        } else {
            listOf(TrendLine("", TREND_COLOURS[0], plotted.trend(metric, bucket)))
        }
    }
    // The ticks are drawn in a Canvas, so the template is resolved here and filled there.
    val mm = stringResource(Res.string.stats_mm)
    val mark = stringResource(Res.string.decimal_mark)
    val format: (Double) -> String = when (metric) {
        Metric.SCORE -> { v -> oneDecimal(v, mark) }
        else -> { v -> mm.replace("%1\$d", v.roundToInt().toString()) }
    }
    TrendChart(lines, format) { line, point ->
        // A bucket starts at midnight, so only a per-series point carries a time of day.
        val stamp = localStamp(point.at.toString()).let { if (bucket == Bucket.SERIES) it else it.take(10) }
        val value = format(point.value)
        if (point.seriesCount > 1) {
            stringResource(Res.string.stats_trend_point_many, line.label, stamp, value, point.seriesCount)
        } else {
            stringResource(Res.string.stats_trend_point, line.label, stamp, value)
        }.trim()
    }
}

/** [v] to one decimal, with the app language's decimal [mark]. */
internal fun oneDecimal(v: Double, mark: String) = ((v * 10).roundToInt() / 10.0).toString().replace(".", mark)

/** Chip label: the short form of the measurement row's name. */
private fun Metric.label() = when (this) {
    Metric.MEAN_DISTANCE -> Res.string.stats_short_mean_distance
    Metric.MEAN_PAIRWISE -> Res.string.stats_short_mean_pairwise
    Metric.GROUP_SIZE -> Res.string.stats_short_group_size
    Metric.MEAN_RADIUS -> Res.string.stats_short_mean_radius
    Metric.RADIAL_SD -> Res.string.stats_short_radial_sd
    Metric.SCORE -> Res.string.stats_short_mean_score
}

/** Left-aligned section title in bold green caps. Shared app-wide. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeDialog(onDismiss: () -> Unit, onPicked: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            val end = state.selectedEndDateMillis
            TextButton(
                onClick = { if (start != null && end != null) onPicked(start, end) },
                enabled = start != null && end != null,
            ) { Text(stringResource(Res.string.stats_date_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.stats_date_cancel)) }
        },
    ) {
        // Weighted so the tall range picker scrolls inside the dialog instead of
        // pushing the buttons off a short screen.
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

/**
 * The whole target (rings 1-10) with every filtered hit on it. Offsets are in
 * image axes (y down), so they map straight onto canvas coordinates. Pinch to
 * zoom, drag to pan once zoomed, double-tap to reset.
 */
@Composable
private fun TargetCanvas(plotted: List<PlottedSeries>, stats: SeriesStatistics?) {
    val textMeasurer = rememberTextMeasurer()
    // The layer scales about its top-left corner, so zooming about the pinch
    // centroid is a plain "keep the centroid still" rescale of the pan.
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clipToBounds()
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
            val mid = (RING_RADII_MM[10 - ring] + RING_RADII_MM[9 - ring]) / 2.0
            val colour = if (mid <= TARGET_BLACK_RING_RADIUS_MM) LINE_ON_BLACK else LINE_ON_PAPER
            digit(ring, colour, centre.x - r(mid), centre.y)
            digit(ring, colour, centre.x + r(mid), centre.y)
            digit(ring, colour, centre.x, centre.y - r(mid))
            digit(ring, colour, centre.x, centre.y + r(mid))
        }

        plotted.forEach { series ->
            val colour = hitColour(series.age).copy(alpha = HIT_DOT_ALPHA)
            // scale is px/mm here, so the caliber's mm radius converts directly.
            val dotRadius = Caliber.fromLabel(series.series.caliber).hitDotRadiusMm() * scale
            series.hits.forEach { hit ->
                val at = Offset(centre.x + r(hit.xMm), centre.y + r(hit.yMm))
                drawCircle(colour, radius = dotRadius, center = at)
                drawCircle(
                    BLACK.copy(alpha = HIT_DOT_ALPHA),
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
 * The age colour scale, doubling as a two-knob range slider (one series or fewer:
 * nothing to segment, so it's just the plain dotted bar). Dragging the knobs narrows
 * [selection] — an index range into [plotted], oldest first — which the target and
 * measurements above follow; hit colours always come from the full-range age, so a
 * hit's colour still matches its dot's position on the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgeLegend(plotted: List<PlottedSeries>, selection: IntRange, onSelection: (IntRange) -> Unit) {
    if (plotted.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                localStamp(plotted[selection.first].series.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                localStamp(plotted[selection.last].series.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plotted.size > 1) {
            val startSource = remember { MutableInteractionSource() }
            val endSource = remember { MutableInteractionSource() }
            RangeSlider(
                value = selection.first.toFloat()..selection.last.toFloat(),
                onValueChange = { range ->
                    // Snap to series positions: series count is small, so a plain
                    // round-to-nearest is enough even where steps == 0 (n == 2).
                    onSelection(range.start.roundToInt()..range.endInclusive.roundToInt())
                },
                valueRange = 0f..plotted.lastIndex.toFloat(),
                steps = (plotted.size - 2).coerceAtLeast(0),
                startInteractionSource = startSource,
                endInteractionSource = endSource,
                // The stock thumb is a tall pill; a round knob the width of the bar's
                // own height reads as a grab handle without towering over it. Dragging
                // is the slider's own gesture, so the touch area doesn't shrink with it.
                startThumb = { SliderDefaults.Thumb(startSource, thumbSize = KNOB) },
                endThumb = { SliderDefaults.Thumb(endSource, thumbSize = KNOB) },
                track = { AgeTrack(plotted) },
            )
        } else {
            AgeTrack(plotted)
        }
    }
}

/**
 * The gradient bar itself, with a small dot at each series' own position — the same
 * [PlottedSeries.age] fraction that colours its hits, so a dot always sits under the
 * colour its hits are drawn in. Used both standalone (one series) and as the
 * [RangeSlider]'s custom track, whose slot is already inset to the thumbs' travel
 * width — whatever [KNOB] the thumbs are — so `fillMaxWidth()` here lines the dots
 * up exactly under where the knobs snap.
 */
@Composable
private fun AgeTrack(plotted: List<PlottedSeries>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(brush = Brush.horizontalGradient(HIT_SCALE), cornerRadius = CornerRadius(4.dp.toPx()))
        val dotRadius = 2.dp.toPx()
        val ringWidth = 0.75.dp.toPx()
        plotted.forEach { s ->
            val at = Offset(size.width * s.age, size.height / 2f)
            drawCircle(BLACK, radius = dotRadius, center = at)
            drawCircle(LINE_ON_BLACK, radius = dotRadius, center = at, style = Stroke(width = ringWidth))
        }
    }
}

@Composable
private fun MeasurementRows(stats: SeriesStatistics) {
    val mm = @Composable { value: Double -> stringResource(Res.string.stats_mm, value.roundToInt()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Measurement(stringResource(Res.string.stats_series), stats.seriesCount.toString())
        Measurement(stringResource(Res.string.stats_hits), stats.hitCount.toString())
        Measurement(stringResource(Res.string.stats_mean_distance), mm(stats.meanDistanceMm))
        Measurement(stringResource(Res.string.stats_mean_pairwise), mm(stats.meanPairwiseMm))
        Measurement(stringResource(Res.string.stats_group_size), mm(stats.meanGroupSizeMm))
        Measurement(stringResource(Res.string.stats_mean_radius), mm(stats.meanRadiusMm))
        Measurement(stringResource(Res.string.stats_radial_sd), mm(stats.radialSdMm))
        Measurement(
            stringResource(Res.string.stats_impact),
            stringResource(
                Res.string.stats_impact_value,
                stats.impactXMm.roundToInt(),
                stats.impactYMm.roundToInt(),
            ),
        )
        Measurement(
            stringResource(Res.string.stats_impact_median),
            stringResource(
                Res.string.stats_impact_value,
                stats.medianXMm.roundToInt(),
                stats.medianYMm.roundToInt(),
            ),
        )
        Measurement(
            stringResource(Res.string.stats_mean_score),
            oneDecimal(stats.meanScore, stringResource(Res.string.decimal_mark)),
        )
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
