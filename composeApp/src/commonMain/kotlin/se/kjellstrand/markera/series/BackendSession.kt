package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The persisted Markera backend login. [provider] is `google`, `apple` or `dev`. */
data class BackendAuth(val token: String, val userId: Long, val provider: String)

/** Platform persistence for [BackendAuth] (DataStore on Android, NSUserDefaults on iOS). */
interface BackendTokenStore {
    suspend fun read(): BackendAuth?
    suspend fun write(auth: BackendAuth)
    suspend fun clear()

    /** The chosen caliber shares this store but survives [clear] (sign-out). */
    suspend fun readCaliber(): Caliber = Caliber.NONE
    suspend fun writeCaliber(caliber: Caliber) {}

    /** The user's own calibers ([decodeCustomCalibers]-encoded): a device preference, survives [clear]. */
    suspend fun readCustomCalibers(): List<Caliber> = emptyList()
    suspend fun writeCustomCalibers(calibers: List<Caliber>) {}

    /** The Historik screen's encoded [se.kjellstrand.markera.ui.history.HistoryFilter]; [clear] drops it, it names the user's tags. */
    suspend fun readHistoryFilter(): String? = null
    suspend fun writeHistoryFilter(value: String) {}

    /** The Historik day groups the user left unfolded, [encodeOpenDays][se.kjellstrand.markera.ui.history.encodeOpenDays]-encoded; also survives [clear]. */
    suspend fun readOpenDays(): String? = null
    suspend fun writeOpenDays(value: String) {}

    /** The last tag chosen, so a whole session costs no extra taps; per user, so [clear] drops it. */
    suspend fun readTag(): String? = null
    suspend fun writeTag(value: String?) {}

    /** The Settings screen's theme ([se.kjellstrand.markera.ui.settings.ThemeMode] name) and language code; device preferences, survive [clear]. */
    suspend fun readTheme(): String? = null
    suspend fun writeTheme(value: String) {}
    suspend fun readLanguage(): String? = null
    suspend fun writeLanguage(value: String?) {}
}

/**
 * Owns the backend login state: restore on app start, sign in via a provider
 * ID token (or the dev endpoint), sign out. The backend session token is
 * opaque and expires after 90 days unused (every request slides it), so there
 * is no refresh — a 401 means sign in again.
 *
 * Construct the [SeriesApi] with `tokenProvider = { currentToken }` of this
 * instance so every request picks up the latest token.
 */
class BackendSessionRepository(
    private val api: SeriesApi,
    private val store: BackendTokenStore,
) {
    private val _auth = MutableStateFlow<BackendAuth?>(null)
    val auth: StateFlow<BackendAuth?> = _auth.asStateFlow()

    val currentToken: String? get() = _auth.value?.token

    suspend fun restore(): BackendAuth? = store.read().also { _auth.value = it }

    suspend fun signInGoogle(idToken: String): BackendAuth =
        publish("google", api.authGoogle(idToken))

    suspend fun signInApple(idToken: String): BackendAuth =
        publish("apple", api.authApple(idToken))

    /** The browser flow's ending (Android). Stores exactly what iOS's native [signInApple] would. */
    suspend fun signInAppleClaim(state: String, secret: String): BackendAuth =
        publish("apple", api.claimApple(state, secret))

    suspend fun signInDev(subject: String): BackendAuth =
        publish("dev", api.authDev(subject))

    suspend fun signOut() {
        // Best effort, and while the token is still set (the api reads it from here): offline, or the 401
        // after deleteAccount, must still sign out locally.
        if (currentToken != null) {
            try {
                api.revokeSession()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        _auth.value = null
        store.clear()
    }

    private suspend fun publish(provider: String, response: BackendAuthResponse): BackendAuth {
        val auth = BackendAuth(response.token, response.userId, provider)
        _auth.value = auth
        store.write(auth)
        return auth
    }
}
