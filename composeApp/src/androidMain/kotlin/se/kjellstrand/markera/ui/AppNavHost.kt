package se.kjellstrand.markera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.AppServices
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.SaveStatus
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.encodeSeriesJpeg
import se.kjellstrand.markera.series.signInWithProvider
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.ui.history.SeriesDetailScreen
import se.kjellstrand.markera.ui.history.SeriesHistoryScreen
import se.kjellstrand.markera.ui.markera.MarkeraScreen
import se.kjellstrand.markera.ui.markera.rememberFrameSource
import se.kjellstrand.markera.ui.markera.rememberTargetScanController
import se.kjellstrand.markera.ui.stats.StatsScreen

/** Shows a short message; the host lives in [AppNavHost], above every screen. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { error("no toast host") }

/** The app's screens; a simple list-backed stack, no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object FreeMarking : Screen
    data object History : Screen
    data object Statistics : Screen

    /** One saved series, editable. Carries the DTO the history row already has. */
    data class SeriesDetail(val series: SeriesDto) : Screen

    /** The optional platform flow, if the host supplied one. */
    data object Competition : Screen
}

/** An optional flow the platform plugs into the nav host (the Android-only webshooter marking). */
class CompetitionHost(
    val content: @Composable (
        frameSource: FrameSource,
        scanController: TargetScanController,
        onExit: () -> Unit,
    ) -> Unit,
)

/**
 * Navigation root. The frame source and the scan controller (the single ONNX
 * session) live here, above the back stack, so both the free-marking screen
 * and the competition wizard share them and nothing heavy is rebuilt per
 * screen switch.
 */
@Composable
fun AppNavHost(app: AppServices, competition: CompetitionHost? = null) {
    val seriesServices = app.series
    val frameSource = rememberFrameSource()
    val scanController = rememberTargetScanController(app.modelPath)

    // Auto-save: every scan the shared controller completes is offered to the
    // recorder, whichever screen started it.
    val scope = rememberCoroutineScope()
    val recorder = remember {
        SeriesRecorder(
            repository = seriesServices.repository,
            session = seriesServices.session,
            readCaliber = seriesServices.store::readCaliber,
            writeCaliber = seriesServices.store::writeCaliber,
            encodeJpeg = ::encodeSeriesJpeg,
            scope = scope,
        ).also { scanController.onSeriesDetected = it::onSeriesDetected }
    }
    DisposableEffect(recorder) { onDispose { recorder.dispose() } }

    val snackbarHostState = remember { SnackbarHostState() }
    val toast: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    // The only save feedback, for both screens: one toast per outcome. Collected
    // (not read from the current value), so a recomposition never repeats it.
    val savedText = stringResource(Res.string.series_status_saved)
    val failedText = stringResource(Res.string.series_status_failed)
    val signInText = stringResource(Res.string.home_sign_in)
    LaunchedEffect(recorder) {
        recorder.status.collect { status ->
            val message = when (status) {
                is SaveStatus.Saved -> savedText
                is SaveStatus.Failed -> "$failedText: ${status.message}"
                SaveStatus.SignedOut -> signInText
                else -> return@collect
            }
            toast(message)
        }
    }

    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val current = stack.last()
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // The cache is published before the delta lands, so History has rows at once.
    LaunchedEffect(Unit) {
        seriesServices.session.restore()
        seriesServices.repository.refresh()
    }
    val backendAuth by seriesServices.session.auth.collectAsState()

    CompositionLocalProvider(
        LocalSeriesRecorder provides recorder,
        LocalToast provides toast,
    ) {
    Box(Modifier.fillMaxSize()) {
    when (val screen = current) {
        Screen.Home -> HomeScreen(
            onFreeMarking = { push(Screen.FreeMarking) },
            onCompetition = competition?.let { { push(Screen.Competition) } },
            onHistory = { push(Screen.History) },
            onStatistics = { push(Screen.Statistics) },
            backendAuth = backendAuth,
            seriesServices = seriesServices,
        )

        Screen.FreeMarking -> MarkeraScreen(
            frameSource = frameSource,
            scanController = scanController,
            onBack = pop,
        )

        // Both list screens read the cached flow; they only ask for a delta on open.
        Screen.History -> SeriesHistoryScreen(
            services = seriesServices,
            onBack = pop,
            onOpen = { push(Screen.SeriesDetail(it)) },
        )

        Screen.Statistics -> StatsScreen(services = seriesServices, onBack = pop)

        is Screen.SeriesDetail -> SeriesDetailScreen(
            initial = screen.series,
            services = seriesServices,
            onBack = pop,
        )

        Screen.Competition -> competition!!.content(frameSource, scanController, pop)
    }
    SnackbarHost(
        snackbarHostState,
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    )
    }
    }

    val caliberDialogOpen by recorder.caliberDialogOpen.collectAsState()
    if (caliberDialogOpen) {
        val caliber by recorder.caliber.collectAsState()
        CaliberDialog(
            selected = caliber,
            onSelect = recorder::selectCaliber,
            onDismiss = recorder::dismissCaliberDialog,
        )
    }
}

