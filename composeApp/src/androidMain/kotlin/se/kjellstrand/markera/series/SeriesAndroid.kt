package se.kjellstrand.markera.series

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
// Historik's filter and unfolded days, also excluded from backup: a reinstall restored a
// months-old "22lr, this week" filter, and the first History after sign-in looked half empty.
private val Context.viewDataStore by preferencesDataStore(
    name = "markera_view",
    produceMigrations = { listOf(DropViewStateFromSettings(it.backendDataStore)) },
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
    val guideColor = longPreferencesKey("guide_color")
    val shootingHand = stringPreferencesKey("shooting_hand")
    val insightsEnabled = booleanPreferencesKey("insights_enabled")
    val sightSetups = stringPreferencesKey("sight_setups")
    val dismissedInsights = stringPreferencesKey("dismissed_insights")
}

/** Moves a login saved before [authDataStore] existed out of the settings file. */
internal fun MoveLoginOut(legacy: DataStore<Preferences>) =
    MoveKeysOut(legacy, listOf(Keys.token, Keys.userId, Keys.provider))

/**
 * Drops Historik's view state from the backed-up settings file instead of moving it: the copy
 * there may itself be a months-old restore, and moving it would show that stale filter again.
 * History opens unfiltered once after the update.
 */
internal fun DropViewStateFromSettings(legacy: DataStore<Preferences>) =
    MoveKeysOut(legacy, listOf(Keys.historyFilter, Keys.openDays), copy = false)

/** Moves [keys] out of [legacy] into the store this migrates (or only drops them); the rest stays. */
internal class MoveKeysOut(
    private val legacy: DataStore<Preferences>,
    private val keys: List<Preferences.Key<*>>,
    private val copy: Boolean = true,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val old = legacy.data.first()
        return keys.any { it in old }
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!copy) return currentData
        val old = legacy.data.first()
        return currentData.toMutablePreferences().apply {
            @Suppress("UNCHECKED_CAST")
            keys.forEach { key -> old[key]?.let { this[key as Preferences.Key<Any>] = it } }
        }.toPreferences()
    }

    override suspend fun cleanUp() {
        legacy.edit { prefs -> keys.forEach { prefs.remove(it) } }
    }
}

/** Persists the Markera backend login in Preferences DataStore. */
class DataStoreBackendTokenStore(context: Context) : BackendTokenStore {

    private val dataStore = context.applicationContext.backendDataStore
    private val authStore = context.applicationContext.authDataStore
    private val viewStore = context.applicationContext.viewDataStore

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

    override suspend fun readGuideColor(): Long? = dataStore.data.first()[Keys.guideColor]

    override suspend fun writeGuideColor(argb: Long) {
        dataStore.edit { it[Keys.guideColor] = argb }
    }

    override suspend fun readShootingHand(): String? = dataStore.data.first()[Keys.shootingHand]

    override suspend fun writeShootingHand(value: String) {
        dataStore.edit { it[Keys.shootingHand] = value }
    }

    override suspend fun readInsightsEnabled(): Boolean? = dataStore.data.first()[Keys.insightsEnabled]

    override suspend fun writeInsightsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.insightsEnabled] = value }
    }

    override suspend fun readSightSetups(): String? = dataStore.data.first()[Keys.sightSetups]

    override suspend fun writeSightSetups(value: String) {
        dataStore.edit { it[Keys.sightSetups] = value }
    }

    override suspend fun readDismissedInsights(): String? = dataStore.data.first()[Keys.dismissedInsights]

    override suspend fun writeDismissedInsights(value: String) {
        dataStore.edit { it[Keys.dismissedInsights] = value }
    }

    override suspend fun readCaliber(): Caliber =
        Caliber.fromStored(dataStore.data.first()[Keys.caliber])

    override suspend fun writeCaliber(caliber: Caliber) {
        dataStore.edit { it[Keys.caliber] = caliber.label }
    }

    override suspend fun readCustomCalibers(): List<Caliber> =
        decodeCustomCalibers(dataStore.data.first()[Keys.customCalibers])

    override suspend fun writeCustomCalibers(calibers: List<Caliber>) {
        dataStore.edit { it[Keys.customCalibers] = calibers.encodeCustomCalibers() }
    }

    override suspend fun readHistoryFilter(): String? = viewStore.data.first()[Keys.historyFilter]

    override suspend fun writeHistoryFilter(value: String) {
        viewStore.edit { it[Keys.historyFilter] = value }
    }

    override suspend fun readOpenDays(): String? = viewStore.data.first()[Keys.openDays]

    override suspend fun writeOpenDays(value: String) {
        viewStore.edit { it[Keys.openDays] = value }
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
        dataStore.edit { it.remove(Keys.tag) }
        viewStore.edit { it.remove(Keys.historyFilter) }
    }
}
