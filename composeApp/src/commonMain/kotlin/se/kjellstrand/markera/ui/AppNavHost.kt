package se.kjellstrand.markera.ui

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.AppServices
import se.kjellstrand.markera.series.SaveStatus
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesRecorder
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.LocalClaimController
import se.kjellstrand.markera.series.rememberSignIn
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendSessionRepository
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.ImportFrameSource
import se.kjellstrand.markera.ui.markera.ImportQueue
import se.kjellstrand.markera.ui.markera.PickedImage
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.ui.history.SeriesDetailScreen
import se.kjellstrand.markera.ui.stats.InsightsDay
import se.kjellstrand.markera.ui.stats.insightsDayFor
import se.kjellstrand.markera.ui.history.SeriesHistoryScreen
import se.kjellstrand.markera.ui.markera.MarkeraScreen
import se.kjellstrand.markera.ui.markera.rememberFrameSource
import se.kjellstrand.markera.ui.markera.DeviceScanEngine
import se.kjellstrand.markera.ui.settings.AppSettings
import se.kjellstrand.markera.ui.settings.LocalAppSettings
import se.kjellstrand.markera.ui.settings.LocalAppLocale
import se.kjellstrand.markera.ui.settings.SettingsScreen
import se.kjellstrand.markera.ui.settings.SystemBarsForTheme
import se.kjellstrand.markera.ui.settings.ThemeMode
import se.kjellstrand.markera.ui.settings.layoutDirectionFor
import se.kjellstrand.markera.ui.stats.StatsScreen
import se.kjellstrand.markera.ui.suggestion.SuggestionScreen
import se.kjellstrand.markera.ui.theme.MarkeraTheme

/** Shows a short message; the host lives in [AppNavHost], above every screen. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { error("no toast host") }

/** UI tests: [AppServices.signInStandIn], answering every Sign in button. */
val LocalSignInStandIn = staticCompositionLocalOf<(suspend (BackendSessionRepository) -> BackendAuth)?> { null }

/** The same host as [LocalToast], for a message with an action (Undo). */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar host") }

/** The app's screens; a simple list-backed stack, no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object FreeMarking : Screen
    data object History : Screen
    data object Statistics : Screen

    /** Statistics opened on Insights for one caliber and period, from the Serie page or Home's card. */
    data class StatisticsDay(val day: InsightsDay) : Screen
    data object Settings : Screen
    data object Suggestion : Screen

    /**
     * One saved series, editable. Carries the DTO the history row already has, and
     * [ids] — the list it was opened from, newest first — for the previous/next arrows.
     */
    data class SeriesDetail(val series: SeriesDto, val ids: List<Long> = emptyList()) : Screen

    /** Picked images scanned one by one; the queue lives as long as the entry. */
    class Import(val queue: ImportQueue<PickedImage>) : Screen
}

/**
 * The app root both platforms call: loads the Settings choices, then applies the
 * theme and the language around [AppNavHost]. A language change re-keys the whole
 * tree (that is what makes the strings re-resolve), so the back stack is held
 * here, above the key, and the user stays on the Settings screen. So are the
 * heavy objects — the frame source, the scan controller (the single ONNX
 * session) and the series recorder — so a language change neither rebuilds
 * them nor cancels an in-flight save or drops the pending series. The stack,
 * the controller and the recorder live in [AppViewModel], so an activity
 * recreation keeps them too; the stack is also saved state, for process death.
 */
@Composable
fun MarkeraApp(app: AppServices) {
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
    // Survives a configuration change; only what it holds may outlive the activity.
    val held = viewModel {
        AppViewModel(app.series, app.scanStandIns?.engine?.invoke() ?: DeviceScanEngine(app.modelPath))
    }
    // The live stack after a configuration change, the saved text after process death.
    val stack = rememberSaveable(saver = backStackSaver { held.stack }) {
        held.stack ?: mutableStateOf<List<Screen>>(listOf(Screen.Home))
    }
    held.stack = stack
    // Each entry's own saved UI state (History's scroll and filter) while a screen above it shows.
    val entryStates = rememberSaveableStateHolder()

    // The camera is always composed (it is a remembered platform resource); a UI test's stand-in replaces it.
    val deviceFrameSource = rememberFrameSource()
    val frameSource = app.scanStandIns?.frameSource ?: deviceFrameSource
    val scanController = held.scanController
    val recorder = held.recorder

    // The locale is process-wide and resources read it while composing: set it after a
    // commit, then key the tree on the language actually applied.
    var applied by remember { mutableStateOf<String?>(null) }
    var localeReady by remember { mutableStateOf(false) }
    LaunchedEffect(language) {
        LocalAppLocale.apply(language)
        applied = language
        localeReady = true
    }
    if (!localeReady) return
    // A language picked in-app sets the direction too (the system's own language already does).
    val direction = layoutDirectionFor(applied) ?: LocalLayoutDirection.current
    CompositionLocalProvider(
        LocalAppLocale provides applied,
        LocalAppSettings provides settings,
        LocalLayoutDirection provides direction,
    ) {
        key(applied) {
            MarkeraTheme(dark) {
                SystemBarsForTheme(dark)
                AppNavHost(app, settings, stack, entryStates, frameSource, scanController, recorder)
            }
        }
    }
}

