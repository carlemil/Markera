package se.kjellstrand.markera.ui.history

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.exportSeriesZip
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.total
import se.kjellstrand.markera.ui.MenuItem
import se.kjellstrand.markera.ui.StateMessage
import se.kjellstrand.markera.ui.userMessage
import se.kjellstrand.markera.ui.rememberBackendSignIn
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.LocalToast
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.DateRangeDialog
import se.kjellstrand.markera.ui.FilterBar
import se.kjellstrand.markera.ui.SectionHeader


/** The cached series, newest first; opening asks the backend for a delta. */
@OptIn(ExperimentalTime::class, ExperimentalMaterial3Api::class)
@Composable
fun SeriesHistoryScreen(
    services: SeriesServices,
    onBack: () -> Unit,
    shareFile: suspend (path: String) -> Unit,
    onOpen: (SeriesDto) -> Unit = {},
    onMarkera: () -> Unit = {},
) {
    val auth by services.session.auth.collectAsState()
    val series by services.repository.series.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<SeriesDto?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val (signingIn, signIn) = rememberBackendSignIn(services)
    var showingHelp by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HistoryFilter()) }
    // Guards against writing the still-default filter back before the saved one loaded.
    var filterLoaded by remember { mutableStateOf(false) }
    // Which days are unfolded, and the same guard against wiping the stored set.
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var expandedLoaded by remember { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    LaunchedEffect(auth, reload) {
        if (auth == null) return@LaunchedEffect
        loading = true
        error = services.repository.refresh()?.userMessage()
        loading = false
        // With rows cached the screen still shows them, so say they may be stale.
        if (error != null && services.repository.series.value.isNotEmpty()) toast(getString(Res.string.refresh_offline))
    }

    LaunchedEffect(Unit) {
        filter = decodeHistoryFilter(services.store.readHistoryFilter())
        filterLoaded = true
        expanded = decodeOpenDays(services.store.readOpenDays())
        expandedLoaded = true
    }

    fun onFilter(new: HistoryFilter) {
        filter = new
        if (filterLoaded) scope.launch { services.store.writeHistoryFilter(new.encode()) }
    }

    fun onToggleDay(day: String) {
        val next = if (day in expanded) expanded - day else expanded + day
        expanded = next
        // Against the unfiltered list: a day the filter hides is still worth keeping.
        if (expandedLoaded) scope.launch { services.store.writeOpenDays(encodeOpenDays(next, series)) }
    }

    // A changed filter re-anchors the keyed list on whatever item was first visible;
    // pull it back to the newest series. Runs after the recomposition that swapped the
    // list in: scrolling from onFilter hit the old list, and the key anchor won.
    LaunchedEffect(filter) { listState.scrollToItem(0) }

    val shown = remember(series, filter) { series.filteredBy(filter, Clock.System.now()) }
    val days = remember(shown) { shown.groupedByDay() }
    // Numbered against every series, not `shown`: a filter must not renumber
    // a series the user already knows as "serie 3".
    val ordinals = remember(series) { series.dayOrdinals() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            // The export spinner rides at the end of the bar while the zip is built.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTopBar(
                    title = stringResource(Res.string.history_title),
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                    menuItems = listOf(
                        MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) { showingHelp = true },
                        MenuItem(
                            Icons.Default.Backup,
                            stringResource(Res.string.history_export),
                            enabled = !exporting && auth != null && shown.isNotEmpty(),
                        ) {
                            exporting = true
                            scope.launch {
                                try {
                                    shareFile(
                                        exportSeriesZip(
                                            services.repository,
                                            services.cacheDir,
                                            shown,
                                        ).toString(),
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    toast(getString(Res.string.history_export_failed))
                                } finally {
                                    exporting = false
                                }
                            }
                        },
                    ),
                )
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 16.dp).size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            when {
                auth == null -> if (signingIn) StateMessage(loading = true) else StateMessage(
                    icon = Icons.Default.AccountCircle,
                    title = stringResource(Res.string.history_signed_out),
                    hint = stringResource(Res.string.signed_out_hint),
                    actionLabel = stringResource(Res.string.home_sign_in),
                    onAction = signIn,
                )

                // A failed delta only takes over the screen with nothing cached to show.
                error != null && series.isEmpty() -> StateMessage(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(Res.string.state_error_title),
                    hint = error?.let { stringResource(it) },
                    actionLabel = stringResource(Res.string.retry),
                    onAction = { reload++ },
                )

                loading && series.isEmpty() -> StateMessage(loading = true)

                series.isEmpty() -> StateMessage(
                    icon = Icons.Default.History,
                    title = stringResource(Res.string.history_empty),
                    hint = stringResource(Res.string.empty_markera_hint),
                    actionLabel = stringResource(Res.string.home_free_marking),
                    onAction = onMarkera,
                )

                else -> PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    FilterBar(
                        calibers = series.calibersPresent(),
                        tags = series.tagsPresent(),
                        filter = filter,
                        onFilter = ::onFilter,
                        onPickDates = { pickingDates = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (shown.isEmpty()) {
                        StateMessage(
                            icon = Icons.Default.FilterAltOff,
                            title = stringResource(Res.string.history_filter_empty),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            days.forEach { day ->
                                item(key = day.day) {
                                    DayHeader(
                                        group = day,
                                        expanded = day.day in expanded,
                                        onToggle = { onToggleDay(day.day) },
                                    )
                                }
                                if (day.day in expanded) {
                                    items(day.series, key = { it.id }) { item ->
                                        SeriesCard(
                                            series = item,
                                            ordinal = ordinals[item.id] ?: 1,
                                            services = services,
                                            onClick = { onOpen(item) },
                                            onLongPress = { pending = item },
                                        )
                                    }
                                }
                            }
                        }
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
                onFilter(filter.copy(customRange = start to end, preset = DatePreset.CUSTOM))
                pickingDates = false
            },
        )
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_history_title),
            sections = listOf(
                Res.string.help_history_list to Res.string.help_history_list_body,
                Res.string.help_history_detail to Res.string.help_history_detail_body,
                Res.string.help_history_export to Res.string.help_history_export_body,
                Res.string.help_history_offline to Res.string.help_history_offline_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }

    pending?.let { target ->
        DeleteSeriesDialog(
            series = target,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    try {
                        services.repository.delete(target.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        toast(getString(Res.string.history_delete_failed))
                    }
                }
            },
        )
    }
}

/**
 * A day's header — `⌄ 2026-09-15      3 serier · 87 poäng` — the day in the shared
 * `SectionHeader` style. The whole row folds the day's cards in and out.
 */
@Composable
private fun DayHeader(group: DayGroup, expanded: Boolean, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f)
    val expandedText = stringResource(Res.string.state_expanded)
    val collapsedText = stringResource(Res.string.state_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) expandedText else collapsedText }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.rotate(angle),
            tint = MaterialTheme.colorScheme.primary,
        )
        SectionHeader(group.day)
        Spacer(Modifier.weight(1f))
        val points = group.series.sumOf { it.total() }
        Text(
            text = if (group.series.size == 1) {
                stringResource(Res.string.history_day_summary_one, points)
            } else {
                stringResource(Res.string.history_day_summary, group.series.size, points)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

