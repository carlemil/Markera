package se.kjellstrand.markera.series

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

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

    override suspend fun readCaliber(): Caliber =
        Caliber.fromLabel(dataStore.data.first()[Keys.caliber] ?: "")

    override suspend fun writeCaliber(caliber: Caliber) {
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
