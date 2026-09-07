package se.kjellstrand.markera.series

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.client.engine.okhttp.OkHttp
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.BuildConfig
import se.kjellstrand.markera.series.db.MarkeraDb
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/**
 * Wires the Markera series backend stack (HTTP client → API → session → local
 * cache). One instance for the app; create it in the nav root and pass it down.
 */
class SeriesServices(context: Context) {

    val session: BackendSessionRepository
    val api: SeriesApi
    val store = DataStoreBackendTokenStore(context)
    val repository: SeriesRepository

    init {
        val client = createWebshooterHttpClient(OkHttp.create())
        lateinit var repo: BackendSessionRepository
        api = SeriesApi(client, BuildConfig.BACKEND_URL, tokenProvider = { repo.currentToken })
        repo = BackendSessionRepository(api, store)
        session = repo
        repository = SeriesRepository(
            api = api,
            db = MarkeraDb(AndroidSqliteDriver(MarkeraDb.Schema, context, "markera-series.db")),
            images = FileImageCache(File(context.cacheDir, "series")),
            session = repo,
        )
    }
}

/** The cached series JPEGs, one file per id under `cacheDir/series/`. */
class FileImageCache(private val dir: File) : ImageCache {

    private fun file(id: Long) = File(dir, "$id.jpg")

    override fun read(id: Long): ByteArray? =
        file(id).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    override fun write(id: Long, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            file(id).writeBytes(bytes)
        }
    }

    override fun delete(id: Long) {
        file(id).delete()
    }

    override fun clear() {
        dir.deleteRecursively()
    }
}

private const val IMAGE_MAX_DIM = 3072

/**
 * The scanned frame as a JPEG for the backend. These images are training data,
 * so they go up close to what the sensor gave: the cap only guards against a
 * bigger sensor than today's ~3000 px square capture.
 */
suspend fun encodeSeriesJpeg(image: Bitmap): EncodedImage = withContext(Dispatchers.Default) {
    val longest = maxOf(image.width, image.height)
    val scaled = if (longest > IMAGE_MAX_DIM) {
        val s = IMAGE_MAX_DIM.toFloat() / longest
        Bitmap.createScaledBitmap(image, (image.width * s).toInt(), (image.height * s).toInt(), true)
    } else {
        image
    }
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (scaled !== image) scaled.recycle()
        // The size reported is the *source* one the hole coordinates are in.
        EncodedImage(out.toByteArray(), image.width, image.height)
    }
}

/**
 * Decode a stored series JPEG down to at most [maxDim] px on the longer side.
 * The full frame is ~3000² (36 MB as ARGB), far more than any screen needs, so
 * every display path subsamples. Hole markers are placed from the series'
 * stored `imageWidth`/`imageHeight`, so the decoded size does not matter.
 */
fun decodeSeriesJpeg(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private val Context.backendDataStore by preferencesDataStore(name = "markera_backend")

/** Persists the Markera backend login in Preferences DataStore. */
class DataStoreBackendTokenStore(context: Context) : BackendTokenStore {

    private val dataStore = context.applicationContext.backendDataStore

    private object Keys {
        val token = stringPreferencesKey("token")
        val userId = longPreferencesKey("user_id")
        val provider = stringPreferencesKey("provider")
        val caliber = stringPreferencesKey("caliber")
    }

    /** The chosen caliber shares this store but survives [clear] (sign-out). */
    suspend fun readCaliber(): Caliber =
        Caliber.fromLabel(dataStore.data.first()[Keys.caliber] ?: "")

    suspend fun writeCaliber(caliber: Caliber) {
        dataStore.edit { it[Keys.caliber] = caliber.label }
    }

    override suspend fun read(): BackendAuth? {
        val prefs = dataStore.data.first()
        val token = prefs[Keys.token] ?: return null
        return BackendAuth(
            token = token,
            userId = prefs[Keys.userId] ?: return null,
            provider = prefs[Keys.provider] ?: return null,
        )
    }

    override suspend fun write(auth: BackendAuth) {
        dataStore.edit { prefs ->
            prefs[Keys.token] = auth.token
            prefs[Keys.userId] = auth.userId
            prefs[Keys.provider] = auth.provider
        }
    }

    override suspend fun clear() {
        // Only the login — the caliber is a device preference, not part of it.
        dataStore.edit { prefs ->
            prefs.remove(Keys.token)
            prefs.remove(Keys.userId)
            prefs.remove(Keys.provider)
        }
    }
}
