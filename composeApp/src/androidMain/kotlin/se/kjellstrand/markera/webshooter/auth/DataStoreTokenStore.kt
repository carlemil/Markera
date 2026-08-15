package se.kjellstrand.markera.webshooter.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.webshooterDataStore by preferencesDataStore(name = "webshooter_session")

/** Persists the webshooter login in Preferences DataStore. */
class DataStoreTokenStore(context: Context) : TokenStore {

    private val dataStore = context.applicationContext.webshooterDataStore

    private object Keys {
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
        val userId = longPreferencesKey("user_id")
        val isAdmin = booleanPreferencesKey("is_admin")
        val userName = stringPreferencesKey("user_name")
    }

    override suspend fun read(): AuthSession? {
        val prefs = dataStore.data.first()
        val accessToken = prefs[Keys.accessToken] ?: return null
        return AuthSession(
            accessToken = accessToken,
            refreshToken = prefs[Keys.refreshToken],
            userId = prefs[Keys.userId],
            isAdmin = prefs[Keys.isAdmin] ?: false,
            userName = prefs[Keys.userName],
        )
    }

    override suspend fun write(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[Keys.accessToken] = session.accessToken
            session.refreshToken?.let { prefs[Keys.refreshToken] = it }
                ?: prefs.remove(Keys.refreshToken)
            session.userId?.let { prefs[Keys.userId] = it } ?: prefs.remove(Keys.userId)
            prefs[Keys.isAdmin] = session.isAdmin
            session.userName?.let { prefs[Keys.userName] = it } ?: prefs.remove(Keys.userName)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
