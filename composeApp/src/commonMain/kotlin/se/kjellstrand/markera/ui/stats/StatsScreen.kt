package se.kjellstrand.markera.ui.stats

import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.resources.getString
import se.kjellstrand.markera.ui.LocalToast
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.decodeSeriesJpeg
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.stats.Bucket
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.stats.Metric
import se.kjellstrand.markera.series.stats.SeriesStatistics
import se.kjellstrand.markera.series.stats.StatsFilter
import se.kjellstrand.markera.series.stats.plotSeries
import se.kjellstrand.markera.series.stats.statistics
import se.kjellstrand.markera.series.stats.window
import se.kjellstrand.markera.series.stats.within
import androidx.compose.material.icons.Icons
import se.kjellstrand.markera.ui.MenuItem
import se.kjellstrand.markera.ui.StateMessage
import se.kjellstrand.markera.ui.userMessage
import se.kjellstrand.markera.ui.rememberBackendSignIn
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.FilterBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import se.kjellstrand.markera.ui.history.HistoryFilter
import se.kjellstrand.markera.ui.history.decodeHistoryFilter
import se.kjellstrand.markera.ui.history.encode
import se.kjellstrand.markera.ui.history.PHOTO_MAX_DIM
import se.kjellstrand.markera.ui.DateRangeDialog


/**
 * All saved series with geometry, filtered and drawn on one target: every hit
 * un-projected to target millimetres, coloured by caliber, with the group
 * measurements underneath. The maths lives in `series/stats/`; this only draws.
 */
@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(services: SeriesServices, onBack: () -> Unit, onMarkera: () -> Unit = {}) {
    val auth by services.session.auth.collectAsState()
    val series by services.repository.series.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val (signingIn, signIn) = rememberBackendSignIn(services)
    val toast = LocalToast.current

    // Every caliber on any plottable series: a caliber's colour is its slot here,
    // whatever the filter keeps.
    val calibers = remember(series) { series.calibersWithGeometry() }
    val tagsWithGeometry = remember(series) { series.tagsWithGeometry() }

    // The one filter Historik uses too, stored the same way: filtering either screen filters both.
    var selection by remember { mutableStateOf(HistoryFilter()) }
    var selectionLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        selection = decodeHistoryFilter(services.store.readHistoryFilter())
        selectionLoaded = true
    }
    fun onSelection(new: HistoryFilter) {
        selection = new
        if (selectionLoaded) scope.launch { services.store.writeHistoryFilter(new.encode()) }
    }
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
        // With rows cached the screen still shows them, so say they may be stale.
        if (error != null && services.repository.series.value.isNotEmpty()) toast(getString(Res.string.refresh_offline))
    }

    val filter = remember(selection) {
        val (from, to) = selection.preset.window(selection.customRange, Clock.System.now())
        StatsFilter(calibers = selection.calibers, from = from, to = to, tags = selection.tags)
    }
    val plotted = remember(series, filter) { series.plotSeries(filter) }
    val stats = remember(plotted) { plotted.statistics() }
    // Tavla-only: the timeline knob sits on one series (an index into `plotted`, oldest
    // first, parked on the newest); switched on, the target and measurements show only
    // that series. Resets whenever `plotted` itself changes (a new filter, or a data
    // refresh), same as the age colours it rides on.
    var knob by remember(plotted) { mutableIntStateOf(plotted.lastIndex.coerceAtLeast(0)) }
    var knobOn by remember(plotted) { mutableStateOf(false) }
    var showingPhoto by remember { mutableStateOf<SeriesDto?>(null) }
    // Tavla's measuring ring, in mm from the centre; null is the whole target.
    var limit by remember { mutableStateOf<Double?>(null) }

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

                else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Hoisted above the tabs in state, drawn below them: one row, both tabs.
                    FilterBar(
                        calibers = calibers,
                        tags = tagsWithGeometry,
                        filter = selection,
                        onFilter = ::onSelection,
                        onPickDates = { pickingDates = true },
                    )
                    if (plotted.isEmpty()) {
                        StateMessage(
                            icon = Icons.Default.FilterAltOff,
                            // Nothing has a ring to plot against at all, whatever the filter.
                            title = stringResource(
                                if (series.none { it.geometry != null }) {
                                    Res.string.stats_no_geometry
                                } else {
                                    Res.string.stats_empty
                                },
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    } else if (tab == 0) {
                        val segment = remember(plotted, knob, knobOn) { knobSelection(plotted, knob, knobOn) }
                        val segmentStats = remember(segment, limit) { segment.within(limit).statistics() }
                        Box {
                            TargetCanvas(segment, segmentStats, calibers, limit) { limit = it }
                            // The gestures and the ring live in the help, not in a line of text here.
                            FilledTonalIconButton(
                                onClick = { showingHelp = true },
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(32.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help))
                            }
                            // The one series under a switched-on knob, when it has a photo.
                            val photographed = segment.singleOrNull()?.series?.takeIf { knobOn && it.hasImage }
                            if (photographed != null) {
                                // Filled so it reads on both the cream paper and the black.
                                FilledTonalIconButton(
                                    onClick = { showingPhoto = photographed },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                ) {
                                    Icon(Icons.Default.PhotoCamera, stringResource(Res.string.stats_show_photo))
                                }
                            }
                        }
                        Text(
                            stringResource(Res.string.stats_ring_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    stringResource(Res.string.stats_timeline),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AgeLegend(plotted, knob, knobOn) { index, on -> knob = index; knobOn = on }
                            }
                        }
                        MarkerLegend(segment, calibers)
                        segmentStats?.let { MeasurementRows(it, segment.sumOf { s -> s.hits.size }) }
                        Text(
                            stringResource(Res.string.stats_limit_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TrendTab(
                            plotted = plotted,
                            calibers = calibers,
                            metric = metric,
                            onMetric = { metric = it },
                            bucket = bucket,
                            onBucket = { bucket = it },
                            // One caliber filtered in leaves nothing to split.
                            splitOffered = selection.calibers.size != 1 && calibers.size >= 2,
                            split = splitByCaliber,
                            onSplit = { splitByCaliber = it },
                        )
                    }
                }
                }
            }
        }
    }

    if (pickingDates) {
        DateRangeDialog(
            onDismiss = { pickingDates = false },
            onPicked = { start, end ->
                onSelection(selection.copy(customRange = start to end, preset = DatePreset.CUSTOM))
                pickingDates = false
            },
        )
    }
    showingPhoto?.let { PhotoDialog(it, services) { showingPhoto = null } }
    if (showingHelp) {
        // What every measurement under the target actually means.
        HelpDialog(
            title = stringResource(Res.string.stats_help),
            sections = listOf(
                Res.string.stats_help_target to Res.string.stats_target_hint,
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

/** [v] to one decimal, with the app language's decimal [mark]. */
internal fun oneDecimal(v: Double, mark: String) = ((v * 10).roundToInt() / 10.0).toString().replace(".", mark)

/** The series' photo, fitted to the dialog; loaded the way the series detail screen does. */
@Composable
private fun PhotoDialog(series: SeriesDto, services: SeriesServices, onDismiss: () -> Unit) {
    var photo by remember(series.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(series.id) { mutableStateOf(false) }
    LaunchedEffect(series.id) {
        // Cache first, then the network; null is a failed download with nothing cached.
        photo = services.repository.image(series.id)?.let {
            try {
                decodeSeriesJpeg(it, PHOTO_MAX_DIM)
            } catch (_: Exception) {
                null
            }
        }
        failed = photo == null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.help_close)) }
        },
        title = { Text(localStamp(series.timestamp)) },
        text = {
            val box = Modifier.fillMaxWidth().aspectRatio(1f)
            val shown = photo
            when {
                shown != null -> Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = box)
                failed -> StateMessage(icon = Icons.Default.HideImage, title = stringResource(Res.string.detail_photo_missing), modifier = box)
                else -> StateMessage(loading = true, modifier = box)
            }
        },
    )
}