/** Tags the scanned series; shown automatically while the caliber is "-". */
@Composable
private fun CaliberDialog(
    selected: Caliber,
    onSelect: (Caliber) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.series_caliber_title)) },
        confirmButton = {},
        text = {
            // Compact rows: the whole row is the tap target, so the radio's 48 dp minimum is off.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Caliber.entries.forEach { caliber ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(caliber) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = caliber == selected, onClick = { onSelect(caliber) })
                            Text(
                                if (caliber == Caliber.NONE) stringResource(Res.string.series_caliber_none) else caliber.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Start screen: free marking (standalone scanner) or competition marking. */
@Composable
private fun HomeScreen(
    onFreeMarking: () -> Unit,
    /** Null hides the card: the platform supplied no competition flow. */
    onCompetition: (() -> Unit)?,
    onHistory: () -> Unit,
    onStatistics: () -> Unit,
    backendAuth: BackendAuth?,
    seriesServices: SeriesServices,
) {
    var showingHelp by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // No top bar here, so the "?" floats in the corner the other screens put it in.
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(48.dp))
                HomeCard(
                    title = stringResource(Res.string.home_free_marking),
                    subtitle = stringResource(Res.string.home_free_marking_hint),
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(36.dp)) },
                    onClick = onFreeMarking,
                )
                if (onCompetition != null) {
                    Spacer(Modifier.height(16.dp))
                    HomeCard(
                        title = stringResource(Res.string.home_competition),
                        subtitle = stringResource(Res.string.home_competition_hint),
                        icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null, modifier = Modifier.size(36.dp)) },
                        onClick = onCompetition,
                    )
                }
                Spacer(Modifier.height(16.dp))
                HomeCard(
                    title = stringResource(Res.string.home_history),
                    subtitle = stringResource(Res.string.home_history_hint),
                    icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(36.dp)) },
                    onClick = onHistory,
                )
                Spacer(Modifier.height(16.dp))
                HomeCard(
                    title = stringResource(Res.string.home_stats),
                    subtitle = stringResource(Res.string.home_stats_hint),
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(36.dp)) },
                    onClick = onStatistics,
                )
                Spacer(Modifier.height(16.dp))
                AccountRow(backendAuth, seriesServices)
            }
            HelpAction(
                onClick = { showingHelp = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(8.dp),
            )
        }
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_home_title),
            sections = listOf(
                Res.string.help_home_how to Res.string.help_home_how_body,
                Res.string.help_home_marking to Res.string.help_home_marking_body,
                Res.string.help_home_history to Res.string.help_home_history_body,
                Res.string.help_home_stats to Res.string.help_home_stats_body,
                Res.string.help_home_account to Res.string.help_home_account_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }
}

/** Markera-backend account: sign in to save scanned series, or sign out. */
@Composable
private fun AccountRow(auth: BackendAuth?, seriesServices: SeriesServices) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.home_delete_account_title)) },
            text = { Text(stringResource(Res.string.home_delete_account_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        try {
                            seriesServices.api.deleteAccount()
                            seriesServices.session.signOut()
                            seriesServices.repository.clear()
                        } catch (_: Throwable) {
                            // Stay signed in; the account is still there.
                            toast(getString(Res.string.home_delete_account_failed))
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.home_delete_account_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.home_delete_account_cancel))
                }
            },
        )
    }

    if (busy) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }
    if (auth == null) {
        TextButton(onClick = {
            busy = true
            scope.launch {
                try {
                    // LocalContext inside MainActivity is the Activity, which is
                    // what Credential Manager needs.
                    signInWithProvider(context, seriesServices.session)
                    // A different account must not inherit the last one's cache.
                    seriesServices.repository.refresh()
                } catch (t: Throwable) {
                    toast(t.message ?: t.toString())
                } finally {
                    busy = false
                }
            }
        }) {
            Text(stringResource(Res.string.home_sign_in))
        }
    } else {
        // Column, not one row: three items side by side clip on a narrow phone.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(Res.string.home_signed_in, auth.provider.replaceFirstChar { it.uppercase() }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    scope.launch {
                        seriesServices.session.signOut()
                        seriesServices.repository.clear()
                    }
                }) {
                    Text(stringResource(Res.string.home_sign_out))
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        stringResource(Res.string.home_delete_account),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
