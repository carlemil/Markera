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
        val historyFilter = stringPreferencesKey("history_filter")
        val openDays = stringPreferencesKey("history_open_days")
        val tag = stringPreferencesKey("tag")
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
    }

    // Stored as "" rather than removed, so "cleared the tag" and "never set one" read alike.
    override suspend fun readTag(): String? = normalizeTag(dataStore.data.first()[Keys.tag])

    override suspend fun writeTag(value: String?) {
        dataStore.edit { it[Keys.tag] = value ?: "" }
    }

    override suspend fun readTheme(): String? = dataStore.data.first()[Keys.theme]

    override suspend fun writeTheme(value: String) {
        dataStore.edit { it[Keys.theme] = value }
    }

    override suspend fun readLanguage(): String? = dataStore.data.first()[Keys.language]?.ifEmpty { null }

    override suspend fun writeLanguage(value: String?) {
        dataStore.edit { it[Keys.language] = value ?: "" }
    }

    override suspend fun readCaliber(): Caliber =
        Caliber.fromLabel(dataStore.data.first()[Keys.caliber] ?: "")

    override suspend fun writeCaliber(caliber: Caliber) {
        dataStore.edit { it[Keys.caliber] = caliber.label }
    }

    override suspend fun readHistoryFilter(): String? = dataStore.data.first()[Keys.historyFilter]

    override suspend fun writeHistoryFilter(value: String) {
        dataStore.edit { it[Keys.historyFilter] = value }
    }

    override suspend fun readOpenDays(): String? = dataStore.data.first()[Keys.openDays]

    override suspend fun writeOpenDays(value: String) {
        dataStore.edit { it[Keys.openDays] = value }
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
        // The login plus what names this user's tags; the caliber is a device preference.
        dataStore.edit { prefs ->
            prefs.remove(Keys.token)
            prefs.remove(Keys.userId)
            prefs.remove(Keys.provider)
            prefs.remove(Keys.tag)
            prefs.remove(Keys.historyFilter)
        }
    }
}
