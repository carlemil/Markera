package se.kjellstrand.markera.series

import platform.Foundation.NSUserDefaults
import se.kjellstrand.markera.diag.ErrorLog

/**
 * Persists the Markera backend login and the device preferences. The session token is in
 * the Keychain ([vault], issue #21); everything else, user id and provider included, is in
 * `NSUserDefaults`.
 */
class UserDefaultsBackendTokenStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val vault: TokenVault = KeychainTokenVault(),
) : BackendTokenStore {

    private object Keys {
        /** Only builds before issue #21 wrote it; [read] moves it to the Keychain. */
        const val TOKEN = "markera.backend.token"
        /** Set once this install owns the Keychain item; wiped with the app on uninstall. */
        const val KEYCHAIN_INSTALLED = "markera.backend.keychainInstalled"
        const val USER_ID = "markera.backend.userId"
        const val PROVIDER = "markera.backend.provider"
        const val CALIBER = "markera.backend.caliber"
        const val CUSTOM_CALIBERS = "markera.backend.customCalibers"
        const val HISTORY_FILTER = "markera.backend.historyFilter"
        const val OPEN_DAYS = "markera.backend.historyOpenDays"
        const val TAG = "markera.backend.tag"
        const val THEME = "markera.settings.theme"
        const val LANGUAGE = "markera.settings.language"
        const val GUIDE_COLOR = "markera.settings.guideColor"
        const val SHOOTING_HAND = "markera.settings.shootingHand"
        const val INSIGHTS = "markera.settings.insightsEnabled"
        const val SIGHT_SETUPS = "markera.settings.sightSetups"
        const val DISMISSED_INSIGHTS = "markera.settings.dismissedInsights"
    }

    // Stored as a string: NSUserDefaults integers can't express "absent".
    override suspend fun readGuideColor(): Long? = defaults.stringForKey(Keys.GUIDE_COLOR)?.toLongOrNull()

    override suspend fun writeGuideColor(argb: Long) = defaults.setObject(argb.toString(), Keys.GUIDE_COLOR)

    override suspend fun readShootingHand(): String? = defaults.stringForKey(Keys.SHOOTING_HAND)

    override suspend fun writeShootingHand(value: String) = defaults.setObject(value, Keys.SHOOTING_HAND)

    // boolForKey reads "never set" as false; the object check keeps it null (= the default, on).
    override suspend fun readInsightsEnabled(): Boolean? =
        if (defaults.objectForKey(Keys.INSIGHTS) == null) null else defaults.boolForKey(Keys.INSIGHTS)

    override suspend fun writeInsightsEnabled(value: Boolean) = defaults.setBool(value, Keys.INSIGHTS)

    override suspend fun readSightSetups(): String? = defaults.stringForKey(Keys.SIGHT_SETUPS)

    override suspend fun writeSightSetups(value: String) = defaults.setObject(value, Keys.SIGHT_SETUPS)

    override suspend fun readDismissedInsights(): String? = defaults.stringForKey(Keys.DISMISSED_INSIGHTS)

    override suspend fun writeDismissedInsights(value: String) = defaults.setObject(value, Keys.DISMISSED_INSIGHTS)

    // Stored as "" rather than removed, so "cleared the tag" and "never set one" read alike.
    override suspend fun readTag(): String? = normalizeTag(defaults.stringForKey(Keys.TAG))

    override suspend fun writeTag(value: String?) = defaults.setObject(value ?: "", Keys.TAG)

    override suspend fun readTheme(): String? = defaults.stringForKey(Keys.THEME)

    override suspend fun writeTheme(value: String) = defaults.setObject(value, Keys.THEME)

    override suspend fun readLanguage(): String? = defaults.stringForKey(Keys.LANGUAGE)?.ifEmpty { null }

    override suspend fun writeLanguage(value: String?) = defaults.setObject(value ?: "", Keys.LANGUAGE)

    override suspend fun readCaliber(): Caliber =
        Caliber.fromStored(defaults.stringForKey(Keys.CALIBER))

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

    /**
     * A Keychain item outlives an uninstall, NSUserDefaults does not. Without this
     * install's flag, whatever the Keychain holds belongs to an earlier install: delete
     * it, and set the flag only once that delete went through (else try again next time).
     * An upgrade from a build that kept the token in defaults has an empty Keychain, so
     * this deletes nothing there and the migration in [read] still runs.
     * False = the Keychain may still hold an earlier install's token, so don't trust it.
     */
    private fun ensureInstalled(): Boolean {
        if (defaults.boolForKey(Keys.KEYCHAIN_INSTALLED)) return true
        val status = vault.delete()
        if (status == 0) {
            defaults.setBool(true, Keys.KEYCHAIN_INSTALLED)
            return true
        }
        ErrorLog.report("token-keychain", KeychainError("delete leftover", status))
        return false
    }

    override suspend fun read(): BackendAuth? {
        val token = (if (!ensureInstalled()) defaults.stringForKey(Keys.TOKEN) else readVault()) ?: return null
        return BackendAuth(
            token = token,
            // Stored as a string: NSUserDefaults integers can't express "absent".
            userId = defaults.stringForKey(Keys.USER_ID)?.toLongOrNull() ?: return null,
            provider = defaults.stringForKey(Keys.PROVIDER) ?: return null,
        )
    }

    private fun readVault(): String? =
        when (val stored = vault.read()) {
            is VaultRead.Found -> stored.token.also {
                // A copy left by an earlier migration whose cleanup never ran.
                defaults.removeObjectForKey(Keys.TOKEN)
            }
            VaultRead.NotFound -> migrateLegacyToken()
            is VaultRead.Failed -> {
                // Unknown, e.g. still locked: only a pre-Keychain copy, if any; nothing deleted.
                ErrorLog.report("token-keychain", KeychainError("read", stored.status))
                defaults.stringForKey(Keys.TOKEN)
            }
        }

    /**
     * Builds before issue #21 kept the token in defaults, which ride along in iCloud/iTunes
     * backups. Moved once: written to the Keychain, and removed from defaults only after
     * that write succeeded. A failed write keeps using (and keeping) the defaults copy.
     */
    private fun migrateLegacyToken(): String? {
        val legacy = defaults.stringForKey(Keys.TOKEN) ?: return null
        val status = vault.write(legacy)
        if (status == 0) {
            defaults.removeObjectForKey(Keys.TOKEN)
            ErrorLog.breadcrumb("signin", "token moved to the keychain")
        } else {
            ErrorLog.report("token-keychain", KeychainError("migrate", status))
        }
        return legacy
    }

    /**
     * The token goes to the Keychain only; user id and provider stay in defaults (not
     * secret, useless without the token, and wiped by an uninstall, so a leftover Keychain
     * token can never pair with them). A failed Keychain write leaves this session in memory
     * only: the next launch is signed out rather than holding a stale or backed-up token.
     */
    override suspend fun write(auth: BackendAuth) {
        ensureInstalled()
        // A new login supersedes any pre-Keychain copy.
        defaults.removeObjectForKey(Keys.TOKEN)
        val status = vault.write(auth.token)
        if (status == 0) {
            // The write replaced any leftover, so the item is this install's now.
            defaults.setBool(true, Keys.KEYCHAIN_INSTALLED)
            defaults.setObject(auth.userId.toString(), Keys.USER_ID)
            defaults.setObject(auth.provider, Keys.PROVIDER)
        } else {
            ErrorLog.report("token-keychain", KeychainError("write", status))
            vault.delete()
            defaults.removeObjectForKey(Keys.USER_ID)
            defaults.removeObjectForKey(Keys.PROVIDER)
        }
    }

    override suspend fun clear() {
        // The login plus what names this user's tags; the caliber is a device preference.
        val status = vault.delete()
        if (status != 0) ErrorLog.report("token-keychain", KeychainError("delete", status))
        defaults.removeObjectForKey(Keys.TOKEN)
        defaults.removeObjectForKey(Keys.USER_ID)
        defaults.removeObjectForKey(Keys.PROVIDER)
        defaults.removeObjectForKey(Keys.TAG)
        defaults.removeObjectForKey(Keys.HISTORY_FILTER)
    }
}

/** A Keychain failure for [ErrorLog]: the operation and its OSStatus, never the token. */
class KeychainError(operation: String, status: Int) : Exception("keychain $operation failed: OSStatus $status")
