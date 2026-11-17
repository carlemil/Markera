package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.SemanticsMatcher
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.series.seriesJson
import se.kjellstrand.markera.ui.MarkeraApp
import se.kjellstrand.markera.ui.history.SERIES_THUMBNAIL_TAG

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
    )

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
            request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
            !authorized -> respond("", HttpStatusCode.Unauthorized)
            request.method == HttpMethod.Get && path == "/series/${serverSeries.id}/image" ->
                respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            request.method == HttpMethod.Get && path == "/series" ->
                respond(seriesJson.encodeToString(listOf(serverSeries)), HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private class MemoryStore(var auth: BackendAuth?) : BackendTokenStore {
        override suspend fun read() = auth
        override suspend fun write(auth: BackendAuth) { this.auth = auth }
        override suspend fun clear() { auth = null }
        // English whatever the phone's language, so the texts below match.
        override suspend fun readLanguage() = "en"
    }

    private fun launch(signedIn: Boolean, seed: (MarkeraDb) -> Unit = {}): SeriesServices {
        val driver = AndroidSqliteDriver(MarkeraDb.Schema, context, null)
        seed(MarkeraDb(driver))
        val services = SeriesServices(
            engine = engine,
            baseUrl = "http://fake",
            store = MemoryStore(if (signedIn) BackendAuth(TOKEN, USER, "dev") else null),
            driver = driver,
            cacheDir = Path(File(root, "cache").apply { mkdirs() }.path),
            filesDir = Path(File(root, "files").apply { mkdirs() }.path),
        )
        val app = AppServices(series = services, modelPath = "no-model-in-ui-tests", shareFile = {})
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

    /**
     * Signing in after start must load that account's series on Home, without leaving the
     * screen. 1.14.0 refreshed before the login was published and Home stayed empty.
     */
    @Test fun homeLoadsSeriesAfterSigningIn() {
        val services = launch(signedIn = false)
        waitForText("Mark")
        rule.waitForIdle()
        assertFalse("Signed out Home must show no series", rule.exists(hasText("series in 7 days")))
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

    private companion object {
        const val TOKEN = "ui-test-token"
        const val USER = 7L
        const val TIMEOUT_MS = 15_000L
    }
}