/** Marks a measurement that counts only the hits inside the measuring ring. */
private const val LIMITED = " *"

/** The measurements as label/value rows under three headings; [totalHits] is every hit, ring or not. */
@Composable
private fun MeasurementRows(stats: SeriesStatistics, totalHits: Int) {
    val mm = @Composable { value: Double -> stringResource(Res.string.stats_mm, value.roundToInt()) }
    val heading = @Composable { text: String ->
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    }
    val row = @Composable { label: AnnotatedString, value: String ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
    val plain = @Composable { label: String, value: String -> row(AnnotatedString(label), value) }
    val marked = @Composable { mark: String, color: Color, label: String, value: String ->
        row(buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(mark) }; append(" $label") }, value)
    }
    Column {
        heading(stringResource(Res.string.stats_section_group) + LIMITED)
        plain(stringResource(Res.string.stats_hits), stringResource(Res.string.stats_hits_of, stats.hitCount, totalHits))
        plain(stringResource(Res.string.stats_mean_distance), mm(stats.meanDistanceMm))
        plain(stringResource(Res.string.stats_mean_pairwise), mm(stats.meanPairwiseMm))
        plain(stringResource(Res.string.stats_group_size), mm(stats.meanGroupSizeMm))
        plain(stringResource(Res.string.stats_mean_radius), mm(stats.meanRadiusMm))
        plain(stringResource(Res.string.stats_radial_sd), mm(stats.radialSdMm))
        heading(stringResource(Res.string.stats_section_impact) + LIMITED)
        marked("+", MEAN_MARK, stringResource(Res.string.stats_legend_mean), impactArrows(stats.impactXMm, stats.impactYMm))
        marked("×", MEDIAN_MARK, stringResource(Res.string.stats_legend_median), impactArrows(stats.medianXMm, stats.medianYMm))
        heading(stringResource(Res.string.stats_series))
        plain(stringResource(Res.string.stats_count), stats.seriesCount.toString())
        plain(stringResource(Res.string.stats_mean_score), oneDecimal(stats.meanScore, stringResource(Res.string.decimal_mark)))
    }
}

/** A point of impact as arrows: right/left, then down/up, in mm ("→ 3  ↓ 2 mm"). */
internal fun impactArrows(xMm: Double, yMm: Double): String {
    val x = xMm.roundToInt()
    val y = yMm.roundToInt()
    val h = if (x < 0) "← ${-x}" else "→ $x"
    val v = if (y < 0) "↑ ${-y}" else "↓ $y"
    return "$h  $v mm"
}
