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
        const val CUSTOM_CALIBERS = "markera.backend.customCalibers"
        const val HISTORY_FILTER = "markera.backend.historyFilter"
        const val OPEN_DAYS = "markera.backend.historyOpenDays"
        const val TAG = "markera.backend.tag"
        const val THEME = "markera.settings.theme"
        const val LANGUAGE = "markera.settings.language"
    }

    // Stored as "" rather than removed, so "cleared the tag" and "never set one" read alike.
    override suspend fun readTag(): String? = normalizeTag(defaults.stringForKey(Keys.TAG))

    override suspend fun writeTag(value: String?) = defaults.setObject(value ?: "", Keys.TAG)

    override suspend fun readTheme(): String? = defaults.stringForKey(Keys.THEME)

    override suspend fun writeTheme(value: String) = defaults.setObject(value, Keys.THEME)

    override suspend fun readLanguage(): String? = defaults.stringForKey(Keys.LANGUAGE)?.ifEmpty { null }

    override suspend fun writeLanguage(value: String?) = defaults.setObject(value ?: "", Keys.LANGUAGE)

    override suspend fun readCaliber(): Caliber =
        Caliber.fromLabel(defaults.stringForKey(Keys.CALIBER) ?: "")

    override suspend fun writeCaliber(caliber: Caliber) =
        defaults.setObject(caliber.label, Keys.CALIBER)

    override suspend fun readCustomCalibers(): List<Caliber> =
        decodeCustomCalibers(defaults.stringForKey(Keys.CUSTOM_CALIBERS))

    override suspend fun writeCustomCalibers(calibers: List<Caliber>) =
        defaults.setObject(calibers.encodeCustomCalibers(), Keys.CUSTOM_CALIBERS)

    override suspend fun readHistoryFilter(): String? = defaults.stringForKey(Keys.HISTORY_FILTER)

    override suspend fun writeHistoryFilter(value: String) =
        defaults.setObject(value, Keys.HISTORY_FILTER)

    override suspend fun readOpenDays(): String? = defaults.stringForKey(Keys.OPEN_DAYS)

    override suspend fun writeOpenDays(value: String) =
        defaults.setObject(value, Keys.OPEN_DAYS)

    override suspend fun read(): BackendAuth? {
        return BackendAuth(
            token = defaults.stringForKey(Keys.TOKEN) ?: return null,
            // Stored as a string: NSUserDefaults integers can't express "absent".
            userId = defaults.stringForKey(Keys.USER_ID)?.toLongOrNull() ?: return null,
            provider = defaults.stringForKey(Keys.PROVIDER) ?: return null,
        )
    }

    // NSUserDefaults rides along in iCloud/iTunes backups, unencrypted, and the bearer
    // token with it. Moving it to the Keychain is deferred on the user's call (2026-09-19).
    override suspend fun write(auth: BackendAuth) {
        defaults.setObject(auth.token, Keys.TOKEN)
        defaults.setObject(auth.userId.toString(), Keys.USER_ID)
        defaults.setObject(auth.provider, Keys.PROVIDER)
    }

    override suspend fun clear() {
        // The login plus what names this user's tags; the caliber is a device preference.
        defaults.removeObjectForKey(Keys.TOKEN)
        defaults.removeObjectForKey(Keys.USER_ID)
        defaults.removeObjectForKey(Keys.PROVIDER)
        defaults.removeObjectForKey(Keys.TAG)
        defaults.removeObjectForKey(Keys.HISTORY_FILTER)
    }
}
