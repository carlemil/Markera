package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import se.kjellstrand.markera.ui.history.HistoryFilter
import se.kjellstrand.markera.ui.markera.CameraPermissionState
import se.kjellstrand.markera.ui.markera.DEBUG_DETECTIONS_TAG
import se.kjellstrand.markera.ui.markera.GUIDE_SWATCH_TAG
import se.kjellstrand.markera.ui.markera.SWATCHES
import se.kjellstrand.markera.ui.settings.DEFAULT_GUIDE_COLOR
import androidx.compose.ui.graphics.toArgb
import se.kjellstrand.markera.ui.history.decodeHistoryFilter
import se.kjellstrand.markera.ui.history.decodeOpenDays
import se.kjellstrand.markera.ui.history.encode
import se.kjellstrand.markera.series.stats.DatePreset
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.LocalMark
import se.kjellstrand.markera.series.EncodedImage
import se.kjellstrand.markera.series.SuggestionRequest
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.SeriesRequest
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.ui.markera.SCAN_VIEWPORT_TAG
import se.kjellstrand.markera.ui.markera.ImageSource
import se.kjellstrand.markera.ui.markera.LoadedImage
import se.kjellstrand.markera.ui.markera.PickedImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.state.ToggleableState
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.series.seriesJson
import se.kjellstrand.markera.ui.HOME_INSIGHT_TAG
import se.kjellstrand.markera.ui.MarkeraApp
import se.kjellstrand.markera.ui.MENU_OVERLAY_TAG
import se.kjellstrand.markera.ui.TOP_BAR_TAG
import se.kjellstrand.markera.ui.history.COMPARISON_DAY_LINE_TAG
import se.kjellstrand.markera.ui.history.COMPARISON_LINE_TAG
import se.kjellstrand.markera.ui.history.SERIES_THUMBNAIL_TAG
import se.kjellstrand.markera.ui.stats.SIGHT_CLICK_TAG
import se.kjellstrand.markera.ui.stats.STATS_PHOTO_TAG
import se.kjellstrand.markera.ui.stats.SIGHT_CORRECTION_TAG
import se.kjellstrand.markera.series.stats.decodeSightSetups

/**
 * The whole app (MarkeraApp, real navigation, repository and SQLite) against a fake
 * backend, on the phone. Each test is one of the regressions seen on 1.14.0 or a
 * sanity walk through the main screens. Nothing touches the app's real data: the
 * database is in memory, the login store too, and the directories are per test.
 *
 * Release rule: these must pass before any build is uploaded to a store.
 *   ANDROID_SERIAL=<serial> ./gradlew :composeApp:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=se.kjellstrand.markera.AppUiTest
 */
