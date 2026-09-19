package se.kjellstrand.markera.series

/** In-memory store for tests. */
class InMemoryBackendTokenStore(private var auth: BackendAuth? = null) : BackendTokenStore {
    override suspend fun read(): BackendAuth? = auth
    override suspend fun write(auth: BackendAuth) { this.auth = auth }
    override suspend fun clear() { auth = null }
}
