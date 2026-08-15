package se.kjellstrand.markera.webshooter.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.kjellstrand.markera.webshooter.api.WebshooterApi
import se.kjellstrand.markera.webshooter.api.WebshooterApiException

/**
 * Owns the webshooter login state: restore from [TokenStore] on app start,
 * login/logout, and a single-flight token refresh used by [withAuth] to
 * transparently retry a call that failed with 401.
 *
 * The [WebshooterApi] must be constructed with `tokenProvider = { currentToken }`
 * of this instance so every request picks up the latest token.
 */
class SessionRepository(
    private val api: WebshooterApi,
    private val store: TokenStore,
) {
    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    val currentToken: String? get() = _session.value?.accessToken

    private val refreshMutex = Mutex()

    suspend fun restore(): AuthSession? =
        store.read().also { _session.value = it }

    suspend fun login(email: String, password: String): AuthSession {
        val tokens = api.login(email, password)
        // Publish the token before the user lookup so currentUser() is authorized.
        _session.value = AuthSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userId = jwtSubject(tokens.accessToken),
        )
        val user = try {
            api.currentUser().user
        } catch (_: Exception) {
            null // Non-fatal: only isSelf/admin gating degrades.
        }
        val session = AuthSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            userId = jwtSubject(tokens.accessToken),
            isAdmin = user?.isAdmin ?: false,
            userName = user?.fullname,
        )
        _session.value = session
        store.write(session)
        return session
    }

    suspend fun logout() {
        _session.value = null
        store.clear()
    }

    /**
     * Single-flight refresh: concurrent 401s wait on the same attempt. Returns
     * true when a new token is in place. On failure the session is cleared
     * (caller should route to login).
     */
    suspend fun refresh(): Boolean {
        val before = _session.value?.accessToken ?: return false
        refreshMutex.withLock {
            val current = _session.value ?: return false
            if (current.accessToken != before) return true // Someone else refreshed.
            return try {
                val tokens = api.refresh()
                val updated = current.copy(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken ?: current.refreshToken,
                    userId = jwtSubject(tokens.accessToken) ?: current.userId,
                )
                _session.value = updated
                store.write(updated)
                true
            } catch (_: Exception) {
                _session.value = null
                store.clear()
                false
            }
        }
    }

    /** Runs [block]; on 401, refreshes once and retries. */
    suspend fun <T> withAuth(block: suspend () -> T): T = try {
        block()
    } catch (e: WebshooterApiException) {
        if (e.isUnauthorized && refresh()) block() else throw e
    }
}
