package se.kjellstrand.markera.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.AppServices
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.MAX_TAG_LENGTH
import se.kjellstrand.markera.series.SaveStatus
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.encodeSeriesJpeg
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.rememberSignIn
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.ui.history.SeriesDetailScreen
import se.kjellstrand.markera.ui.history.SeriesHistoryScreen
import se.kjellstrand.markera.ui.history.SeriesCard
import se.kjellstrand.markera.ui.history.dayOrdinals
import se.kjellstrand.markera.ui.markera.MarkeraScreen
import se.kjellstrand.markera.ui.markera.rememberFrameSource
import se.kjellstrand.markera.ui.markera.rememberTargetScanController
import se.kjellstrand.markera.ui.settings.AppSettings
import se.kjellstrand.markera.ui.settings.LocalAppLocale
import se.kjellstrand.markera.ui.settings.SettingsScreen
import se.kjellstrand.markera.ui.settings.SystemBarsForTheme
import se.kjellstrand.markera.ui.settings.ThemeMode
import se.kjellstrand.markera.ui.stats.StatsScreen
import se.kjellstrand.markera.ui.theme.MarkeraTheme

/** Shows a short message; the host lives in [AppNavHost], above every screen. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { error("no toast host") }

/** The same host as [LocalToast], for a message with an action (Undo). */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar host") }

/** The app's screens; a simple list-backed stack, no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object FreeMarking : Screen
    data object History : Screen
    data object Statistics : Screen
    data object Settings : Screen

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
 * The app root both platforms call: loads the Settings choices, then applies the
 * theme and the language around [AppNavHost]. A language change re-keys the whole
 * tree (that is what makes the strings re-resolve), so the back stack is held
 * here, above the key, and the user stays on the Settings screen. So are the
 * heavy objects — the frame source, the scan controller (the single ONNX
 * session) and the series recorder — so a language change neither rebuilds
 * them nor cancels an in-flight save or drops the pending series.
 */
@Composable
fun MarkeraApp(app: AppServices, competition: CompetitionHost? = null) {
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(app.series.store, scope) }
    val loaded by settings.loaded.collectAsState()
    // A frame or two of nothing, rather than the wrong language and theme first.
    if (!loaded) return
    val theme by settings.theme.collectAsState()
    val language by settings.language.collectAsState()
    val dark = when (theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val stack = remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }

    val seriesServices = app.series
    val frameSource = rememberFrameSource()
    val scanController = rememberTargetScanController(app.modelPath)
    // Auto-save: every scan the shared controller completes is offered to the
    // recorder, whichever screen started it.
    val recorder = remember {
        SeriesRecorder(
            repository = seriesServices.repository,
            session = seriesServices.session,
            readCaliber = seriesServices.store::readCaliber,
            writeCaliber = seriesServices.store::writeCaliber,
            readTag = seriesServices.store::readTag,
            writeTag = seriesServices.store::writeTag,
            encodeJpeg = ::encodeSeriesJpeg,
            scope = scope,
        ).also { scanController.onSeriesDetected = it::onSeriesDetected }
    }
    DisposableEffect(recorder) { onDispose { recorder.dispose() } }

    CompositionLocalProvider(LocalAppLocale provides language) {
        key(language) {
            MarkeraTheme(dark) {
                SystemBarsForTheme(dark)
                AppNavHost(app, competition, settings, stack, frameSource, scanController, recorder)
            }
        }
    }
}

/**
 * Navigation root. The frame source, the scan controller (the single ONNX
 * session) and the recorder come from [MarkeraApp], above the language key and
 * the back stack, so both the free-marking screen and the competition wizard
 * share them and nothing heavy is rebuilt per screen switch or language change.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNavHost(
    app: AppServices,
    competition: CompetitionHost?,
    settings: AppSettings,
    stackState: MutableState<List<Screen>>,
    frameSource: FrameSource,
    scanController: TargetScanController,
    recorder: SeriesRecorder,
) {
    val seriesServices = app.series
    val scope = rememberCoroutineScope()

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

    var stack by stackState
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

    val menuHost = remember { MenuHost() }
    CompositionLocalProvider(
        LocalSeriesRecorder provides recorder,
        LocalToast provides toast,
        LocalSnackbar provides snackbarHostState,
        LocalMenuHost provides menuHost,
        LocalOpenSettings provides { push(Screen.Settings) },
    ) {
    Box(Modifier.fillMaxSize()) {
    when (val screen = current) {
        Screen.Home -> HomeScreen(
            onFreeMarking = { push(Screen.FreeMarking) },
            onCompetition = competition?.let { { push(Screen.Competition) } },
            onHistory = { push(Screen.History) },
            onOpenSeries = { push(Screen.SeriesDetail(it)) },
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
            shareFile = app.shareFile,
            onOpen = { push(Screen.SeriesDetail(it)) },
            onMarkera = { push(Screen.FreeMarking) },
        )

        Screen.Statistics -> StatsScreen(
            services = seriesServices,
            onBack = pop,
            onMarkera = { push(Screen.FreeMarking) },
        )

        Screen.Settings -> SettingsScreen(settings = settings, onBack = pop)

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
    MenuOverlay(menuHost)
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

    val tagDialogOpen by recorder.tagDialogOpen.collectAsState()
    if (tagDialogOpen) {
        val tag by recorder.tag.collectAsState()
        val cached by seriesServices.repository.series.collectAsState()
        TagDialog(
            selected = tag,
            // Every tag already in use, so one typed once is one tap forever after.
            known = cached.mapNotNull { it.tag }.distinct(),
            onSelect = recorder::selectTag,
            onDismiss = recorder::dismissTagDialog,
        )
    }
}

/**
 * The free-text tag for the next series: the tags already in use as rows, plus a
 * field for a new one. Its own composable rather than a [CaliberDialog] variant —
 * a fixed enum needs no text input.
 */
