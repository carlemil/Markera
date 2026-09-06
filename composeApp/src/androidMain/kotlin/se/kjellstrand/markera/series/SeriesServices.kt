package se.kjellstrand.markera.series

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import se.kjellstrand.markera.BuildConfig
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/**
 * Wires the Markera series backend stack (HTTP client → API → session).
 * One instance for the app; create it in the nav root and pass it down.
 */
class SeriesServices(context: Context) {

    val session: BackendSessionRepository
    val api: SeriesApi

    init {
        val client = createWebshooterHttpClient(OkHttp.create())
        lateinit var repo: BackendSessionRepository
        api = SeriesApi(client, BuildConfig.BACKEND_URL, tokenProvider = { repo.currentToken })
        repo = BackendSessionRepository(api, DataStoreBackendTokenStore(context))
        session = repo
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
        dataStore.edit { it.clear() }
    }
}
