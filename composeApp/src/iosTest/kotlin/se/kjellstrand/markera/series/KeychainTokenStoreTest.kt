package se.kjellstrand.markera.series

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/** The Keychain token's migration and first-run rules, against a fake vault (no Keychain in the test binary). */
class KeychainTokenStoreTest {

    private class FakeVault(var token: String? = null) : TokenVault {
        var readStatus = 0
        var writeStatus = 0
        var deleteStatus = 0
        var writes = 0
        var deletes = 0

        override fun read(): VaultRead = when {
            readStatus != 0 -> VaultRead.Failed(readStatus)
            token == null -> VaultRead.NotFound
            else -> VaultRead.Found(token!!)
        }

        override fun write(token: String): Int {
            writes++
            if (writeStatus == 0) this.token = token
            return writeStatus
        }

        override fun delete(): Int {
            deletes++
            if (deleteStatus == 0) token = null
            return deleteStatus
        }
    }

    private val suite = "markera.test.${NSUUID().UUIDString}"
    private val defaults = NSUserDefaults(suiteName = suite)
    private val vault = FakeVault()
    private val store = UserDefaultsBackendTokenStore(defaults, vault)

    @AfterTest
    fun dropSuite() {
        defaults.removePersistentDomainForName(suite)
    }

    private fun legacyLogin(token: String = "old-token") {
        defaults.setObject(token, TOKEN)
        defaults.setObject("7", "markera.backend.userId")
        defaults.setObject("dev", "markera.backend.provider")
    }

    private fun markInstalled() = defaults.setBool(true, INSTALLED)

    @Test
    fun anUpgradeMovesTheTokenToTheKeychainAndDropsTheDefaultsCopy() = runBlocking {
        legacyLogin()

        assertEquals(BackendAuth("old-token", 7, "dev"), store.read())
        assertEquals("old-token", vault.token)
        assertNull(defaults.stringForKey(TOKEN))
        assertTrue(defaults.boolForKey(INSTALLED))
        // Read again: from the Keychain, no second write.
        assertEquals(BackendAuth("old-token", 7, "dev"), store.read())
        assertEquals(1, vault.writes)
    }

    @Test
    fun aFailedMigrationKeepsTheDefaultsCopyAndStaysSignedIn() = runBlocking {
        legacyLogin()
        vault.writeStatus = -25299

        assertEquals(BackendAuth("old-token", 7, "dev"), store.read())
        assertEquals("old-token", defaults.stringForKey(TOKEN))

        vault.writeStatus = 0
        assertEquals(BackendAuth("old-token", 7, "dev"), store.read())
        assertEquals("old-token", vault.token)
        assertNull(defaults.stringForKey(TOKEN))
    }

    @Test
    fun aFreshInstallDeletesALeftoverKeychainToken() = runBlocking {
        vault.token = "previous-install"

        assertNull(store.read())
        assertNull(vault.token)
        assertTrue(defaults.boolForKey(INSTALLED))
    }

    @Test
    fun theLeftoverCheckRunsOnlyOnce() = runBlocking {
        store.write(BackendAuth("t1", 3, "apple"))
        val deletes = vault.deletes

        assertEquals(BackendAuth("t1", 3, "apple"), store.read())
        assertEquals(BackendAuth("t1", 3, "apple"), store.read())
        assertEquals("t1", vault.token)
        assertEquals(deletes, vault.deletes)
    }

    @Test
    fun aLeftoverThatCannotBeDeletedIsNotTrustedAndIsCheckedAgain() = runBlocking {
        vault.token = "previous-install"
        defaults.setObject("7", "markera.backend.userId")
        defaults.setObject("dev", "markera.backend.provider")
        vault.deleteStatus = -25308

        assertNull(store.read())
        assertFalse(defaults.boolForKey(INSTALLED))

        vault.deleteStatus = 0
        assertNull(store.read())
        assertNull(vault.token)
        assertTrue(defaults.boolForKey(INSTALLED))
    }

    @Test
    fun aKeychainReadErrorIsNotTreatedAsNoToken() = runBlocking {
        markInstalled()
        vault.token = "kept"
        defaults.setObject("7", "markera.backend.userId")
        defaults.setObject("dev", "markera.backend.provider")
        vault.readStatus = -25308

        assertNull(store.read())
        assertEquals("kept", vault.token)
        assertEquals(0, vault.writes)
        assertEquals("7", defaults.stringForKey("markera.backend.userId"))

        vault.readStatus = 0
        assertEquals(BackendAuth("kept", 7, "dev"), store.read())
    }

    @Test
    fun writeKeepsTheTokenOutOfDefaults() = runBlocking {
        legacyLogin("stale")
        markInstalled()

        store.write(BackendAuth("fresh", 9, "google"))

        assertEquals("fresh", vault.token)
        assertNull(defaults.stringForKey(TOKEN))
        assertEquals(BackendAuth("fresh", 9, "google"), store.read())
    }

    @Test
    fun aFailedWriteDoesNotThrowAndLeavesNoLoginBehind() = runBlocking {
        markInstalled()
        store.write(BackendAuth("t1", 3, "apple"))
        vault.writeStatus = -34018

        store.write(BackendAuth("t2", 4, "dev"))

        assertNull(store.read())
        assertNull(defaults.stringForKey("markera.backend.userId"))
        assertNull(defaults.stringForKey(TOKEN))
    }

    @Test
    fun clearDeletesTheKeychainItemAndAnyDefaultsCopy() = runBlocking {
        markInstalled()
        store.write(BackendAuth("t1", 3, "apple"))
        defaults.setObject("leftover", TOKEN)

        store.clear()

        assertNull(vault.token)
        assertNull(defaults.stringForKey(TOKEN))
        assertNull(store.read())
    }

    @Test
    fun clearStillClearsDefaultsWhenTheKeychainDeleteFails() = runBlocking {
        markInstalled()
        store.write(BackendAuth("t1", 3, "apple"))
        vault.deleteStatus = -25308

        store.clear()

        assertNull(defaults.stringForKey("markera.backend.userId"))
        assertNull(store.read())
    }

    private companion object {
        const val TOKEN = "markera.backend.token"
        const val INSTALLED = "markera.backend.keychainInstalled"
    }
}
