package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.db.MarkeraDb

/**
 * #28: a series photo whose download outlives a [se.kjellstrand.markera.series.SeriesRepository.clear]
 * (sign-out, account deletion, another user) leaves no thumbnail, and no photo, in the cache.
 * The real repository, SQLite, file cache and thumbnail cutter, against a fake backend.
 */
class LateThumbnailTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "late-thumb-${System.nanoTime()}")

    /** Set, GET /series/45/image waits for [gate]; [entered] says it is waiting. */
    @Volatile private var hold = false
    private val gate = CompletableDeferred<Unit>()
    private val entered = CompletableDeferred<Unit>()

    private val jpeg: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 180, 120)) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private val engine = MockEngine { request ->
        if (request.method == HttpMethod.Get && request.url.encodedPath == "/series/45/image") {
            if (hold) {
                entered.complete(Unit)
                gate.await()
            }
            respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        } else {
            respond("", HttpStatusCode.NoContent)
        }
    }

    private class Store(private var auth: BackendAuth?) : BackendTokenStore {
        override suspend fun read() = auth
        override suspend fun write(auth: BackendAuth) { this.auth = auth }
        override suspend fun clear() { auth = null }
    }

    private fun services(): SeriesServices = SeriesServices(
        engine = engine,
        baseUrl = "http://fake",
        store = Store(BackendAuth("late-thumb-token", 7L, "dev")),
        driver = AndroidSqliteDriver(MarkeraDb.Schema, context, null),
        cacheDir = Path(File(root, "cache").apply { mkdirs() }.path),
        filesDir = Path(File(root, "files").apply { mkdirs() }.path),
    ).also { runBlocking { it.session.restore() } }

    private val cached get() = File(root, "cache/series/45.jpg")
    private val thumb get() = File(root, "cache/series/45.thumb.jpg")

    @After fun cleanUp() {
        gate.complete(Unit)
        root.deleteRecursively()
    }

    /** The control: signed in throughout, the photo and its thumbnail are both kept. */
    @Test
    fun aThumbnailWhileSignedInIsCutAndKept() = runBlocking<Unit> {
        val s = services()
        assertNotNull(s.repository.thumbnail(45, 64))
        assertTrue("the thumbnail is cached", thumb.exists())
        assertTrue("the photo is cached", cached.exists())
    }

    @Test
    fun aPhotoArrivingAfterClearLeavesNoThumbnail() = runBlocking<Unit> {
        val s = services()
        hold = true
        val pending = async(Dispatchers.Default) { s.repository.thumbnail(45, 64) }
        withTimeout(10_000) { entered.await() }
        s.repository.clear()
        gate.complete(Unit)
        assertNull("a late photo must not make a thumbnail", withTimeout(10_000) { pending.await() })
        assertFalse("no thumbnail may be written after clear()", thumb.exists())
        assertFalse("no photo may be written after clear()", cached.exists())
    }
}
