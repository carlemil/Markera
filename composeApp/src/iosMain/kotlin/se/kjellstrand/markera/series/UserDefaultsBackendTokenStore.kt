package se.kjellstrand.markera.series

import platform.Foundation.NSUserDefaults

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

    override suspend fun readCaliber(): Caliber =
        Caliber.fromLabel(defaults.stringForKey(Keys.CALIBER) ?: "")

    override suspend fun writeCaliber(caliber: Caliber) =
        defaults.setObject(caliber.label, Keys.CALIBER)

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