@Composable
internal fun TagDialog(
    selected: String?,
    known: List<String>,
    /** Null clears the tag. */
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    // No tags of the user's own yet: suggest two, in the UI language — once picked they are just tags.
    val offered = known.ifEmpty {
        listOf(stringResource(Res.string.series_tag_suggest_practice), stringResource(Res.string.series_tag_suggest_competition))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.series_tag_title)) },
        confirmButton = {
            TextButton(enabled = typed.isNotBlank(), onClick = { onSelect(typed) }) {
                Text(stringResource(Res.string.series_tag_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
        text = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // The leading null is the "clear it" row: untagged has exactly one
                    // representation, and it is null all the way to the server.
                    (listOf(null) + offered).forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tag) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == selected, onClick = { onSelect(tag) })
                            Text(
                                tag ?: stringResource(Res.string.series_tag_none),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = typed,
                        // Capped here: a longer tag is a 400 from the server.
                        onValueChange = { if (it.length <= MAX_TAG_LENGTH) typed = it },
                        label = { Text(stringResource(Res.string.series_tag_new)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

/** Tags the scanned series; shown automatically while the caliber is "-". */
@Composable
internal fun CaliberDialog(
    selected: Caliber,
    onSelect: (Caliber) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.series_caliber_title)) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
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
    /** Null hides the button: the platform supplied no competition flow. */
    onCompetition: (() -> Unit)?,
    onHistory: () -> Unit,
    onOpenSeries: (SeriesDto) -> Unit,
    onStatistics: () -> Unit,
    backendAuth: BackendAuth?,
    seriesServices: SeriesServices,
) {
    var showingHelp by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // No top bar here, so the menu floats in the top-left corner.
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
                Button(onClick = onFreeMarking, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.home_free_marking), style = MaterialTheme.typography.titleLarge)
                }
                // Signed out or nothing saved yet: the cache is empty and the card just isn't there.
                val cached by seriesServices.repository.series.collectAsState()
                val latest = cached.firstOrNull() // Series.sq orders by timestamp DESC
                if (latest != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(Res.string.home_latest_series, localStamp(latest.timestamp).take(10)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    SeriesCard(
                        series = latest,
                        ordinal = remember(cached) { cached.dayOrdinals() }[latest.id] ?: 1,
                        services = seriesServices,
                        thumbnails = remember { mutableStateMapOf() },
                        onClick = { onOpenSeries(latest) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeButton(Icons.Default.History, stringResource(Res.string.home_history), onHistory)
                    HomeButton(Icons.Default.BarChart, stringResource(Res.string.home_stats), onStatistics)
                    if (onCompetition != null) {
                        HomeButton(Icons.Default.EmojiEvents, stringResource(Res.string.home_competition), onCompetition)
                    }
                }
                Spacer(Modifier.height(16.dp))
                AccountRow(backendAuth, seriesServices)
            }
            AppMenu(
                items = listOf(
                    MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) {
                        showingHelp = true
                    },
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    // + the menu's own 4 dp = 16 dp from the edges.
                    .padding(12.dp),
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

/**
 * Backend sign-in as (busy, start): Home's account row and the signed-out states of
 * Historik/Statistik share it. A failure is toasted.
 */
@Composable
internal fun rememberBackendSignIn(services: SeriesServices): Pair<Boolean, () -> Unit> {
    val signIn = rememberSignIn(services.session)
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    return busy to {
        busy = true
        scope.launch {
            try {
                signIn()
                // A different account must not inherit the last one's cache.
                services.repository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                toast(t.message ?: t.toString())
            } finally {
                busy = false
            }
        }
    }
}

/** Markera-backend account: sign in to save scanned series, or sign out. */
@Composable
private fun AccountRow(auth: BackendAuth?, seriesServices: SeriesServices) {
    val (signingIn, signIn) = rememberBackendSignIn(seriesServices)
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
                        } catch (e: CancellationException) {
                            throw e
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

    if (busy || signingIn) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }
    if (auth == null) {
        TextButton(onClick = signIn) {
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

/** One of Home's equal-width list buttons; icon above the label so three fit in Swedish. */
@Composable
private fun RowScope.HomeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
