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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isDialog
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
import kotlinx.datetime.Instant as KxInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.LocalMark
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.SeriesRequest
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.ui.markera.SCAN_VIEWPORT_TAG
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.series.seriesJson
import se.kjellstrand.markera.ui.HOME_INSIGHT_TAG
import se.kjellstrand.markera.ui.MarkeraApp
import se.kjellstrand.markera.ui.history.COMPARISON_DAY_LINE_TAG
import se.kjellstrand.markera.ui.history.COMPARISON_LINE_TAG
import se.kjellstrand.markera.ui.history.SERIES_THUMBNAIL_TAG
import se.kjellstrand.markera.ui.stats.SIGHT_CLICK_TAG
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
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "ui-test-${System.nanoTime()}")
    private val requests = CopyOnWriteArrayList<String>()
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

    /** One series shot an hour ago, with a photo, as the fake server holds it. */
    private val serverSeries = SeriesDto(
        id = 501,
        timestamp = (Clock.System.now() - 1.hours).toString(),
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
        val authorized = request.headers[HttpHeaders.Authorization] == "Bearer $TOKEN"
        val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when {
            request.method == HttpMethod.Post && path == "/auth/dev" ->
                respond("""{"token":"$TOKEN","userId":$USER}""", HttpStatusCode.OK, json)
            request.method == HttpMethod.Delete -> {
                Regex("""/series/(\d+)""").matchEntire(path)?.let { gone += it.groupValues[1].toLong() }
                respond("", HttpStatusCode.NoContent)
            }
            !authorized -> respond("", HttpStatusCode.Unauthorized)
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
    ) : BackendTokenStore {
        override suspend fun readCaliber() = caliber
        override suspend fun writeCaliber(caliber: Caliber) { this.caliber = caliber }
        override suspend fun read() = auth
        override suspend fun write(auth: BackendAuth) { this.auth = auth }
        override suspend fun clear() { auth = null }
        // English whatever the phone's language, so the texts below match.
        override suspend fun readLanguage() = "en"
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
            shareFile = {},
            scanStandIns = ScanStandIns(FakeFrameSource()) { scanEngine },
        )
        rule.setContent { MarkeraApp(app) }
        return services
    }

    @After fun cleanUp() { root.deleteRecursively() }

    private fun SemanticsNodeInteractionsProvider.exists(matcher: SemanticsMatcher) =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, what: String) {
        try {
            rule.waitUntil(TIMEOUT_MS) { rule.exists(matcher) }
        } catch (e: Throwable) {
            throw AssertionError("Never showed $what; requests: $requests " + rule.onRoot(useUnmergedTree = true).printToString(), e)
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
        if (newest.toEpochMilliseconds().let { ms -> KxInstant.fromEpochMilliseconds(ms).toLocalDateTime(zone).time.toSecondOfDay() } < 600) {
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
    private val offsetKey get() = "offset|22lr|${KxInstant.parse(serverSeries.timestamp).toEpochMilliseconds()}"

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
        offsetOnly(serverSeries.copy(id = 520, timestamp = (Clock.System.now() - 10.minutes).toString(), holes = roundHoles))
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
        val today = KxInstant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date
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
        val today = KxInstant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date
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

    private companion object {
        const val TOKEN = "ui-test-token"
        const val USER = 7L
        const val TIMEOUT_MS = 15_000L

        /** Unrotated and square: centre (500, 500), the 6/7 edge 100 px out, so 1 px = 1 mm. */
        val GEOMETRY = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)
    }
}
