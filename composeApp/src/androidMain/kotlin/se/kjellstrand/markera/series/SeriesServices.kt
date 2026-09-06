package se.kjellstrand.markera.series

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.engine.okhttp.OkHttp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import se.kjellstrand.markera.BuildConfig
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/**
 * Wires the Markera series backend stack (HTTP client → API → session).
 * One instance for the app; create it in the nav root and pass it down.
 */
class SeriesServices(context: Context) {

    val session: BackendSessionRepository
    val api: SeriesApi
    val store = DataStoreBackendTokenStore(context)

    init {
        val client = createWebshooterHttpClient(OkHttp.create())
        lateinit var repo: BackendSessionRepository
        api = SeriesApi(client, BuildConfig.BACKEND_URL, tokenProvider = { repo.currentToken })
        repo = BackendSessionRepository(api, store)
        session = repo
    }
}

private const val IMAGE_MAX_DIM = 1024

/**
 * The scanned frame as a modest JPEG for the backend — the thumbnail in the
 * history list is all it feeds, so 1024 px on the longer side is plenty.
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
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== image) scaled.recycle()
        // The size reported is the *source* one the hole coordinates are in.
        EncodedImage(out.toByteArray(), image.width, image.height)
    }
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
