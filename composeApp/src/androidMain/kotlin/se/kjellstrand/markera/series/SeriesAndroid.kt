package se.kjellstrand.markera.series

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// Settings (backed up) and the login (excluded in res/xml/*_rules.xml) live in
// separate files so a restore keeps the one without carrying the token to another device.
private val Context.backendDataStore by preferencesDataStore(name = "markera_backend")
private val Context.authDataStore by preferencesDataStore(
    name = "markera_auth",
    produceMigrations = { listOf(MoveLoginOut(it.backendDataStore)) },
)

private object Keys {
    val token = stringPreferencesKey("token")
    val userId = longPreferencesKey("user_id")
    val provider = stringPreferencesKey("provider")
    val caliber = stringPreferencesKey("caliber")
    val customCalibers = stringPreferencesKey("custom_calibers")
    val historyFilter = stringPreferencesKey("history_filter")
    val openDays = stringPreferencesKey("history_open_days")
    val tag = stringPreferencesKey("tag")
    val theme = stringPreferencesKey("theme")
    val language = stringPreferencesKey("language")
}

/** Moves a login saved before [authDataStore] existed out of the settings file. */
internal class MoveLoginOut(private val legacy: DataStore<Preferences>) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        legacy.data.first()[Keys.token] != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = legacy.data.first()
        return currentData.toMutablePreferences().apply {
            old[Keys.token]?.let { this[Keys.token] = it }
            old[Keys.userId]?.let { this[Keys.userId] = it }
            old[Keys.provider]?.let { this[Keys.provider] = it }
        }.toPreferences()
    }

    override suspend fun cleanUp() {
        legacy.edit {
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.provider)
        }
    }
}

/** Persists the Markera backend login in Preferences DataStore. */
class DataStoreBackendTokenStore(context: Context) : BackendTokenStore {

    private val dataStore = context.applicationContext.backendDataStore
    private val authStore = context.applicationContext.authDataStore

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

    override suspend fun readCustomCalibers(): List<Caliber> =
        decodeCustomCalibers(dataStore.data.first()[Keys.customCalibers])

    override suspend fun writeCustomCalibers(calibers: List<Caliber>) {
        dataStore.edit { it[Keys.customCalibers] = calibers.encodeCustomCalibers() }
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
        val prefs = authStore.data.first()
        val token = prefs[Keys.token] ?: return null
        return BackendAuth(
            token = token,
            userId = prefs[Keys.userId] ?: return null,
            provider = prefs[Keys.provider] ?: return null,
        )
    }

    override suspend fun write(auth: BackendAuth) {
        authStore.edit { prefs ->
            prefs[Keys.token] = auth.token
            prefs[Keys.userId] = auth.userId
            prefs[Keys.provider] = auth.provider
        }
    }

    override suspend fun clear() {
        // The login plus what names this user's tags; the caliber is a device preference.
        authStore.edit { it.clear() }
        dataStore.edit { prefs ->
            prefs.remove(Keys.tag)
            prefs.remove(Keys.historyFilter)
        }
    }
}