@OptIn(ExperimentalTime::class, ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppUiTest {
    // A ComponentActivity host, as createComposeRule() has, but reachable: Back on Home must leave it.
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "ui-test-${System.nanoTime()}")
    private val requests = CopyOnWriteArrayList<String>()
    /** Links the app opened; never a real browser. */
    private val opened = CopyOnWriteArrayList<String>()
    /** Files the app handed to the share sheet. */
    private val shared = CopyOnWriteArrayList<String>()
    /** Every series the app POSTed, as the server got it. */
    private val posted = CopyOnWriteArrayList<SeriesRequest>()
    /** What those posts became on the fake server, which GET /series lists newest first. */
    private val saved = java.util.concurrent.ConcurrentSkipListMap<Long, SeriesDto>()
    /** Every edit PUT, by series id; [edited] is what each made of its series on the fake server. */
    private val puts = CopyOnWriteArrayList<Pair<Long, SeriesRequest>>()
    private val edited = java.util.concurrent.ConcurrentHashMap<Long, SeriesDto>()
    /** Series the app DELETEd; GET /series leaves them out. */
    private val gone = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    /** Series whose photo download fails. */
    private val photoless = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    /** Set, POST /series answers this instead of taking the series. */
    @Volatile private var postAnswer: HttpStatusCode? = null
    /** Set, the server no longer knows the token: every signed-in request is a 401. */
    @Volatile private var tokenExpired = false
    /** Set, every request fails as with no network. */
    @Volatile private var offline = false
    /** Every suggestion sent, and whether it came with the token. */
    private val suggestions = CopyOnWriteArrayList<Pair<SuggestionRequest, Boolean>>()
    /** Set, POST /suggestions answers this; null takes it. */
    @Volatile private var suggestionAnswer: HttpStatusCode? = null

    /** One series shot an hour ago, with a photo, as the fake server holds it. */
    /**
     * An hour ago, but still today: just after local midnight an hour back is yesterday, and the
     * tests that put [serverSeries] on "today" and other series on the days before failed then.
     */
    private fun earlierToday(): kotlin.time.Instant {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val today = kotlin.time.Instant.fromEpochMilliseconds(now.toEpochMilliseconds()).toLocalDateTime(zone).date
        val midnight = kotlin.time.Instant.fromEpochMilliseconds(today.atStartOfDayIn(zone).toEpochMilliseconds())
        val sinceMidnight = now - midnight
        return if (sinceMidnight > 2.hours) now - 1.hours else midnight + sinceMidnight / 2
    }

    private val serverSeries = SeriesDto(
        id = 501,
        timestamp = earlierToday().toString(),
        caliber = "22lr",
        holes = listOf(
            HoleDto(500.0, 500.0, 10, false, 3.0),
            HoleDto(520.0, 480.0, 9, false, 20.0),
            HoleDto(470.0, 530.0, 9, false, 22.0),
            HoleDto(560.0, 450.0, 8, false, 40.0),
            HoleDto(420.0, 560.0, 7, false, 70.0),
        ),
        hasImage = true,
        imageWidth = 1000,
        imageHeight = 1000,
        geometry = GEOMETRY,
    )

    /**
     * Three older series, one a day, grouped about 45 mm right of the centre: with the one above,
     * 20 shots of 22lr, enough for Insights to find the offset.
     */
    private val offsetSeries = (1..3).map { day ->
        SeriesDto(
            id = 501L + day,
            timestamp = (Clock.System.now() - (24 * day).hours).toString(),
            caliber = "22lr",
            holes = listOf(0.0 to 0.0, 12.0 to -8.0, -10.0 to 10.0, 8.0 to 14.0, -12.0 to -6.0).map { (dx, dy) ->
                HoleDto(545.0 + dx + day, 500.0 + dy, 8, false, 45.0)
            },
            hasImage = true,
            imageWidth = 1000,
            imageHeight = 1000,
            geometry = GEOMETRY,
        )
    }
    /** What GET /series answers; a test fills it before launch. */
    private var onServer: List<SeriesDto> = listOf(serverSeries) + offsetSeries

    private val jpeg: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 180, 120)) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** The backend's answers; everything but sign-in wants the token, as the real one does. */
    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        requests += "${request.method.value} $path"
        if (offline) throw java.io.IOException("offline")
        val authorized = request.headers[HttpHeaders.Authorization] == "Bearer $TOKEN"
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when {
            request.method == HttpMethod.Post && path == "/auth/dev" ->
                respond("""{"token":"$TOKEN","userId":$USER}""", HttpStatusCode.OK, json)
            // Open to signed-out users, as on the real server.
            request.method == HttpMethod.Post && path == "/suggestions" -> suggestionAnswer?.let { respond("", it) } ?: run {
                suggestions += seriesJson.decodeFromString<SuggestionRequest>((request.body as TextContent).text) to authorized
                respond("""{"id":${suggestions.size}}""", HttpStatusCode.Created, json)
            }
            request.method == HttpMethod.Delete -> {
                Regex("""/series/(\d+)""").matchEntire(path)?.let { gone += it.groupValues[1].toLong() }
                respond("", HttpStatusCode.NoContent)
            }
            !authorized || tokenExpired -> respond("", HttpStatusCode.Unauthorized)
            request.method == HttpMethod.Get && path.matches(Regex("""/series/\d+/image""")) ->
                if (path.split('/')[2].toLong() in photoless) respond("", HttpStatusCode.NotFound)
                else respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            request.method == HttpMethod.Get && path == "/series" ->
                respond(seriesJson.encodeToString(serverList()), HttpStatusCode.OK, json)
            request.method == HttpMethod.Put && path.matches(Regex("""/series/\d+""")) -> {
                val id = path.split('/')[2].toLong()
                val req = seriesJson.decodeFromString<SeriesRequest>((request.body as TextContent).text)
                puts += id to req
                serverList().firstOrNull { it.id == id }?.let {
                    edited[id] = it.copy(caliber = req.caliber, holes = req.holes.filterNot { h -> h.deleted }, tag = req.tag)
                }
                respond("", HttpStatusCode.NoContent)
            }
            request.method == HttpMethod.Post && path == "/series" && postAnswer != null ->
                respond("", postAnswer!!)
            request.method == HttpMethod.Post && path == "/series" -> {
                val req = seriesJson.decodeFromString<SeriesRequest>((request.body as TextContent).text)
                posted += req
                val id = 900L + posted.size
                saved[id] = SeriesDto(id, req.timestamp, req.caliber, req.holes.filterNot { it.deleted }, geometry = req.geometry, tag = req.tag)
                respond("""{"id":$id}""", HttpStatusCode.Created, json)
            }
            request.method == HttpMethod.Post && path.matches(Regex("""/series/\d+/image""")) -> {
                val id = path.split('/')[2].toLong()
                saved[id] = saved.getValue(id).copy(
                    hasImage = true,
                    imageWidth = request.url.parameters["width"]?.toInt(),
                    imageHeight = request.url.parameters["height"]?.toInt(),
                )
                respond("", HttpStatusCode.NoContent)
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    /** What the fake server holds now: the posted series, newest first, then [onServer], as edited. */
    private fun serverList() = (saved.values.reversed() + onServer).filterNot { it.id in gone }.map { edited[it.id] ?: it }

    private class MemoryStore(
        var auth: BackendAuth?,
        var shootingHand: String? = null,
        var insightsEnabled: Boolean? = null,
        var sightSetups: String? = null,
        var dismissedInsights: String? = null,
        var caliber: Caliber = Caliber.DEFAULT,
        // English unless a test says otherwise, whatever the phone's language, so the texts below match.
        var language: String? = "en",
        var theme: String? = null,
        var historyFilter: String? = null,
        var openDays: String? = null,
        var guideColor: Long? = null,
    ) : BackendTokenStore {
        override suspend fun readGuideColor() = guideColor
        override suspend fun writeGuideColor(argb: Long) { guideColor = argb }
        override suspend fun readCaliber() = caliber
        override suspend fun writeCaliber(caliber: Caliber) { this.caliber = caliber }
        override suspend fun read() = auth
        override suspend fun write(auth: BackendAuth) { this.auth = auth }
        override suspend fun clear() { auth = null }
        override suspend fun readLanguage() = language
        override suspend fun writeLanguage(value: String?) { language = value }
        override suspend fun readTheme() = theme
        override suspend fun readHistoryFilter() = historyFilter
        override suspend fun writeHistoryFilter(value: String) { historyFilter = value }
        override suspend fun readOpenDays() = openDays
        override suspend fun writeOpenDays(value: String) { openDays = value }
        override suspend fun writeTheme(value: String) { theme = value }
        override suspend fun readShootingHand() = shootingHand
        override suspend fun writeShootingHand(value: String) { shootingHand = value }
        override suspend fun readInsightsEnabled() = insightsEnabled
        override suspend fun writeInsightsEnabled(value: Boolean) { insightsEnabled = value }
        override suspend fun readSightSetups() = sightSetups
        override suspend fun writeSightSetups(value: String) { sightSetups = value }
        override suspend fun readDismissedInsights() = dismissedInsights
        override suspend fun writeDismissedInsights(value: String) { dismissedInsights = value }
    }

    private fun launch(
        signedIn: Boolean,
        store: MemoryStore = MemoryStore(null),
        scanEngine: FakeScanEngine = FakeScanEngine(),
        /** What Import's picker hands back, for either source; null leaves the system picker. */
        picks: ((ImageSource) -> List<PickedImage>)? = null,
        /** The scan screen's camera permission; null grants it, as the fake camera needs none. */
        cameraPermission: (@androidx.compose.runtime.Composable () -> CameraPermissionState)? = null,
        seed: (MarkeraDb) -> Unit = {},
    ): SeriesServices {
        store.auth = if (signedIn) BackendAuth(TOKEN, USER, "dev") else null
        val driver = AndroidSqliteDriver(MarkeraDb.Schema, context, null)
        seed(MarkeraDb(driver))
        val services = SeriesServices(
            engine = engine,
            baseUrl = "http://fake",
            store = store,
            driver = driver,
            cacheDir = Path(File(root, "cache").apply { mkdirs() }.path),
            filesDir = Path(File(root, "files").apply { mkdirs() }.path),
        )
        // Never the phone's camera or models: the drawn target and its stand-in detectors.
        val app = AppServices(
            series = services,
            modelPath = "no-model-in-ui-tests",
            shareFile = { shared += it },
            scanStandIns = ScanStandIns(FakeFrameSource(), picks, cameraPermission) { scanEngine },
            // Every Sign in button: the dev login, with no provider chooser or Credential Manager.
            signInStandIn = { it.signInDev("ui-test") },
        )
        val links = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        rule.setContent { CompositionLocalProvider(LocalUriHandler provides links) { MarkeraApp(app) } }
        return services
    }

    @After fun cleanUp() { root.deleteRecursively() }

    private fun SemanticsNodeInteractionsProvider.exists(matcher: SemanticsMatcher) =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, what: String) {
        try {
            rule.waitUntil(TIMEOUT_MS) { rule.exists(matcher) }
        } catch (e: Throwable) {
            // Every root: a dialog is a second one, and onRoot() would throw on it.
            // The printout must not hide e: with no hierarchy at all it throws too.
            val tree = runCatching { rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString() }
                .getOrElse { "(no tree: $it)" }
            throw AssertionError("Never showed $what; requests: $requests $tree", e)
        }
    }

    /** The system Back key, as the user presses it. */
    private fun pressBack() {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun waitForText(text: String) = waitFor(hasText(text, substring = true, ignoreCase = true), "\"$text\"")
    private val thumbnail = hasTestTag(SERIES_THUMBNAIL_TAG)

    // ---- the 1.14.0 regressions ----

    /** Signed in at start: Home loads the week strip and the latest series, with its photo. */
    @Test fun homeLoadsSeriesAndPhotoAtStartup() {
        launch(signedIn = true)
        waitForText("series in 7 days")
        waitForText("Latest series")
        waitFor(thumbnail, "the latest series' photo")
    }

    // ---- series without a complete photo are hidden ----

    /**
     * [serverSeries] (22lr, with its photo) plus two newer series that lack a complete photo: a 9mm
     * one with no photo at all and a 308 one whose photo has no frame size. Both carry geometry, so
     * they would be plotted and listed if they were not hidden.
     */
    private fun withPhotolessSeries() {
        val now = Clock.System.now()
        onServer = listOf(
            serverSeries.copy(id = 701, caliber = "9mm", timestamp = (now - 1.minutes).toString(), hasImage = false),
            serverSeries.copy(id = 702, caliber = "308", timestamp = (now - 2.minutes).toString(),
                imageWidth = null, imageHeight = null),
            serverSeries,
        )
    }

    private fun assertPhotolessHidden(where: String) {
        assertFalse("A series without a photo must be hidden on $where", rule.exists(hasText("9mm")))
        assertFalse("A series without its frame size must be hidden on $where", rule.exists(hasText("308")))
    }

    /** Home's latest series is the newest one with a photo, not the newer photo-less ones. */
    @Test fun homeHidesSeriesWithoutAPhoto() {
        withPhotolessSeries()
        launch(signedIn = true)
        waitForText("series in 7 days")
        waitFor(thumbnail, "the latest series' photo")
        waitForText("22lr")
        rule.waitForIdle()
        assertPhotolessHidden("Home")
    }

    /** History lists only the series with a photo. */
    @Test fun historyHidesSeriesWithoutAPhoto() {
        withPhotolessSeries()
        launch(signedIn = true)
        waitForText("series in 7 days")
        rule.onNodeWithText("History").performClick()
        waitFor(thumbnail, "a photo in History")
        waitForText("22lr")
        rule.waitForIdle()
        assertPhotolessHidden("History")
    }

    /** Statistics' caliber filter and plots leave them out too. */
    @Test fun statisticsHideSeriesWithoutAPhoto() {
        withPhotolessSeries()
        launch(signedIn = true)
        waitForText("series in 7 days")
        rule.onNodeWithText("Statistics").performClick()
        // Only the photographed 22lr series is plotted: its 5 hits, not the 15 of all three.
        waitFor(hasContentDescription("Target with 5 hits from 1 series"), "the target with only the photographed series")
    }

    /**
     * Signing in after start must load that account's series on Home, without leaving the
     * screen. 1.14.0 refreshed before the login was published and Home stayed empty.
     */
    @Test fun homeLoadsSeriesAfterSigningIn() {
        val services = launch(signedIn = false)
        waitForText("Mark")
        rule.waitForIdle()
        assertFalse("Signed out Home must show no series", rule.exists(hasText("series in 7 days")))
        assertFalse("Signed out with no series: no insight card", rule.exists(insightCard))
        runBlocking { services.session.signInDev("ui-test") }
        waitForText("series in 7 days")
        waitFor(thumbnail, "the latest series' photo after sign-in")
    }

    /**
     * Home's own Sign in button, with series on the server: the card leaves Home as the login
     * is published, which cancelled the refresh it had started, and the sign-in's refresh had
     * joined that one. Home stayed empty until History refreshed again.
     */
    @Test fun homeLoadsSeriesAfterTheSignInButton() {
        launch(signedIn = false)
        waitForText("Mark")
        click(button("Sign in"))
        waitForText("series in 7 days")
        waitFor(thumbnail, "the latest series' photo after the Sign in button")
    }

    /**
     * A backup restore brings the series database back without the login or the photos.
     * 1.14.0 listed those rows with blank photos; signed out, nothing of the server's shows.
     */
    @Test fun restoredCacheIsNotShownWhileSignedOut() {
        launch(signedIn = false) { db ->
            db.seriesQueries.upsert(serverSeries.id, serverSeries.timestamp, seriesJson.encodeToString(serverSeries))
            db.seriesQueries.setLastSync(USER.toString(), "2026-11-12 10:00:00.000")
        }
        waitForText("Mark")
        rule.waitForIdle()
        assertFalse("A restored series must not show on Home", rule.exists(hasText("Latest series", substring = true)))
        rule.onNodeWithText("History").performClick()
        waitForText("Sign in to see your history")
        assertFalse(rule.exists(hasText("22lr")))
    }

    // ---- sanity walk ----

    /** Home → History (photo shown) → back → Statistics → back → Settings → back. */
    @Test fun mainScreensOpenAndGoBack() {
        launch(signedIn = true)
        waitForText("series in 7 days")

        rule.onNodeWithText("History").performClick()
        waitForText("22lr")
        waitFor(thumbnail, "a photo in History")
        pressBack()
        waitForText("series in 7 days")

        rule.onNodeWithText("Statistics").performClick()
        waitForText("Target")
        waitForText("Trend")
        waitForText("Drift")
        // Insights: the fake series sit right of the centre, so the offset finding and its target show.
        rule.onNodeWithText("Insights").performClick()
        waitForText("Your group sits")
        waitForText("to the right over 20 shots")
        waitForText("Often caused by")
        waitFor(hasContentDescription("Insights target with 20 shots", substring = true), "the Insights target")
        // Tapping the finding picks it (it highlights the arrow on the target).
        rule.onNode(hasText("Your group sits", substring = true)).performScrollTo().performClick()
        // The card merges its texts and its selected state into one node: the merged tree.
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText("Your group sits", substring = true) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        pressBack()
        waitForText("series in 7 days")

        rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true)[0].performClick()
        waitForText("Settings")
        rule.onAllNodesWithText("Settings")[0].performClick()
        waitForText("Language")
        waitForText("Sign out")
        pressBack()
        waitForText("series in 7 days")
    }

    // ---- coaching: shooter profile and the Insights help ----

    private fun openSettings() {
        rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true)[0].performClick()
        waitForText("Settings")
        rule.onAllNodesWithText("Settings")[0].performClick()
        waitForText("Shooting hand")
    }

    /** One option of a Settings either/or row, by its exact label. */
    private fun option(label: String) = hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    private fun waitForSelected(label: String) = waitFor(option(label) and isSelected(), "\"$label\" picked")
    private fun pick(label: String) = rule.onNode(option(label)).performScrollTo().performClick()

    /** A fresh install shoots right-handed with Insights on; picking Left and Off is saved and stays picked. */
    @Test fun settingsSaveShootingHandAndInsights() {
        val store = MemoryStore(null)
        launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        openSettings()
        waitForSelected("Right")
        waitForSelected("On")

        pick("Left")
        waitForSelected("Left")
        pick("Off")
        waitForSelected("Off")
        rule.waitUntil(TIMEOUT_MS) { store.shootingHand == "LEFT" && store.insightsEnabled == false }
        assertFalse("Right must no longer be picked", rule.exists(option("Right") and isSelected()))
        assertFalse("On must no longer be picked", rule.exists(option("On") and isSelected()))

        // Leaving Settings and coming back shows the same choice.
        pressBack()
        waitForText("series in 7 days")
        openSettings()
        waitForSelected("Left")
        waitForSelected("Off")

        // And both can be switched back.
        pick("Right")
        waitForSelected("Right")
        pick("On")
        waitForSelected("On")
        rule.waitUntil(TIMEOUT_MS) { store.shootingHand == "RIGHT" && store.insightsEnabled == true }
    }

    /** A relaunch reads the saved choices: Settings opens with Left and Off picked. */
    @Test fun settingsShowSavedShootingHandAndInsights() {
        launch(signedIn = true, store = MemoryStore(null, shootingHand = "LEFT", insightsEnabled = false))
        waitForText("series in 7 days")
        openSettings()
        waitForSelected("Left")
        waitForSelected("Off")
    }

    /** A saved value the app does not know (an older or newer build's) falls back to right-handed. */
    @Test fun settingsUnknownShootingHandFallsBackToRight() {
        launch(signedIn = false, store = MemoryStore(null, shootingHand = "BOTH"))
        waitForText("Mark")
        openSettings()
        waitForSelected("Right")
        waitForSelected("On")
    }

    /** The Statistics help explains Insights and says it is a hint, not a coach. */
    @Test fun statisticsHelpExplainsInsights() {
        launch(signedIn = true)
        waitForText("series in 7 days")
        rule.onNodeWithText("Statistics").performClick()
        waitForText("Target")
        // Through the menu: the round help button on the target needs a series with a fitted ring.
        rule.waitForIdle()
        rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true).onLast().performClick()
        waitForText("Help")
        rule.onAllNodesWithText("Help").onLast().performClick()
        waitForText("About the measures")
        waitForText("It is a hint, not a coach")
        // The help's own heading, and the Insights tab behind the dialog.
        assertEquals(2, rule.onAllNodes(hasText("Insights"), useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Close").performClick()
        waitForText("Target")
    }

    /** Insights switched off in Settings: Statistics shows its three tabs and no Insights tab. */
    @Test fun statisticsHideInsightsWhenOff() {
        launch(signedIn = true, store = MemoryStore(null, insightsEnabled = false))
        waitForText("series in 7 days")
        rule.onNodeWithText("Statistics").performClick()
        waitForText("Target")
        waitForText("Drift")
        rule.waitForIdle()
        assertFalse("Insights must be hidden when off", rule.exists(hasText("Insights")))
    }

    // ---- coaching: the Serie page line against your average ----

    /** [serverSeries] with these rings on top of ten earlier 22lr series averaging 44, a day apart. */
    private fun withHistory(vararg rings: Int) {
        val now = Clock.System.now()
        val earlier = List(10) { i ->
            serverSeries.copy(
                id = 600L + i,
                timestamp = (now - (i + 2).days).toString(),
                holes = List(5) { h -> HoleDto(500.0, 500.0, if (h < 4) 9 else 8, false, null) },
            )
        }
        val newest = serverSeries.copy(holes = rings.map { HoleDto(500.0, 500.0, it, false, null) })
        onServer = listOf(newest) + earlier
    }

    private fun openNewestSeries(store: MemoryStore = MemoryStore(null)) {
        launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        rule.onNodeWithText("History").performClick()
        waitFor(thumbnail, "a photo in History")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitForText("Series")
    }

    private val lineTag = hasTestTag(COMPARISON_LINE_TAG)

    /** A series a little under the usual average of 44 (ten series of 9,9,9,9,8 = 44). */
    @Test fun serieLineSaysHowFarUnderTheAverage() {
        withHistory(9, 9, 9, 8, 8)
        openNewestSeries()
        waitForText("1 under your average of 44")
    }

    /** A clearly low series reads as such, with the number, and nothing about shape. */
    @Test fun serieLineForALowSeries() {
        withHistory(8, 8, 8, 7, 7)
        openNewestSeries()
        waitForText("6 under your average of 44")
        assertFalse(rule.exists(hasTestTag(COMPARISON_DAY_LINE_TAG)))
    }

    @Test fun serieLineForANormalSeries() {
        withHistory(9, 9, 9, 9, 8)
        openNewestSeries()
        waitForText("On your average of 44")
    }

    @Test fun serieLineForABestSeries() {
        withHistory(10, 10, 10, 9, 9)
        openNewestSeries()
        waitForText("Your best in the last 30 days")
    }

    /** Fewer than ten earlier series: no line at all. */
    @Test fun serieLineIsMissingWithoutHistory() {
        openNewestSeries()
        rule.waitForIdle()
        assertFalse(rule.exists(lineTag))
    }

    /** Insights off in Settings hides the line. */
    @Test fun serieLineIsHiddenWhenInsightsIsOff() {
        withHistory(8, 8, 8, 7, 7)
        openNewestSeries(MemoryStore(null, insightsEnabled = false))
        rule.waitForIdle()
        assertFalse(rule.exists(lineTag))
    }

    /**
     * The Serie page's day link opens Insights on that day and caliber: the newest series and three
     * offset ones minutes before it, 20 shots of 22lr on one day sitting right of the centre.
     */
    @Test fun serieDayLinkOpensInsightsForThatDay() {
        // All four on one local day: just after midnight, the whole set moves back before it.
        var newest = Clock.System.now() - 1.hours
        val zone = TimeZone.currentSystemDefault()
        if (newest.toEpochMilliseconds().let { ms -> kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time.toSecondOfDay() } < 600) {
            newest -= 15.minutes
        }
        onServer = listOf(serverSeries.copy(timestamp = newest.toString())) + offsetSeries.mapIndexed { i, s ->
            s.copy(timestamp = (newest - (i + 1).minutes).toString())
        }
        openNewestSeries()
        waitForText("See this day in Insights")
        rule.onNode(hasText("See this day in Insights")).performScrollTo().performClick()
        // The Serie page words the same finding, so the target and the picked tab tell the screens apart.
        waitFor(hasContentDescription("Insights target with 20 shots", substring = true), "the Insights target")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasText("Insights") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        waitForText("to the right over 20 shots")
        pressBack()
        waitForText("See this day in Insights")
    }

    // ---- coaching: Home's insight card ----

    private val insightCard = hasTestTag(HOME_INSIGHT_TAG)

    /** The key the offset is dismissed under: type, caliber, newest series' time. */
    private val offsetKey get() = "offset|22lr|${kotlin.time.Instant.parse(serverSeries.timestamp).toEpochMilliseconds()}"

    /** Round groups, the newest centred and three right of the centre: the offset is the only finding. */
    private val roundHoles = listOf(0.0 to 0.0, 12.0 to -8.0, -10.0 to 10.0, 8.0 to 14.0, -12.0 to -6.0).map { (dx, dy) ->
        HoleDto(500.0 + dx, 500.0 + dy, 9, false, 10.0)
    }

    private fun offsetOnly(vararg extra: SeriesDto) {
        onServer = extra.toList() + serverSeries.copy(holes = roundHoles) + offsetSeries
    }

    /**
     * The series sit right of the centre this week: Home shows that in one line, and a tap
     * opens Insights on 22lr with the finding; Back returns to Home with the card.
     */
    @Test fun homeInsightCardOpensInsights() {
        offsetOnly()
        launch(signedIn = true)
        waitForText("series in 7 days")
        waitFor(insightCard, "the insight card")
        waitForText("This week your 22lr group sits")
        rule.onNode(hasText("This week your 22lr group sits", substring = true)).performScrollTo().performClick()
        waitFor(hasContentDescription("Insights target with 20 shots", substring = true), "the Insights target")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasText("Insights") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        waitForText("to the right over 20 shots")
        pressBack()
        waitFor(insightCard, "the insight card after Back")
    }

    /** Hiding the card stores its key; the week strip stays. */
    @Test fun homeInsightCardCanBeDismissed() {
        offsetOnly()
        val store = MemoryStore(null)
        launch(signedIn = true, store = store)
        waitFor(insightCard, "the insight card")
        rule.onNode(hasContentDescription("Hide this insight")).performScrollTo().performClick()
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(insightCard) }
        rule.waitUntil(TIMEOUT_MS) { store.dismissedInsights == offsetKey }
        waitForText("series in 7 days")
    }

    /** A dismissed finding stays away on the next launch... */
    @Test fun homeInsightCardStaysDismissed() {
        offsetOnly()
        launch(signedIn = true, store = MemoryStore(null, dismissedInsights = offsetKey))
        waitForText("series in 7 days")
        waitFor(thumbnail, "the latest series' photo")
        rule.waitForIdle()
        assertFalse("A dismissed finding must stay away", rule.exists(insightCard))
    }

    /** ...until a new series in that caliber changes its key. */
    @Test fun homeInsightCardComesBackWithANewSeries() {
        // Newer than serverSeries, which sits only halfway back to midnight just after it.
        offsetOnly(serverSeries.copy(id = 520, timestamp = (Clock.System.now() - 1.minutes).toString(), holes = roundHoles))
        launch(signedIn = true, store = MemoryStore(null, dismissedInsights = offsetKey))
        waitFor(insightCard, "the insight card after a new 22lr series")
    }

    /** Insights off in Settings: no card on Home. */
    @Test fun homeInsightCardIsHiddenWhenInsightsIsOff() {
        launch(signedIn = true, store = MemoryStore(null, insightsEnabled = false))
        waitForText("series in 7 days")
        waitFor(thumbnail, "the latest series' photo")
        rule.waitForIdle()
        assertFalse("Insights off must hide the card", rule.exists(insightCard))
    }

    /**
     * Three earlier days of four 22lr series each, the last two series of every day scoring about
     * four points under the first two: Insights says the scores fall through a session, and says
     * what to try.
     */
    @Test fun insightsShowTheSessionFatigueFinding() {
        val zone = TimeZone.currentSystemDefault()
        val today = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date
        val start = today.atStartOfDayIn(zone).toEpochMilliseconds()
        val totals = listOf(listOf(48, 47, 43, 44), listOf(47, 48, 44, 42), listOf(48, 46, 43, 43))
        onServer = totals.flatMapIndexed { d, day ->
            day.mapIndexed { n, total ->
                val rings = List(5) { total / 5 + if (it < total % 5) 1 else 0 }
                SeriesDto(
                    id = 600L + d * 10 + n,
                    timestamp = kotlin.time.Instant.fromEpochMilliseconds(
                        start - (d + 1) * 24 * 3_600_000L + 10 * 3_600_000L + n * 30 * 60_000L,
                    ).toString(),
                    caliber = "22lr",
                    holes = rings.mapIndexed { i, ring -> HoleDto(500.0 + i * 3, 500.0 - i * 2, ring, false, 5.0) },
                    hasImage = true,
                    imageWidth = 1000,
                    imageHeight = 1000,
                    geometry = GEOMETRY,
                )
            }
        }
        launch(signedIn = true)
        waitForText("Mark")
        rule.onNodeWithText("Statistics").performClick()
        waitForText("Target")
        rule.onNodeWithText("Insights").performClick()
        waitForText("longer days, your later series scored lower")
        waitForText("Often caused by")
        waitForText("Shorter sessions")
        // A day trend has no mark on the target: picking it still selects the card.
        rule.onNode(hasText("longer days", substring = true)).performScrollTo().performClick()
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText("longer days", substring = true) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---- coaching: the sight helper ----

    /**
     * Three earlier days of two 22lr series each, every one grouped 40 mm right and 22 mm low of
     * the centre: the same offset on 3 days, so Insights offers the sight correction.
     */
    private fun withStableOffset() {
        // Mid-morning of each local day, so the two series of a day never straddle midnight.
        val zone = TimeZone.currentSystemDefault()
        val today = kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date
        val start = today.atStartOfDayIn(zone).toEpochMilliseconds()
        onServer = (1..3).flatMap { day ->
            (0..1).map { n ->
                SeriesDto(
                    id = 700L + day * 10 + n,
                    timestamp = kotlin.time.Instant.fromEpochMilliseconds(
                        start - day * 24 * 3_600_000L + 10 * 3_600_000L + n * 30 * 60_000L,
                    ).toString(),
                    caliber = "22lr",
                    holes = listOf(0.0 to 0.0, 12.0 to -8.0, -10.0 to 10.0, 8.0 to 14.0, -12.0 to -6.0).map { (dx, dy) ->
                        HoleDto(540.0 + dx, 520.0 + dy, 8, false, 45.0)
                    },
                    hasImage = true,
                    imageWidth = 1000,
                    imageHeight = 1000,
                    geometry = GEOMETRY,
                )
            }
        }
    }

    private fun openInsights(store: MemoryStore) {
        launch(signedIn = true, store = store)
        waitForText("Mark")
        rule.onNodeWithText("Statistics").performClick()
        waitForText("Target")
        rule.onNodeWithText("Insights").performClick()
    }

    /** A button by its text; [scroll] for one on the scrolling screen, not in a dialog. */
    private fun tapText(text: String, scroll: Boolean = true) {
        val node = rule.onNode(hasText(text) and hasClickAction())
        (if (scroll) node.performScrollTo() else node).performClick()
    }

    /**
     * A stable offset shows the correction in mm with the hint; a click value turns it into clicks
     * and is saved; "I adjusted the sights" saves the moment and the offset goes (no series since);
     * Undo brings it back.
     */
    @Test fun sightHelperGivesTheCorrectionInMmAndClicks() {
        withStableOffset()
        val store = MemoryStore(null)
        openInsights(store)
        waitFor(hasTestTag(SIGHT_CORRECTION_TAG), "the sight correction")
        waitForText("Sight correction")
        waitForText("Up 22 mm")
        waitForText("Left 40 mm")
        waitForText("Move the rear sight the way the impact should go")
        waitForText("Enter your sight's click value")

        tapText("Enter click value")
        waitForText("Mm per click at that distance")
        rule.onNode(hasTestTag(SIGHT_CLICK_TAG)).performTextReplacement("6")
        tapText("Save", scroll = false)
        waitForText("Up 22 mm: 4 clicks")
        waitForText("Left 40 mm: 7 clicks")
        waitForText("Clicks at 25 m with 6 mm per click")
        rule.waitUntil(TIMEOUT_MS) { decodeSightSetups(store.sightSetups)["22lr"]?.mmPerClick == 6.0 }

        tapText("I adjusted the sights")
        waitForText("Sights adjusted")
        waitForText("judged on the 0 shots since then")
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasTestTag(SIGHT_CORRECTION_TAG)) }
        rule.waitUntil(TIMEOUT_MS) { decodeSightSetups(store.sightSetups)["22lr"]?.adjustedAtMs != null }
        // The click value stays.
        assertEquals(6.0, decodeSightSetups(store.sightSetups)["22lr"]?.mmPerClick)

        tapText("Undo")
        waitForText("Up 22 mm: 4 clicks")
        rule.waitUntil(TIMEOUT_MS) { decodeSightSetups(store.sightSetups)["22lr"]?.adjustedAtMs == null }
    }

    /** A saved click value shows clicks at once; a saved adjustment after the series hides the offset. */
    @Test fun sightHelperReadsSavedSetups() {
        withStableOffset()
        openInsights(MemoryStore(null, sightSetups = """{"22lr":{"distanceM":50.0,"mmPerClick":10.0}}"""))
        waitForText("Up 22 mm: 2 clicks")
        waitForText("Left 40 mm: 4 clicks")
        waitForText("Clicks at 50 m with 10 mm per click")
    }

    @Test fun sightHelperHidesTheOffsetAfterASavedAdjustment() {
        withStableOffset()
        val now = Clock.System.now().toEpochMilliseconds()
        openInsights(MemoryStore(null, sightSetups = """{"22lr":{"adjustedAtMs":$now}}"""))
        waitForText("Sights adjusted")
        waitForText("judged on the 0 shots since then")
        assertFalse(rule.exists(hasTestTag(SIGHT_CORRECTION_TAG)))
    }

    // ---- the scan screen (free marking): FakeTarget, its stand-in detectors, the real pipeline ----

    private fun waitForExact(text: String) = waitFor(hasText(text), "exactly \"$text\"")
    private fun button(label: String) = hasText(label) and hasClickAction()
    /** A button merges its label into its own node, so it is looked for in the merged tree. */
    private fun waitForButton(matcher: SemanticsMatcher, what: String) {
        try {
            rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("Never showed $what; requests: $requests " + rule.onRoot().printToString(), e)
        }
    }
    private fun click(matcher: SemanticsMatcher, what: String = "the button") {
        waitForButton(matcher, what)
        val node = rule.onAllNodes(matcher)[0]
        // Only a button inside a scrolling column can (and needs to) be scrolled to.
        if (rule.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.performClick()
    }

    /** Home → Mark → Detect: the five drawn holes score 10, 9, 8, 7, 6. */
    private fun openScanAndDetect(signedIn: Boolean = true, store: MemoryStore = MemoryStore(null)): SeriesServices {
        onServer = emptyList()
        val services = launch(signedIn, store)
        click(button("Mark"))
        click(button("Detect"))
        waitForExact("40")
        return services
    }

    /** Taps (or long-presses) the frozen frame at image pixel [x],[y]. */
    private fun onPhoto(x: Float, y: Float, long: Boolean = false) {
        rule.onNodeWithTag(SCAN_VIEWPORT_TAG).performTouchInput {
            val at = Offset(x * width / FakeTarget.SIZE, y * height / FakeTarget.SIZE)
            if (long) longClick(at) else click(at)
        }
    }

    private fun save() {
        click(button("Save"))
    }

    private fun waitForPosts(n: Int): List<SeriesRequest> {
        rule.waitUntil(TIMEOUT_MS) { posted.size >= n }
        return posted.toList()
    }

    @Test fun scanFillsTheTotalAndTheScores() {
        openScanAndDetect()
        listOf("10", "9", "8", "7", "6").forEach { waitForButton(button(it), "a score box $it") }
        waitForText("Tap the photo to add a hit")
    }

    /** A typed score replaces the detector's; the save carries both, and the series lands in History with its photo. */
    @Test fun typedScoreIsSavedAndTheSeriesShowsInHistory() {
        openScanAndDetect()
        click(button("6"))
        waitForText("Score")
        click(button("X"))
        waitForExact("44")
        save()
        waitForExact("Saved")
        val sent = waitForPosts(1).single()
        assertEquals("22lr", sent.caliber)
        val typed = sent.holes.single { it.innerTen }
        assertEquals(10, typed.ring)
        assertEquals(6, typed.detectedRing)
        assertEquals(listOf(10, 10, 9, 8, 7), sent.holes.map { it.ring }.sortedDescending())
        rule.waitUntil(TIMEOUT_MS) { requests.any { it.matches(Regex("""POST /series/\d+/image""")) } }

        pressBack()
        rule.onNodeWithText("History").performClick()
        waitForText("44")
        waitFor(thumbnail, "the saved series' photo in History")
    }

    /** A tap on the photo adds the missed hole as a hand-placed one: scored, saved with no detector values. */
    @Test fun tapAddsAMissedHole() {
        openScanAndDetect()
        val (x, y) = FakeTarget.at(40f, 200f)
        onPhoto(x, y)
        waitForExact("49")
        save()
        val sent = waitForPosts(1).single()
        assertEquals(6, sent.holes.size)
        val manual = sent.holes.single { it.detectedX == null }
        assertEquals(9, manual.ring)
        assertEquals(null, manual.detectedRing)
    }

    /** A long press asks, then removes the hit; the save keeps the detection as a deleted hole. */
    @Test fun longPressRemovesAHit() {
        openScanAndDetect()
        val (x, y) = FakeTarget.HOLES.last()
        onPhoto(x, y, long = true)
        waitForText("Remove the hit?")
        click(button("Remove"))
        waitForExact("34")
        save()
        val sent = waitForPosts(1).single()
        assertEquals(listOf(10, 9, 8, 7), sent.holes.filterNot { it.deleted }.map { it.ring }.sortedDescending())
        assertEquals(6, sent.holes.single { it.deleted }.detectedRing)
    }

    /** No caliber chosen yet: Save asks for one first, and the tag picked on the badge rides along. */
    @Test fun saveWithoutACaliberAsksForOne() {
        val store = MemoryStore(null, caliber = Caliber.NONE)
        openScanAndDetect(store = store)
        click(button("+ Tag"))
        waitForText("Choose tag")
        click(button("Practice"))
        waitForExact("Practice")
        save()
        waitForText("Choose caliber")
        assertEquals("Nothing is sent before a caliber is chosen", 0, posted.size)
        click(button("9mm"))
        waitForExact("Saved")
        val sent = waitForPosts(1).single()
        assertEquals("9mm", sent.caliber)
        assertEquals("Practice", sent.tag)
        rule.waitUntil(TIMEOUT_MS) { store.caliber == Caliber.MM9 }
    }

    /** A saved scan survives the next one: scanning again and resetting sends nothing more. */
    @Test fun rescanDropsOnlyThePendingScan() {
        openScanAndDetect()
        save()
        waitForExact("Saved")
        waitForPosts(1)
        click(button("Detect"))
        waitForExact("40")
        // The "Saved" snackbar may still cover the action row, so click through semantics.
        rule.onAllNodes(hasContentDescription("Reset") and hasClickAction())[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(TIMEOUT_MS) {
            rule.exists(hasText("Discard your changes?")) || rule.onAllNodes(button("Detect")).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("An untouched scan must reset without asking", rule.exists(hasText("Discard your changes?")))
        rule.waitForIdle()
        assertEquals(1, posted.size)
    }

    /** Signed out, Save keeps the series on the phone and sends nothing. */
    @Test fun signedOutSaveStaysOnThePhone() {
        val services = openScanAndDetect(signedIn = false)
        save()
        waitForText("Saved on this device")
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.any { it.id < 0 } }
        assertEquals(0, posted.size)
    }

    // ---- Import images: the dialog, manual and auto ----

    /** The sources the stand-in picker was asked for, in order. */
    private val pickedFrom = CopyOnWriteArrayList<ImageSource>()

    /** The drawn target as a picked image; its load sets the [holes] the stand-in detector finds in it. */
    private fun picked(engine: FakeScanEngine, takenAt: String? = null, holes: List<Pair<Float, Float>> = FakeTarget.HOLES) =
        PickedImage { loads.incrementAndGet(); engine.holes = holes; LoadedImage(FakeTarget.frame(), takenAt) }

    /** Picked images loaded so far: an image is loaded again when its review pass reaches it. */
    private val loads = java.util.concurrent.atomic.AtomicInteger()

    /** Home → menu → Import images, with Auto import set to [auto], then [source]'s button. */
    private fun startImport(auto: Boolean, source: String = "Gallery") {
        waitForText(homeMarker)
        openMenuItem("Import images")
        waitForText("Auto import")
        val switch = hasText("Auto import") and isToggleable()
        val on = rule.onNode(switch).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        // The switch remembers its last position for the session, so it is set, not toggled blindly.
        if (on != auto) rule.onNode(switch).performClick()
        rule.waitUntil(TIMEOUT_MS) {
            (rule.onNode(switch).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On) == auto
        }
        click(button(source))
    }

    /** Skip, an icon button labelled by its description, clicked through semantics: a save's toast may cover it. */
    private fun skip() {
        val skip = hasContentDescription("Skip") and hasClickAction()
        waitForButton(skip, "Skip")
        rule.onAllNodes(skip)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun backHomeWith(summary: String) {
        waitForExact(summary)
        waitForText(homeMarker)
        assertFalse("The import screen must be gone", rule.exists(hasText("Import ", substring = true) and hasAnyAncestor(hasTestTag(TOP_BAR_TAG))))
    }

    /** The dialog shows its caliber and tag chips, Auto import and both sources; with no caliber it asks before scanning. */
    @Test fun importDialogAsksForACaliberBeforeTheFirstScan() {
        val engine = FakeScanEngine()
        launch(signedIn = true, store = MemoryStore(null, caliber = Caliber.NONE), scanEngine = engine,
            picks = { pickedFrom += it; listOf(picked(engine)) })
        startImport(auto = false, source = "Files")
        assertEquals(listOf(ImageSource.FILES), pickedFrom.toList())
        waitForText("Choose caliber")
        rule.waitForIdle()
        // The chooser lists a "40" caliber, so the load count tells whether anything was scanned.
        assertEquals("Nothing is loaded before a caliber is chosen", 0, loads.get())
        click(button("9mm"))
        waitForExact("40")
        waitForText("Import 1 / 1")
        skip()
        backHomeWith("0 saved, 1 skipped")
        assertEquals(0, posted.size)
    }

    /** Manual: Save keeps the image (dated by its EXIF time), Skip moves on, one summary, back Home. */
    @Test fun manualImportSavesAndSkipsAndDatesTheSeries() {
        val engine = FakeScanEngine()
        val taken = "2026-05-01T08:30:00Z"
        launch(signedIn = true, scanEngine = engine, picks = { pickedFrom += it; listOf(picked(engine, taken), picked(engine)) })
        startImport(auto = false)
        assertEquals(listOf(ImageSource.GALLERY), pickedFrom.toList())
        waitForText("Import 1 / 2")
        waitForExact("40")
        save()
        val sent = waitForPosts(1).single()
        assertEquals(java.time.Instant.parse(taken), java.time.Instant.parse(sent.timestamp))
        waitForText("Import 2 / 2")
        // The first image's total is still up until the second is scanned.
        rule.waitUntil(TIMEOUT_MS) { loads.get() == 2 }
        waitForExact("40")
        skip()
        backHomeWith("1 saved, 1 skipped")
        assertEquals(1, posted.size)
    }

    /** Auto: the scored images save without stopping; the one that scored nothing is shown after the pass. */
    @Test fun autoImportSavesWhatScoredAndReviewsTheRest() {
        val engine = FakeScanEngine()
        launch(signedIn = true, scanEngine = engine,
            picks = { listOf(picked(engine), picked(engine, holes = emptyList()), picked(engine)) })
        startImport(auto = true)
        // The review pass is a queue of its own: the held image, alone (a fourth load).
        rule.waitUntil(TIMEOUT_MS) { loads.get() == 4 }
        waitForText("Import 1 / 1")
        assertEquals(2, waitForPosts(2).size)
        skip()
        backHomeWith("2 saved, 1 skipped")
        assertEquals(2, posted.size)
    }

    /** Auto: an image whose photo cannot be kept on the phone is not counted saved; it goes to review. */
    @Test fun autoImportSendsAFailedLocalSaveToReview() {
        val engine = FakeScanEngine()
        // A file where the durable photo folder goes: every local photo write fails.
        File(root, "files").mkdirs()
        File(root, "files/local-series").writeText("not a folder")
        launch(signedIn = true, scanEngine = engine, picks = { listOf(picked(engine)) })
        startImport(auto = true)
        // Both passes read "Import 1 / 1": the second load is the review pass.
        rule.waitUntil(TIMEOUT_MS) { loads.get() == 2 }
        waitForExact("40")
        skip()
        backHomeWith("0 saved, 1 skipped")
    }

    // ---- the scan screen's side states: camera permission, guide colour, debug overlay ----

    /** Denied, the scan screen explains why and asks again on Allow access; granted, the viewfinder is back. */
    @Test fun cameraPermissionDeniedExplainsAndAsksAgain() {
        val granted = androidx.compose.runtime.mutableStateOf(false)
        val asked = java.util.concurrent.atomic.AtomicInteger()
        // The first ask is refused, the second allowed.
        launch(signedIn = false, cameraPermission = {
            CameraPermissionState(granted.value) { if (asked.incrementAndGet() >= 2) granted.value = true }
        })
        click(button("Mark"))
        waitForText("The camera is needed to mark hits")
        assertFalse("No scanning without the camera", rule.exists(button("Detect")))
        click(button("Allow access"))
        rule.waitUntil(TIMEOUT_MS) { asked.get() == 1 }
        rule.waitForIdle()
        assertTrue("Still refused, still explained", rule.exists(hasText("The camera is needed to mark hits", substring = true)))
        click(button("Allow access"))
        waitForButton(button("Detect"), "Detect")
        assertFalse(rule.exists(hasText("The camera is needed to mark hits", substring = true)))
        assertEquals(2, asked.get())
    }

    private fun guideColorButton() = hasContentDescription("Guide colour") and hasClickAction()
    private fun swatch(i: Int) = hasTestTag("$GUIDE_SWATCH_TAG-$i")
    private fun stored(i: Int) = SWATCHES[i].toArgb().toLong() and 0xFFFFFFFFL

    /** The palette's pick is saved on Done, is the one marked when it opens again and after a restart; Default is white. */
    @Test fun guideColourIsPickedAndKept() {
        val store = MemoryStore(null)
        launch(signedIn = false, store = store)
        click(button("Mark"))
        click(guideColorButton(), "the palette button")
        waitFor(swatch(2), "the swatches")
        rule.onNode(swatch(2)).performClick()
        rule.onNode(swatch(2) and isSelected()).assertExists()
        click(button("Done"))
        rule.waitUntil(TIMEOUT_MS) { store.guideColor == stored(2) }
        click(guideColorButton(), "the palette button")
        waitFor(swatch(2) and isSelected(), "the picked swatch marked")
        // Cancel keeps it.
        rule.onNode(swatch(4)).performClick()
        click(button("Cancel"))
        rule.waitForIdle()
        assertEquals(stored(2), store.guideColor)
        click(guideColorButton(), "the palette button")
        click(button("Default"))
        rule.waitUntil(TIMEOUT_MS) { store.guideColor == DEFAULT_GUIDE_COLOR }
    }

    /** A colour stored earlier is the guide's colour on the next start. */
    @Test fun guideColourIsReadBackOnStart() {
        launch(signedIn = false, store = MemoryStore(null, guideColor = stored(6)))
        click(button("Mark"))
        click(guideColorButton(), "the palette button")
        waitFor(swatch(6) and isSelected(), "the stored swatch marked")
    }

    /** Debug builds: Show detection data draws the raw boxes over the result, and a second tap hides them. */
    @Test fun debugOverlayShowsAndHidesTheRawBoxes() {
        org.junit.Assume.assumeTrue(se.kjellstrand.markera.ui.markera.isDebugBuild)
        openScanAndDetect(signedIn = false)
        val raw = hasTestTag(DEBUG_DETECTIONS_TAG)
        assertFalse("The clean view has no raw boxes", rule.exists(raw))
        openMenuItem("Show detection data")
        waitFor(raw, "the raw detection boxes")
        openMenuItem("Show detection data")
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(raw) }
    }

    // ---- the Serie page: editing and walking the list ----
    private fun inDialog(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(isDialog())
    private fun waitForPuts(n: Int): List<Pair<Long, SeriesRequest>> {
        try {
            rule.waitUntil(TIMEOUT_MS) { puts.size >= n }
        } catch (e: Throwable) {
            throw AssertionError("Expected $n PUTs, got ${puts.size}; requests: $requests", e)
        }
        return puts.toList()
    }
    private fun date(s: SeriesDto) = localStamp(s.timestamp).take(10)

    /** Score, caliber and tag each save at once, and are still there after going back and in again. */
    @Test fun serieEditsAreSavedAndSurviveReopening() {
        openNewestSeries()
        waitForExact("43")
        // Hole a, the 10, made an 8.
        click(button("10"))
        waitForText("Score")
        click(inDialog(button("8")))
        waitForExact("41")
        val (id, score) = waitForPuts(1).last()
        assertEquals(serverSeries.id, id)
        assertEquals(listOf(9, 9, 8, 8, 7), score.holes.map { it.ring }.sortedDescending())

        click(button("22lr"))
        waitForText("Choose caliber")
        click(inDialog(button("9mm")))
        assertEquals("9mm", waitForPuts(2).last().second.caliber)

        click(button("+ Tag"))
        waitForText("Choose tag")
        click(inDialog(button("Practice")))
        assertEquals("Practice", waitForPuts(3).last().second.tag)
        click(button("Practice"))
        waitForText("Choose tag")
        click(inDialog(button("No tag")))
        val cleared = waitForPuts(4).last().second
        assertEquals(null, cleared.tag)
        assertEquals("9mm", cleared.caliber)

        pressBack()
        waitFor(thumbnail, "History again")
        rule.onAllNodesWithText("9mm")[0].performClick()
        waitForExact("Series")
        waitForExact("41")
        waitForButton(button("9mm"), "the corrected caliber")
        waitForButton(button("+ Tag"), "no tag")
        assertEquals(4, puts.size)
    }

    /** Removing a detected hole saves it as deleted, for training, and rescores the series. */
    @Test fun serieRemovedHoleIsKeptAsDeleted() {
        onServer = listOf(
            serverSeries.copy(
                holes = serverSeries.holes.map { it.copy(detectedRing = it.ring, detectedInnerTen = false, detectedX = it.x, detectedY = it.y) },
            ),
        ) + offsetSeries
        openNewestSeries()
        waitForExact("43")
        click(hasContentDescription("More") and hasClickAction())
        click(button("Remove hit"))
        waitForText("Remove the hit?")
        click(inDialog(button("Remove")))
        waitForExact("33")
        val sent = waitForPuts(1).last().second
        assertEquals(listOf(9, 9, 8, 7), sent.holes.filterNot { it.deleted }.map { it.ring }.sortedDescending())
        assertEquals(10, sent.holes.single { it.deleted }.detectedRing)
    }

    /** Delete from the menu asks, removes the series on the server and takes it out of History. */
    @Test fun serieDeleteRemovesItFromHistory() {
        openNewestSeries()
        waitForExact("43")
        rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true)[0].performClick()
        click(button("Delete"))
        waitForText("Delete the series?")
        click(inDialog(button("Delete")))
        rule.waitUntil(TIMEOUT_MS) { "DELETE /series/${serverSeries.id}" in requests }
        waitForExact("History")
        waitFor(thumbnail, "the other series in History")
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("43")) }
    }

    /** The arrows walk the list the page was opened from, newest to oldest; Back returns to the list. */
    @Test fun serieArrowsWalkTheListAndBackReturnsToIt() {
        openNewestSeries()
        waitForExact(date(serverSeries))
        val previous = hasContentDescription("Previous series") and hasClickAction()
        val next = hasContentDescription("Next series") and hasClickAction()
        waitForButton(next, "the next arrow")
        assertFalse("The newest has no previous", rule.onAllNodes(previous).fetchSemanticsNodes().isNotEmpty())
        offsetSeries.forEach { s ->
            click(next)
            waitForExact(date(s))
            waitForButton(previous, "the previous arrow")
        }
        rule.waitForIdle()
        assertFalse("The oldest has no next", rule.onAllNodes(next).fetchSemanticsNodes().isNotEmpty())
        click(previous)
        waitForExact(date(offsetSeries[1]))
        pressBack()
        waitForExact("History")
        waitFor(thumbnail, "History's photos")
        assertFalse(rule.onAllNodes(previous).fetchSemanticsNodes().isNotEmpty())
    }

    /** A photo that cannot be downloaded says so instead of spinning. */
    @Test fun seriePhotoThatFailsSaysNoPhoto() {
        photoless += serverSeries.id
        launch(signedIn = true)
        waitForText("series in 7 days")
        rule.onNodeWithText("History").performClick()
        // Its thumbnail fails too, so History is not waited on for a photo.
        waitForText("22lr")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitForExact("Series")
        waitForExact("No photo")
    }

    /** A series the server refused shows why on its page; Try again sends it once the server takes it. */
    @Test fun serieRefusedUploadCanBeRetried() {
        postAnswer = HttpStatusCode.BadRequest
        val services = openScanAndDetect()
        save()
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.any { it.local == LocalMark.REFUSED } }
        pressBack()
        rule.onNodeWithText("History").performClick()
        waitForText("22lr")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitForText("The server did not accept this series")
        postAnswer = null
        click(button("Try again"))
        waitForPosts(1)
        waitForExact("History")
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.none { it.local != null } }
    }

    /** A series the server could not take yet (here a 503) says it is not uploaded. */
    @Test fun serieQueuedUploadSaysNotUploaded() {
        postAnswer = HttpStatusCode.ServiceUnavailable
        val services = openScanAndDetect()
        save()
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.any { it.local == LocalMark.QUEUED } }
        pressBack()
        rule.onNodeWithText("History").performClick()
        waitForText("22lr")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitForText("Not uploaded")
        assertEquals(0, posted.size)
    }

    // ---- accounts: local series, the claim dialog, sign-out, delete account, an expired session ----

    /** A series saved on this phone, with its photo, as a signed-out (or [owner]'s) save leaves it. */
    private fun keepLocal(services: SeriesServices, minutesAgo: Int = 10, owner: Long? = null) = runBlocking {
        services.repository.keepLocal(
            SeriesRequest(
                timestamp = (Clock.System.now() - minutesAgo.minutes).toString(),
                caliber = "22lr",
                holes = serverSeries.holes,
                geometry = GEOMETRY,
            ),
            EncodedImage(jpeg, 1000, 1000),
            owner,
        )
    }
    private fun localSeries(services: SeriesServices) = services.repository.series.value.filter { it.local != null }

    /** Signed out, Home's card counts the series kept on the phone, and Home and History show them. */
    @Test fun signedOutLocalSeriesAreCountedAndShown() {
        onServer = emptyList()
        val services = launch(signedIn = false)
        // Minutes apart, so both stay on today's open day group even just after midnight.
        keepLocal(services, 2)
        keepLocal(services, 3)
        waitForText("2 series only on this phone")
        waitForText("Latest series")
        rule.onNodeWithText("History").performClick()
        waitFor(thumbnail, "a local series' photo in History")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText("22lr").fetchSemanticsNodes().size >= 2 }
        assertTrue(requests.none { it.startsWith("POST") })
    }

    /** Signing in with series on the phone asks first; Add uploads them to the account. */
    private fun signInWithALocalSeries(): Pair<SeriesServices, MemoryStore> {
        onServer = emptyList()
        val store = MemoryStore(null)
        val services = launch(signedIn = false, store = store)
        keepLocal(services)
        waitForText("1 series only on this phone")
        runBlocking { services.session.signInDev("ui-test") }
        waitForText("Series on this device")
        assertEquals("No token is saved while the choice is open", null, store.auth)
        return services to store
    }

    @Test fun claimAddUploadsTheLocalSeries() {
        val (services, store) = signInWithALocalSeries()
        click(inDialog(button("Add to account")))
        waitForPosts(1)
        rule.waitUntil(TIMEOUT_MS) { store.auth?.token == TOKEN && localSeries(services).isEmpty() }
        waitForText("series in 7 days")
    }

    @Test fun claimDeleteAsksThenDeletesFromThePhone() {
        val (services, store) = signInWithALocalSeries()
        click(inDialog(button("Delete from device")))
        waitForText("Delete 1 series and their photos")
        click(inDialog(button("Delete from device")))
        rule.waitUntil(TIMEOUT_MS) { store.auth?.token == TOKEN && localSeries(services).isEmpty() }
        assertEquals(0, posted.size)
    }

    @Test fun claimStaySignedOutKeepsTheSeriesAndNoToken() {
        val (services, store) = signInWithALocalSeries()
        click(inDialog(button("Stay signed out")))
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("Series on this device")) }
        rule.waitUntil(TIMEOUT_MS) { "DELETE /auth/session" in requests }
        assertEquals(null, store.auth)
        assertEquals(null, services.session.auth.value)
        assertEquals(1, localSeries(services).size)
        assertEquals(0, posted.size)
        waitForText("1 series only on this phone")
    }

    /** Dismissing the dialog (Back) is Stay signed out. */
    @Test fun claimDismissIsStaySignedOut() {
        val (services, store) = signInWithALocalSeries()
        pressBack()
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("Series on this device")) }
        assertEquals(null, store.auth)
        assertEquals(null, services.session.auth.value)
        assertEquals(1, localSeries(services).size)
    }

    /** Sign out clears the account's series from the phone but keeps work not yet uploaded. */
    @Test fun signOutKeepsLocalWorkAndClearsSyncedSeries() {
        postAnswer = HttpStatusCode.ServiceUnavailable
        val store = MemoryStore(null)
        val services = launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        keepLocal(services, owner = USER)
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.any { it.id > 0 } && localSeries(services).size == 1 }
        openSettings()
        click(button("Sign out"))
        waitForText("Signed out")
        rule.waitUntil(TIMEOUT_MS) { services.repository.series.value.none { it.id > 0 } }
        assertEquals(null, store.auth)
        assertEquals(1, localSeries(services).size)
        pressBack()
        waitForText("1 series only on this phone")
    }

    /** Delete account asks for the typed phrase, deletes on the server and leaves local work. */
    @Test fun deleteAccountAsksThenSignsOut() {
        postAnswer = HttpStatusCode.ServiceUnavailable
        val store = MemoryStore(null)
        val services = launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        keepLocal(services, owner = USER)
        rule.waitUntil(TIMEOUT_MS) { localSeries(services).size == 1 }
        openSettings()
        click(button("Delete account"))
        waitForText("Delete the account?")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("delete me")
        click(inDialog(button("Delete")))
        rule.waitUntil(TIMEOUT_MS) { "DELETE /account" in requests }
        rule.waitUntil(TIMEOUT_MS) { store.auth == null && services.repository.series.value.none { it.id > 0 } }
        assertEquals(1, localSeries(services).size)
        waitForText("Not signed in")
    }

    /** The server no longer takes the token: the app signs out and says why. */
    @Test fun expiredSessionSignsOutWithAToast() {
        tokenExpired = true
        val store = MemoryStore(null)
        launch(signedIn = true, store = store)
        waitForText("Signed out: sign in again to save series")
        rule.waitUntil(TIMEOUT_MS) { store.auth == null }
        waitForText("Save your series to your account")
    }

    // ---- the suggestion box ----

    private fun field(label: String) = hasSetTextAction() and hasText(label)

    /** Home's menu → Suggestion; Send stays off until a title and a description are typed. */
    private fun openSuggestion(signedIn: Boolean) {
        launch(signedIn)
        waitForText("Mark")
        rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true)[0].performClick()
        click(button("Suggestion"))
        waitForText("It goes straight to the developer")
        waitForButton(button("Send"), "Send")
        rule.onAllNodes(button("Send"))[0].assertIsNotEnabled()
        rule.onNode(field("Title")).performTextInput("Wind flags")
        rule.onAllNodes(button("Send"))[0].assertIsNotEnabled()
        rule.onNode(field("Description")).performTextInput("Show the wind on the scan screen.")
        rule.onAllNodes(button("Send"))[0].assertIsEnabled()
    }

    @Test fun suggestionIsSentAndGoesBack() {
        openSuggestion(signedIn = true)
        click(button("Send"))
        waitForText("Thanks! Your suggestion has been sent.")
        rule.waitUntil(TIMEOUT_MS) { suggestions.size == 1 }
        val (sent, withToken) = suggestions.single()
        assertEquals("Wind flags", sent.title)
        assertEquals("Show the wind on the scan screen.", sent.description)
        assertEquals(null, sent.email)
        assertEquals("android", sent.platform)
        assertTrue(withToken)
        waitForText("Mark")
    }

    /** Signed out works too; a typed e-mail rides along, and one that is not an address blocks Send. */
    @Test fun suggestionWorksSignedOutWithAnEmail() {
        openSuggestion(signedIn = false)
        rule.onNode(field("Your e-mail (optional)")).performTextInput("nope")
        waitForText("That doesn't look like an e-mail address.")
        rule.onAllNodes(button("Send"))[0].assertIsNotEnabled()
        rule.onNode(field("Your e-mail (optional)")).performTextInput("@example.com")
        click(button("Send"))
        rule.waitUntil(TIMEOUT_MS) { suggestions.size == 1 }
        val (sent, withToken) = suggestions.single()
        assertEquals("nope@example.com", sent.email)
        assertFalse(withToken)
    }

    /** A server error says so and keeps what was typed, so Send can be tried again. */
    @Test fun suggestionServerErrorKeepsTheText() {
        suggestionAnswer = HttpStatusCode.ServiceUnavailable
        openSuggestion(signedIn = true)
        click(button("Send"))
        waitForText("The server had a problem. Try again later.")
        rule.waitForIdle()
        waitForExact("Wind flags")
        waitForExact("Show the wind on the scan screen.")
        suggestionAnswer = null
        // The error snackbar may still cover Send; click it through its semantics.
        rule.onAllNodes(button("Send"))[0].performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(TIMEOUT_MS) { suggestions.size == 1 }
    }

    // ---- Settings: language, theme, About ----

    /** Svenska re-keys the whole app into Swedish and is saved; English switches it back. */
    @Test fun settingsLanguageSwitchesTheAppAndIsSaved() {
        val store = MemoryStore(null)
        launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        openSettings()
        pick("Svenska")
        // Section headers are drawn upper-case; the title is not.
        waitForExact("Inställningar")
        waitForSelected("Svenska")
        rule.waitUntil(TIMEOUT_MS) { store.language == "sv" }
        pressBack()
        waitForExact("Historik")
        waitForExact("Statistik")
        rule.onAllNodes(hasContentDescription("Meny"), useUnmergedTree = true)[0].performClick()
        rule.onAllNodesWithText("Inställningar")[0].performClick()
        pick("English")
        waitForExact("Settings")
        rule.waitUntil(TIMEOUT_MS) { store.language == "en" }
        pressBack()
        waitForExact("History")
    }

    /** A saved Swedish is there from the first frame. */
    @Test fun settingsShowSavedLanguage() {
        launch(signedIn = true, store = MemoryStore(null, language = "sv"))
        waitForExact("Historik")
        waitForExact("Statistik")
    }

    /** Dark is picked, saved, and a saved choice shows picked. */
    @Test fun settingsThemeIsSaved() {
        val store = MemoryStore(null)
        launch(signedIn = true, store = store)
        waitForText("series in 7 days")
        openSettings()
        pick("Dark")
        waitForSelected("Dark")
        rule.waitUntil(TIMEOUT_MS) { store.theme == "DARK" }
        pick("Light")
        waitForSelected("Light")
        rule.waitUntil(TIMEOUT_MS) { store.theme == "LIGHT" }
    }

    @Test fun settingsShowSavedTheme() {
        launch(signedIn = true, store = MemoryStore(null, theme = "DARK"))
        waitForText("series in 7 days")
        openSettings()
        waitForSelected("Dark")
    }

    /** About names the installed version, and the privacy link opens the public page. */
    @Test fun settingsAboutShowsTheVersionAndOpensPrivacy() {
        launch(signedIn = true)
        waitForText("series in 7 days")
        openSettings()
        waitForExact("Version ${BuildConfig.VERSION_NAME}")
        click(button("Privacy policy"))
        rule.waitUntil(TIMEOUT_MS) { opened.isNotEmpty() }
        assertEquals(listOf("https://markera.duckdns.org/privacy"), opened.toList())
    }

    // ---- the app menu, the help dialogs and back navigation ----

    private val homeMarker = "series in 7 days"
    private fun menuButton() = rule.onAllNodes(hasContentDescription("Menu"), useUnmergedTree = true)[0]
    private fun menuRow(label: String) = hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(MENU_OVERLAY_TAG))

    /** Opens the screen's menu and checks its rows, top to bottom, are exactly [labels]. */
    private fun assertMenu(vararg labels: String) {
        menuButton().performClick()
        labels.forEach { waitForButton(menuRow(it), "the menu's \"$it\"") }
        rule.waitForIdle()
        val rows = rule.onAllNodes(hasClickAction() and hasText("", substring = true) and hasAnyAncestor(hasTestTag(MENU_OVERLAY_TAG)))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(labels.toList(), rows)
        // Back closes the menu and stays on the screen.
        pressBack()
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasTestTag(MENU_OVERLAY_TAG)) }
    }
    private fun openMenuItem(label: String) {
        menuButton().performClick()
        click(menuRow(label))
    }

    /** Waits until [marker] shows and Home does not. */
    private fun waitOn(marker: SemanticsMatcher, what: String) {
        waitFor(marker, what)
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText(homeMarker, substring = true)) }
    }
    private fun waitHome(left: SemanticsMatcher) {
        waitForText(homeMarker)
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(left) }
    }
    private val onMark = hasTestTag(SCAN_VIEWPORT_TAG)
    private val onHistory = hasText("History") and hasAnyAncestor(hasTestTag(TOP_BAR_TAG))
    private val onStatistics = hasText("Statistics") and hasAnyAncestor(hasTestTag(TOP_BAR_TAG))
    private val onSerie = hasText("Series") and hasAnyAncestor(hasTestTag(TOP_BAR_TAG))
    private val onSettings = hasText("Shooting hand", substring = true, ignoreCase = true)
    private val onSuggestion = hasText("It goes straight to the developer", substring = true)
    /** Whether anything in the app takes Back; false means the system's Back leaves the app. */
    private fun backIsHandled() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
    private fun topBarBack() = click(hasContentDescription("Back") and hasClickAction(), "the top bar's back button")

    /** Every screen's menu has its own items first and ends with Suggestion and Settings (bar the screen itself). */
    @Test fun everyMenuEndsWithSuggestionAndSettings() {
        launch(signedIn = true)
        waitForText(homeMarker)
        assertMenu("Import images", "Help", "Suggestion", "Settings")
        click(button("Mark"))
        waitOn(onMark, "the scan screen")
        assertMenu("Help", "Show detection data", "Suggestion", "Settings")
        pressBack()
        click(button("History"))
        waitOn(onHistory, "History")
        assertMenu("Help", "Export", "Suggestion", "Settings")
        waitFor(thumbnail, "a photo in History")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitOn(onSerie, "the Serie page")
        assertMenu("Help", "Delete", "Suggestion", "Settings")
        pressBack()
        pressBack()
        click(button("Statistics"))
        waitOn(onStatistics, "Statistics")
        assertMenu("Help", "Suggestion", "Settings")
        openMenuItem("Settings")
        waitOn(onSettings, "Settings")
        // Settings and the Suggestion box each leave themselves out.
        assertMenu("Suggestion")
        openMenuItem("Suggestion")
        waitOn(onSuggestion, "the Suggestion box")
        assertMenu("Settings")
    }

    /** Each menu item goes where it says. */
    @Test fun menuItemsGoWhereTheySay() {
        launch(signedIn = true)
        waitForText(homeMarker)
        openMenuItem("Import images")
        waitForText("Auto import")
        pressBack()
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("Auto import")) }
        openMenuItem("Suggestion")
        waitOn(onSuggestion, "the Suggestion box")
        openMenuItem("Settings")
        waitOn(onSettings, "Settings")
        pressBack()
        waitOn(onSuggestion, "the Suggestion box again")
        pressBack()
        waitHome(onSuggestion)
        click(button("History"))
        waitOn(onHistory, "History")
        waitFor(thumbnail, "a photo in History")
        openMenuItem("Export")
        rule.waitUntil(TIMEOUT_MS) { shared.isNotEmpty() }
        assertTrue(shared.single(), shared.single().endsWith(".zip"))
    }

    /** Opens [screen]'s Help from its menu, checks [title], and closes it with Close (or Back). */
    private fun assertHelp(title: String, closeWithBack: Boolean = false) {
        openMenuItem("Help")
        waitForText(title)
        if (closeWithBack) pressBack() else click(inDialog(button("Close")))
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText(title)) }
    }

    @Test fun everyHelpOpensAndCloses() {
        launch(signedIn = true)
        waitForText(homeMarker)
        assertHelp("About Markera")
        waitForText(homeMarker)
        click(button("Mark"))
        waitOn(onMark, "the scan screen")
        assertHelp("How to scan", closeWithBack = true)
        waitFor(onMark, "the scan screen after its help")
        pressBack()
        click(button("History"))
        waitOn(onHistory, "History")
        assertHelp("About the history")
        waitFor(thumbnail, "a photo in History")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitOn(onSerie, "the Serie page")
        assertHelp("About the series")
        waitFor(onSerie, "the Serie page after its help")
    }

    /** Both the top bar's back button and system Back return one step, on every screen. */
    @Test fun backReturnsOneStepOnEveryScreen() {
        launch(signedIn = true)
        waitForText(homeMarker)
        assertFalse("Home leaves Back to the system", backIsHandled())
        listOf(
            Triple("Mark", onMark, "the scan screen"),
            Triple("History", onHistory, "History"),
            Triple("Statistics", onStatistics, "Statistics"),
        ).forEach { (button, marker, what) ->
            click(button(button))
            waitOn(marker, what)
            topBarBack()
            waitHome(marker)
            click(button(button))
            waitOn(marker, what)
            pressBack()
            waitHome(marker)
        }
        listOf(
            Triple("Settings", onSettings, "Settings"),
            Triple("Suggestion", onSuggestion, "the Suggestion box"),
        ).forEach { (item, marker, what) ->
            openMenuItem(item)
            waitOn(marker, what)
            topBarBack()
            waitHome(marker)
            openMenuItem(item)
            waitOn(marker, what)
            pressBack()
            waitHome(marker)
        }
        click(button("History"))
        waitOn(onHistory, "History")
        waitFor(thumbnail, "a photo in History")
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitOn(onSerie, "the Serie page")
        topBarBack()
        waitOn(onHistory, "History from the Serie page")
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(onSerie) }
        rule.onAllNodesWithText("22lr")[0].performClick()
        waitOn(onSerie, "the Serie page")
        pressBack()
        waitOn(onHistory, "History from the Serie page")
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(onSerie) }
        pressBack()
        waitHome(onHistory)
        // Back on Home leaves the app.
        assertFalse(backIsHandled())
        pressBack()
        rule.waitUntil(TIMEOUT_MS) { rule.activity.isFinishing }
    }

    // ---- History: filters, day groups, export, refresh ----

    private val zone = TimeZone.currentSystemDefault()
    private fun localDay(daysAgo: Int): LocalDate =
        kotlin.time.Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date
            .minus(daysAgo, DateTimeUnit.DAY)
    /** Local noon [daysAgo] days back, so the day and its UTC day agree. */
    private fun noon(daysAgo: Int) = LocalDateTime(localDay(daysAgo), LocalTime(12, 0)).toInstant(zone).toString()

    /**
     * Five series on five days: today's 22lr ([serverSeries]), yesterday's 9mm tagged Club, 22lr two days
     * back, 22lr tagged Club three days back, and 22lr forty days back.
     */
    private val varied by lazy {
        listOf(
            serverSeries,
            offsetSeries[0].copy(id = 502, timestamp = noon(1), caliber = "9mm", tag = "Club"),
            offsetSeries[1].copy(id = 503, timestamp = noon(2)),
            offsetSeries[2].copy(id = 504, timestamp = noon(3), tag = "Club"),
            offsetSeries[2].copy(id = 505, timestamp = noon(40)),
        )
    }
    private fun day(daysAgo: Int) = localDay(daysAgo).toString()
    private val today get() = localStamp(serverSeries.timestamp).take(10)

    private val dayHeader = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription) and hasClickAction()
    private fun header(day: String) = dayHeader and hasText(day)
    /** The day headers History shows now, top to bottom. */
    private fun shownDays() = rule.onAllNodes(dayHeader).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .map { it.config[SemanticsProperties.Text].first().text }
    private fun waitForDays(vararg days: String) {
        try {
            rule.waitUntil(TIMEOUT_MS) { shownDays() == days.toList() }
        } catch (e: Throwable) {
            throw AssertionError("Expected days ${days.toList()}, showed ${shownDays()}", e)
        }
    }
    private val allDays get() = arrayOf(today, day(1), day(2), day(3), day(40))
    private fun filterIs(store: MemoryStore, expected: HistoryFilter) =
        rule.waitUntil(TIMEOUT_MS) { decodeHistoryFilter(store.historyFilter) == expected }

    private fun openHistory(store: MemoryStore = MemoryStore(null)): MemoryStore {
        onServer = varied
        launch(signedIn = true, store = store)
        waitForText(homeMarker)
        click(button("History"))
        waitOn(onHistory, "History")
        return store
    }
    private fun chip(label: String) = click(button(label), "the \"$label\" chip")

    /** Caliber, tag and date chips narrow the list, combine, and are saved as they change. */
    @Test fun historyFiltersNarrowTheListAndAreSaved() {
        val store = openHistory()
        waitForDays(*allDays)
        chip("Filter")
        chip("9mm")
        waitForDays(day(1))
        filterIs(store, HistoryFilter(calibers = setOf(Caliber.fromLabel("9mm"))))
        chip("Clear filters")
        waitForDays(*allDays)
        filterIs(store, HistoryFilter())
        chip("Club")
        waitForDays(day(1), day(3))
        chip("22lr")
        waitForDays(day(3))
        filterIs(store, HistoryFilter(calibers = setOf(Caliber.fromLabel("22lr")), tags = setOf("Club")))
        chip("Clear filters")
        chip("Today")
        waitForDays(today)
        chip("30 d")
        waitForDays(today, day(1), day(2), day(3))
        filterIs(store, HistoryFilter(preset = DatePreset.MONTH))
        // A filter that matches nothing says so.
        chip("9mm")
        chip("Club")
        chip("Today")
        waitForText("No series match the filter.")
    }

    /** A saved filter (here a picked date range) is there when History opens; Pick… offers the range dialog. */
    @Test fun historyShowsTheSavedFilter() {
        val start = localDay(3).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val end = localDay(2).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val saved = HistoryFilter(preset = DatePreset.CUSTOM, customRange = start to end)
        val store = openHistory(MemoryStore(null, historyFilter = saved.encode()))
        waitForDays(day(2), day(3))
        waitForButton(button("Clear filters"), "Clear filters")
        chip("Filter")
        chip("${day(3)} – ${day(2)}")
        waitForText("Done")
        click(inDialog(button("Cancel")))
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("Done")) }
        waitForDays(day(2), day(3))
        assertEquals(saved, decodeHistoryFilter(store.historyFilter))
    }

    /** The newest day opens unfolded and the rest folded; a tap folds or unfolds one, and that is saved. */
    @Test fun historyDayGroupsFoldAndAreSaved() {
        val store = openHistory()
        waitForDays(*allDays)
        waitForButton(header(today) and hasStateDescription("Expanded"), "today unfolded")
        waitForButton(header(day(2)) and hasStateDescription("Collapsed"), "two days back folded")
        assertEquals(1, rule.onAllNodes(thumbnail, useUnmergedTree = true).fetchSemanticsNodes().size)
        click(header(day(2)))
        waitForButton(header(day(2)) and hasStateDescription("Expanded"), "two days back unfolded")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(thumbnail, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        click(header(today))
        waitForButton(header(today) and hasStateDescription("Collapsed"), "today folded")
        rule.waitUntil(TIMEOUT_MS) { decodeOpenDays(store.openDays) == setOf(day(2), "!$today") }
    }

    @Test fun historyShowsTheSavedDayGroups() {
        openHistory(MemoryStore(null, openDays = "${day(3)};!$today"))
        waitForDays(*allDays)
        waitForButton(header(today) and hasStateDescription("Collapsed"), "today folded")
        waitForButton(header(day(3)) and hasStateDescription("Expanded"), "three days back unfolded")
        waitForButton(header(day(1)) and hasStateDescription("Collapsed"), "yesterday folded")
    }

    /** A changed filter takes the list back to the top. */
    @Test fun historyFilterScrollsBackToTheTop() {
        onServer = List(40) { i -> offsetSeries[0].copy(id = 700L + i, timestamp = noon(i)) }
        launch(signedIn = true)
        waitForText(homeMarker)
        click(button("History"))
        waitForButton(header(day(0)), "today's group")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(header(day(0))).fetchSemanticsNodes().isEmpty() }
        chip("Filter")
        chip("1 yr")
        waitForButton(header(day(0)), "today's group again")
    }

    /** Export zips what the filter shows: both CSVs (one row per series, one per hole) and each photo. */
    @Test fun historyExportZipsTheShownSeries() {
        openHistory()
        waitForDays(*allDays)
        chip("Filter")
        chip("Club")
        waitForDays(day(1), day(3))
        openMenuItem("Export")
        rule.waitUntil(TIMEOUT_MS) { shared.isNotEmpty() }
        java.util.zip.ZipFile(shared.single()).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("series.csv", "holes.csv", "images/502.jpg", "images/504.jpg"), names)
            fun rows(name: String) = zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8)
                .split("\r\n").filter { it.isNotBlank() }
            assertEquals("A header and the two Club series", 3, rows("series.csv").size)
            assertEquals("A header and their ten holes", 11, rows("holes.csv").size)
        }
    }

    /** Pulling the list down asks the server again; offline, the saved series stay and a toast says so. */
    @Test fun historyPullToRefreshAndOffline() {
        openHistory()
        waitForDays(*allDays)
        val before = requests.count { it == "GET /series" }
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        rule.waitUntil(TIMEOUT_MS) { requests.count { it == "GET /series" } > before }
        offline = true
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        waitForText("showing saved series")
        waitForDays(*allDays)
    }

    /** With nothing saved and no network, History says it couldn't load; Try again loads it. */
    @Test fun historyOfflineWithNothingSavedCanRetry() {
        onServer = varied
        offline = true
        launch(signedIn = true)
        click(button("History"))
        waitForText("Couldn't load")
        offline = false
        click(button("Try again"))
        waitForDays(*allDays)
    }

    // ---- Statistics: filters, Target, Trend, Drift ----

    private fun openStatistics(store: MemoryStore = MemoryStore(null)): MemoryStore {
        launch(signedIn = true, store = store)
        waitForText(homeMarker)
        click(button("Statistics"))
        waitOn(onStatistics, "Statistics")
        return store
    }
    private fun described(text: String) = hasContentDescription(text, substring = true)
    private fun waitForDescribed(text: String) = waitFor(described(text), "a chart described \"$text\"")
    private fun tab(label: String) = click(button(label), "the $label tab")

    /** The caliber, tag and date chips change what Target plots, and the filter is the one History shares. */
    @Test fun statsFiltersChangeWhatIsPlotted() {
        onServer = varied
        val store = openStatistics()
        waitForDescribed("hits from 5 series")
        chip("Filter")
        chip("9mm")
        waitForDescribed("Target with 5 hits from 1 series")
        filterIs(store, HistoryFilter(calibers = setOf(Caliber.fromLabel("9mm"))))
        chip("Clear filters")
        chip("Club")
        waitForDescribed("hits from 2 series")
        chip("Clear filters")
        chip("30 d")
        waitForDescribed("hits from 4 series")
        chip("Today")
        waitForDescribed("hits from 1 series")
        chip("9mm")
        waitForText("No series match the filter.")
    }

    /** Target's legend names each caliber shown; the timeline knob on one series shows only it, and its photo. */
    @Test fun statsTargetLegendKnobAndPhoto() {
        onServer = varied
        openStatistics()
        waitForDescribed("hits from 5 series")
        waitForExact("9mm")
        waitForExact("Mean")
        waitForExact("Median")
        val knob = hasContentDescription("Series timeline") and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        rule.onNode(knob).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        waitForDescribed("hits from 1 series")
        // Only today's 22lr: one caliber needs no swatches.
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasText("9mm")) }
        waitForExact("Mean")
        click(hasContentDescription("Show photo") and hasClickAction(), "Show photo")
        waitFor(hasTestTag(STATS_PHOTO_TAG), "the series' photo")
        waitForExact(localStamp(serverSeries.timestamp))
        click(inDialog(button("Close")))
        rule.waitUntil(TIMEOUT_MS) { !rule.exists(hasTestTag(STATS_PHOTO_TAG)) }
    }

    /** Trend draws a line per caliber until Per caliber is switched off; one caliber filtered in offers no split. */
    @Test fun statsTrendSplitsByCaliber() {
        onServer = varied
        openStatistics()
        waitForDescribed("hits from 5 series")
        tab("Trend")
        waitForDescribed("Trend chart, lines: 2, points: 5")
        chip("Per caliber")
        waitForDescribed("Trend chart, lines: 1, points: 5")
        chip("Filter")
        chip("9mm")
        waitForDescribed("Trend chart, lines: 1, points: 1")
        assertFalse(rule.exists(hasText("Per caliber")))
    }

    /** Drift charts a point per day, or per series; with nothing in the filter it says so. */
    @Test fun statsDriftChartOrEmpty() {
        onServer = varied
        openStatistics()
        waitForDescribed("hits from 5 series")
        tab("Drift")
        waitForDescribed("over time, 5 points")
        chip("Filter")
        chip("30 d")
        waitForDescribed("over time, 4 points")
        chip("9mm")
        chip("Today")
        waitForText("No series match the filter.")
        assertFalse(rule.exists(described("over time,")))
    }

    /** With no series scanned with a ring, Statistics says there is nothing to plot against. */
    @Test fun statsWithoutGeometrySaysSo() {
        onServer = varied.map { it.copy(geometry = null) }
        openStatistics()
        waitForText("None of your series has a ring")
    }

    /** A day opened from the Serie page filters only that look: the filter History shares is left as it was. */
    @Test fun statisticsDayLeavesTheHistoryFilterAlone() {
        var newest = Clock.System.now() - 1.hours
        if (newest.toEpochMilliseconds().let { ms -> kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time.toSecondOfDay() } < 600) {
            newest -= 15.minutes
        }
        onServer = listOf(serverSeries.copy(timestamp = newest.toString())) + offsetSeries.mapIndexed { i, s ->
            s.copy(timestamp = (newest - (i + 1).minutes).toString())
        }
        val kept = HistoryFilter(calibers = setOf(Caliber.fromLabel("22lr"))).encode()
        val store = MemoryStore(null, historyFilter = kept)
        launch(signedIn = true, store = store)
        waitForText(homeMarker)
        click(button("History"))
        waitFor(thumbnail, "a photo in History")
        // The filter's summary reads 22lr too; the series is the card.
        click(button("22lr"), "the newest series' card")
        waitForText("See this day in Insights")
        rule.onNode(hasText("See this day in Insights")).performScrollTo().performClick()
        waitFor(hasContentDescription("Insights target with 20 shots", substring = true), "the Insights target")
        chip("Filter")
        chip("1 yr")
        tab("Target")
        waitForDescribed("hits from 4 series")
        rule.waitForIdle()
        assertEquals(kept, store.historyFilter)
        pressBack()
        waitForText("See this day in Insights")
        pressBack()
        waitForButton(button("Clear filters"), "History's own filter, still on")
        assertEquals(kept, store.historyFilter)
    }

    private companion object {
        const val TOKEN = "ui-test-token"
        const val USER = 7L
        const val TIMEOUT_MS = 15_000L

        /** Unrotated and square: centre (500, 500), the 6/7 edge 100 px out, so 1 px = 1 mm. */
        val GEOMETRY = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)
    }
}
