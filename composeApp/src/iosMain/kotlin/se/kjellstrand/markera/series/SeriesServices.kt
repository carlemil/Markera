package se.kjellstrand.markera.series

import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSUserDefaults
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

/**
 * Wires the Markera series backend stack (HTTP client → API → session) on iOS.
 * Mirrors the Android `SeriesServices`; the host app creates one and keeps it.
 */
class SeriesServices(baseUrl: String = "https://markera.duckdns.org") {

    val session: BackendSessionRepository
    val api: SeriesApi
    val store = UserDefaultsBackendTokenStore()

    init {
        val client = createWebshooterHttpClient(Darwin.create())
        lateinit var repo: BackendSessionRepository
        api = SeriesApi(client, baseUrl, tokenProvider = { repo.currentToken })
        repo = BackendSessionRepository(api, store)
        session = repo
    }
}

/** Persists the Markera backend login in `NSUserDefaults`. */
class UserDefaultsBackendTokenStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : BackendTokenStore {

    private object Keys {
        const val TOKEN = "markera.backend.token"
        const val USER_ID = "markera.backend.userId"
        const val PROVIDER = "markera.backend.provider"
        const val CALIBER = "markera.backend.caliber"
    }

    /** The chosen caliber shares this store but survives [clear] (sign-out). */
    fun readCaliber(): Caliber =
        Caliber.fromLabel(defaults.stringForKey(Keys.CALIBER) ?: "")

    fun writeCaliber(caliber: Caliber) = defaults.setObject(caliber.label, Keys.CALIBER)

    override suspend fun read(): BackendAuth? {
        return BackendAuth(
            token = defaults.stringForKey(Keys.TOKEN) ?: return null,
            // Stored as a string: NSUserDefaults integers can't express "absent".
            userId = defaults.stringForKey(Keys.USER_ID)?.toLongOrNull() ?: return null,
            provider = defaults.stringForKey(Keys.PROVIDER) ?: return null,
        )
    }

    override suspend fun write(auth: BackendAuth) {
        defaults.setObject(auth.token, Keys.TOKEN)
        defaults.setObject(auth.userId.toString(), Keys.USER_ID)
        defaults.setObject(auth.provider, Keys.PROVIDER)
    }

    override suspend fun clear() {
        // Only the login — the caliber is a device preference, not part of it.
        defaults.removeObjectForKey(Keys.TOKEN)
        defaults.removeObjectForKey(Keys.USER_ID)
        defaults.removeObjectForKey(Keys.PROVIDER)
    }
}
