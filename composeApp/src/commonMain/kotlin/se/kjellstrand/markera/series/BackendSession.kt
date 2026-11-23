package se.kjellstrand.markera.series

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The persisted Markera backend login. [provider] is `google`, `apple` or `dev`. */
data class BackendAuth(val token: String, val userId: Long, val provider: String)

/** Platform persistence for [BackendAuth] (DataStore on Android; on iOS the token in the Keychain, the rest in NSUserDefaults). */
interface BackendTokenStore {
    suspend fun read(): BackendAuth?
    suspend fun write(auth: BackendAuth)
    suspend fun clear()

    /**
     * The chosen caliber shares this store but survives [clear] (sign-out); never
     * chosen is [Caliber.DEFAULT] ([Caliber.fromStored]).
     */
    suspend fun readCaliber(): Caliber = Caliber.DEFAULT
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

    /** The live viewfinder guide's colour as ARGB; a device preference, survives [clear]. Null = never chosen. */
    suspend fun readGuideColor(): Long? = null
    suspend fun writeGuideColor(argb: Long) {}

    /**
     * The shooting hand ([se.kjellstrand.markera.ui.settings.ShootingHand] name) and whether
     * insights are on: device preferences, survive [clear], never sent to the server.
     * Null = never chosen.
     */
    suspend fun readShootingHand(): String? = null
    suspend fun writeShootingHand(value: String) {}
    suspend fun readInsightsEnabled(): Boolean? = null
    suspend fun writeInsightsEnabled(value: Boolean) {}

    /**
     * The sight helper's per-caliber setups ([se.kjellstrand.markera.series.stats.encodeSightSetups]:
     * distance, mm per click, when the sights were adjusted): a device preference, survives [clear],
     * never sent to the server. Null = none entered.
     */
    suspend fun readSightSetups(): String? = null
    suspend fun writeSightSetups(value: String) {}

    /**
     * The Home insight cards the user dismissed, one key per line
     * ([se.kjellstrand.markera.ui.stats.encodeDismissedInsights]); a device preference, survives
     * [clear], never sent to the server. Null = none dismissed yet.
     */
    suspend fun readDismissedInsights(): String? = null
    suspend fun writeDismissedInsights(value: String) {}
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
    var claimGate: ClaimGate? = null,
) {
    private val _pendingAuth = MutableStateFlow<BackendAuth?>(null)
    val pendingAuth: StateFlow<BackendAuth?> = _pendingAuth.asStateFlow()
    suspend fun confirmPending(): BackendAuth? {
        val pending = _pendingAuth.value ?: return null
        // Persistence precedes public state: observers may start refresh immediately.
        store.write(pending)
        setAuth(pending)
        _pendingAuth.value = null
        return pending
    }
    suspend fun discardPending() {
        val pending = _pendingAuth.value ?: return
        _pendingAuth.value = null
        try { api.revokeSession(pending.token) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Best effort, never persist the held token. */ }
    }
    private val _auth = MutableStateFlow<BackendAuth?>(null)
    val auth: StateFlow<BackendAuth?> = _auth.asStateFlow()

    private val _generation = MutableStateFlow(0L)

    /**
     * Bumped every time the login changes (sign-in, sign-out, restore). A network call that began under
     * an older generation was overtaken: its 401 is not an expired session and its answer is not cached.
     */
    val generation: Long get() = _generation.value

    private fun setAuth(value: BackendAuth?) {
        _auth.value = value
        _generation.update { it + 1 }
    }

    val currentToken: String? get() = _auth.value?.token

    suspend fun restore(): BackendAuth? = store.read().also { setAuth(it) }

    suspend fun signInGoogle(idToken: String): BackendAuth =
        publish("google", api.authGoogle(idToken))

    suspend fun signInApple(idToken: String): BackendAuth =
        publish("apple", api.authApple(idToken))

    /** The browser flow's ending (Android). Stores exactly what iOS's native [signInApple] would. */
    suspend fun signInAppleClaim(state: String, secret: String, nonce: String): BackendAuth =
        publish("apple", api.claimApple(state, secret, nonce))

    suspend fun signInDev(subject: String): BackendAuth =
        publish("dev", api.authDev(subject))

    /**
     * Signed out locally first, so leaving the screen mid-call (which cancels this) or being offline can
     * never leave the user signed in; the server-side revoke after it is best effort.
     */
    suspend fun signOut() {
        discardPending()
        val token = currentToken
        setAuth(null)
        store.clear()
        if (token != null) {
            try {
                api.revokeSession(token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun publish(provider: String, response: BackendAuthResponse): BackendAuth {
        val auth = BackendAuth(response.token, response.userId, provider)
        if ((claimGate?.needsDecision(auth) ?: 0) > 0) {
            discardPending()
            // Switching providers/accounts cannot leave the previous login active
            // behind a dialog promising to stay signed out.
            setAuth(null)
            store.clear()
            _pendingAuth.value = auth
            return auth
        }
        store.write(auth)
        setAuth(auth)
        return auth
    }
}
