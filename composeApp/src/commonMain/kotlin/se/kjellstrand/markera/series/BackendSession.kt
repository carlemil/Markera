package se.kjellstrand.markera.series

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The persisted Markera backend login. [provider] is `google`, `apple` or `dev`. */
data class BackendAuth(val token: String, val userId: Long, val provider: String)

/** Platform persistence for [BackendAuth] (DataStore on Android, stub on iOS). */
interface BackendTokenStore {
    suspend fun read(): BackendAuth?
    suspend fun write(auth: BackendAuth)
    suspend fun clear()
}

/** In-memory store for tests. */
class InMemoryBackendTokenStore(private var auth: BackendAuth? = null) : BackendTokenStore {
    override suspend fun read(): BackendAuth? = auth
    override suspend fun write(auth: BackendAuth) { this.auth = auth }
    override suspend fun clear() { auth = null }
}

/**
 * Owns the backend login state: restore on app start, sign in via a provider
 * ID token (or the dev endpoint), sign out. The backend session token is
 * opaque and long-lived, so there is no refresh — a 401 means sign in again.
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

    suspend fun signInDev(subject: String): BackendAuth =
        publish("dev", api.authDev(subject))

    suspend fun signOut() {
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
