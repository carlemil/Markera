package se.kjellstrand.markera.webshooter.auth

/** The persisted webshooter login. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    /** DB user id, decoded from the JWT `sub` claim; drives the isSelf rule. */
    val userId: Long? = null,
    val isAdmin: Boolean = false,
    val userName: String? = null,
)

/** Platform persistence for [AuthSession] (DataStore on Android, stub on iOS). */
interface TokenStore {
    suspend fun read(): AuthSession?
    suspend fun write(session: AuthSession)
    suspend fun clear()
}

/** In-memory token store for tests. */
class InMemoryTokenStore(private var session: AuthSession? = null) : TokenStore {
    override suspend fun read(): AuthSession? = session
    override suspend fun write(session: AuthSession) { this.session = session }
    override suspend fun clear() { session = null }
}
