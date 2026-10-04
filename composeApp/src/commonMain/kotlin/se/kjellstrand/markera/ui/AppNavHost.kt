package se.kjellstrand.markera.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Modifier
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
import se.kjellstrand.markera.series.encodeSeriesJpeg
import se.kjellstrand.markera.series.rememberSignIn
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.ui.history.SeriesDetailScreen
import se.kjellstrand.markera.ui.history.SeriesHistoryScreen
import se.kjellstrand.markera.ui.markera.MarkeraScreen
import se.kjellstrand.markera.ui.markera.rememberFrameSource
import se.kjellstrand.markera.ui.markera.rememberTargetScanController
import se.kjellstrand.markera.ui.settings.AppSettings
import se.kjellstrand.markera.ui.settings.LocalAppLocale
import se.kjellstrand.markera.ui.settings.SettingsScreen
import se.kjellstrand.markera.ui.settings.SystemBarsForTheme
import se.kjellstrand.markera.ui.settings.ThemeMode
import se.kjellstrand.markera.ui.stats.StatsScreen
import se.kjellstrand.markera.ui.suggestion.SuggestionScreen
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
    data object Suggestion : Screen

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
            readCustomCalibers = seriesServices.store::readCustomCalibers,
            writeCustomCalibers = seriesServices.store::writeCustomCalibers,
        ).also {
            scanController.onSeriesDetected = it::onSeriesDetected
            scanController.onScanStarted = it::clear
        }
    }
    DisposableEffect(recorder) { onDispose { recorder.dispose() } }

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
    CompositionLocalProvider(LocalAppLocale provides applied) {
        key(applied) {
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
    // A new message replaces the one on screen instead of queueing behind it.
    val toast: (String) -> Unit = { msg ->
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    // Not rememberBackendSignIn: LocalToast is only provided further down.
    val signIn = rememberSignIn(seriesServices.session)
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
    val queuedText = stringResource(Res.string.series_status_queued)
    val signInText = stringResource(Res.string.home_sign_in)
    val expiredText = stringResource(Res.string.session_expired)
    LaunchedEffect(recorder) {
        recorder.status.collect { status ->
            val message = when (status) {
                is SaveStatus.Saved -> savedText
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
        LocalOpenSuggestion provides { push(Screen.Suggestion) },
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

        Screen.Suggestion -> SuggestionScreen(api = seriesServices.api, onBack = pop)

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
                t.userMessage()?.let { toast(getString(it)) }
            } finally {
                busy = false
            }
        }
    }
}