/**
 * Navigation root. The frame source, the scan controller (the single ONNX
 * session) and the recorder come from [MarkeraApp], above the language key and
 * the back stack, so the scan screens share them and nothing heavy is rebuilt per
 * screen switch or language change.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNavHost(
    app: AppServices,
    settings: AppSettings,
    stackState: MutableState<List<Screen>>,
    entryStates: SaveableStateHolder,
    frameSource: FrameSource,
    scanController: TargetScanController,
    recorder: SeriesRecorder,
) {
    val seriesServices = app.series
    val scope = rememberCoroutineScope()

    val claimController = remember(seriesServices) {
        LocalClaimController(seriesServices.repository, seriesServices.session, scope)
    }
    DisposableEffect(claimController) { onDispose { claimController.dispose() } }
    LocalClaimDialog(claimController)

    val snackbarHostState = remember { SnackbarHostState() }
    // A new message replaces the one on screen instead of queueing behind it.
    val toast: (String) -> Unit = { msg ->
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // Not rememberBackendSignIn: LocalToast is only provided further down.
    val realSignIn = rememberSignIn(seriesServices.session)
    val signIn: suspend () -> BackendAuth = app.signInStandIn?.let { { it(seriesServices.session) } } ?: realSignIn
    val signInFromToast: () -> Unit = {
        scope.launch {
            try {
                signIn()
                seriesServices.repository.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                t.userMessage()?.let { toast(getString(it)) }
            }
        }
    }
    val signInAction = stringResource(Res.string.snackbar_sign_in)

    // The only save feedback, for both screens: one toast per outcome. Collected
    // (not read from the current value), so a recomposition never repeats it.
    val savedText = stringResource(Res.string.series_status_saved)
    val savedOnPhoneText = stringResource(Res.string.series_status_phone)
    val queuedText = stringResource(Res.string.series_status_queued)
    val signInText = stringResource(Res.string.home_sign_in)
    val expiredText = stringResource(Res.string.session_expired)
    LaunchedEffect(recorder) {
        recorder.status.collect { status ->
            val message = when (status) {
                is SaveStatus.Saved -> savedText
                is SaveStatus.SavedOnPhone -> savedOnPhoneText
                SaveStatus.Queued -> queuedText
                is SaveStatus.Failed -> status.error.userMessage()
                    ?.let { getString(Res.string.series_status_failed, getString(it)) }
                    ?: return@collect
                SaveStatus.SignedOut -> {
                    // Nothing was saved: offer the way to fix that right there.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(signInText, actionLabel = signInAction)
                        if (result == SnackbarResult.ActionPerformed) signInFromToast()
                    }
                    return@collect
                }
                else -> return@collect
            }
            toast(message)
        }
    }
    LaunchedEffect(seriesServices.repository) {
        seriesServices.repository.sessionExpired.collect { toast(expiredText) }
    }

    var stack by stackState
    val current = stack.last()
    val currentKey = backStackKey(stack.lastIndex, current)
    // A dropped entry's saved state goes with it, so the next screen at that depth starts fresh.
    val forgetTop: () -> Unit = { entryStates.removeState(backStackKey(stack.lastIndex, stack.last())) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = {
        if (stack.size > 1) {
            forgetTop()
            stack = stack.dropLast(1)
        }
    }
    // Swaps the top entry, so stepping between series leaves one Back to the list.
    val replace: (Screen) -> Unit = {
        forgetTop()
        stack = stack.dropLast(1) + it
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = stack.size > 1,
        onBackCompleted = { pop() },
    )

    // The cache is published before the delta lands, so History has rows at once.
    LaunchedEffect(Unit) {
        seriesServices.session.restore()
        seriesServices.repository.refresh()
        // Every later sign-in (any provider, any screen) loads that account's series. The
        // refresh a sign-in button runs itself can come before the held-auth gate publishes
        // the login, and then saw no account: Home stayed empty until a screen refreshed.
        seriesServices.session.auth.map { it?.userId }.distinctUntilChanged().drop(1)
            .filterNotNull().collect { seriesServices.repository.refresh() }
    }
    val backendAuth by seriesServices.session.auth.collectAsState()

    val menuHost = remember { MenuHost() }
    CompositionLocalProvider(
        LocalSeriesRecorder provides recorder,
        LocalToast provides toast,
        LocalSnackbar provides snackbarHostState,
        LocalMenuHost provides menuHost,
        LocalOpenSettings provides { push(Screen.Settings) },
        LocalOpenSuggestion provides { push(Screen.Suggestion) },
        LocalSignInStandIn provides app.signInStandIn,
    ) {
    Box(Modifier.fillMaxSize()) {
    entryStates.SaveableStateProvider(currentKey) {
    when (val screen = current) {
        Screen.Home -> HomeScreen(
            onFreeMarking = { push(Screen.FreeMarking) },
            onHistory = { push(Screen.History) },
            // From Home the arrows walk every series.
            onOpenSeries = {
                push(Screen.SeriesDetail(it, seriesServices.repository.visibleSeries.value.map { s -> s.id }))
            },
            onStatistics = { push(Screen.Statistics) },
            onOpenInsights = { push(Screen.StatisticsDay(it)) },
            onImport = { images, auto -> push(Screen.Import(ImportQueue(images, auto))) },
            backendAuth = backendAuth,
            seriesServices = seriesServices,
            pickStandIn = app.scanStandIns?.pickImages,
        )

        Screen.FreeMarking -> MarkeraScreen(
            frameSource = frameSource,
            scanController = scanController,
            onBack = pop,
            cameraPermission = app.scanStandIns?.cameraPermission,
        )

        is Screen.Import -> MarkeraScreen(
            frameSource = remember(screen) { ImportFrameSource() },
            scanController = scanController,
            onBack = pop,
            importQueue = screen.queue,
        )

        // Both list screens read the cached flow; they only ask for a delta on open.
        Screen.History -> SeriesHistoryScreen(
            services = seriesServices,
            onBack = pop,
            shareFile = app.shareFile,
            onOpen = { series, ids -> push(Screen.SeriesDetail(series, ids)) },
            onMarkera = { push(Screen.FreeMarking) },
        )

        Screen.Statistics -> StatsScreen(
            services = seriesServices,
            onBack = pop,
            onMarkera = { push(Screen.FreeMarking) },
        )

        is Screen.StatisticsDay -> key(screen.day) {
            StatsScreen(
                services = seriesServices,
                onBack = pop,
                onMarkera = { push(Screen.FreeMarking) },
                insightsDay = screen.day,
            )
        }

        Screen.Settings -> SettingsScreen(settings = settings, seriesServices = seriesServices, onBack = pop)

        Screen.Suggestion -> SuggestionScreen(api = seriesServices.api, onBack = pop)

        // Keyed so every bit of screen state starts fresh when the arrows step on.
        is Screen.SeriesDetail -> key(screen.series.id) {
            SeriesDetailScreen(
                initial = screen.series,
                services = seriesServices,
                onBack = pop,
                ids = screen.ids,
                onOpen = { replace(Screen.SeriesDetail(it, screen.ids)) },
                // The series as it is now: the page may just have corrected its caliber.
                onOpenInsights = { date ->
                    val all = seriesServices.repository.visibleSeries.value
                    val now = all.firstOrNull { it.id == screen.series.id } ?: screen.series
                    push(Screen.StatisticsDay(insightsDayFor(now, date, all, sights = settings.sightSetups.value)))
                },
            )
        }
    }
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
        val custom by recorder.customCalibers.collectAsState()
        CaliberDialog(
            selected = caliber,
            custom = custom,
            onSelect = recorder::selectCaliber,
            onAdd = { label, mm -> recorder.addCaliber(label, mm)?.let(recorder::selectCaliber) },
            onRemove = recorder::removeCaliber,
            onDismiss = recorder::dismissCaliberDialog,
        )
    }

    val tagDialogOpen by recorder.tagDialogOpen.collectAsState()
    if (tagDialogOpen) {
        val tag by recorder.tag.collectAsState()
        val cached by seriesServices.repository.visibleSeries.collectAsState()
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
 * Backend sign-in as (busy, start): Home's account row and the signed-out states of
 * Historik/Statistik share it. A failure is toasted.
 */
@Composable
internal fun rememberBackendSignIn(services: SeriesServices): Pair<Boolean, () -> Unit> {
    val real = rememberSignIn(services.session)
    val standIn = LocalSignInStandIn.current
    val signIn: suspend () -> BackendAuth = standIn?.let { { it(services.session) } } ?: real
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
                t.userMessage()?.let { toast(getString(it)) }
            } finally {
                busy = false
            }
        }
    }
}

