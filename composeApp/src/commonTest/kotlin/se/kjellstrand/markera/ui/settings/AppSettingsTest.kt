package se.kjellstrand.markera.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsTest {

    /** Map-backed like the platform stores; [clear] drops only the login, as they do. */
    private class MapStore : BackendTokenStore {
        val values = mutableMapOf<String, Any>()
        override suspend fun read(): BackendAuth? = values["auth"] as BackendAuth?
        override suspend fun write(auth: BackendAuth) { values["auth"] = auth }
        override suspend fun clear() { values.remove("auth") }
        override suspend fun readShootingHand(): String? = values["hand"] as String?
        override suspend fun writeShootingHand(value: String) { values["hand"] = value }
        override suspend fun readInsightsEnabled(): Boolean? = values["insights"] as Boolean?
        override suspend fun writeInsightsEnabled(value: Boolean) { values["insights"] = value }
    }

    // Unconfined: the store never suspends, so every launch (load and write-through) completes inline.
    private fun loaded(store: BackendTokenStore): AppSettings =
        AppSettings(store, CoroutineScope(Dispatchers.Unconfined)).also { assertTrue(it.loaded.value) }

    @Test
    fun emptyStoreReadsTheDefaults() {
        val settings = loaded(MapStore())
        assertEquals(ShootingHand.RIGHT, settings.shootingHand.value)
        assertTrue(settings.insightsEnabled.value)
    }

    @Test
    fun choicesSurviveARestart() {
        val store = MapStore()
        val first = loaded(store)
        first.setShootingHand(ShootingHand.LEFT)
        first.setInsightsEnabled(false)
        assertEquals(ShootingHand.LEFT, first.shootingHand.value)
        assertFalse(first.insightsEnabled.value)

        val second = loaded(store)
        assertEquals(ShootingHand.LEFT, second.shootingHand.value)
        assertFalse(second.insightsEnabled.value)
    }

    @Test
    fun unknownStoredHandReadsRight() {
        val store = MapStore().apply { values["hand"] = "BOTH" }
        assertEquals(ShootingHand.RIGHT, loaded(store).shootingHand.value)
    }

    @Test
    fun choicesSurviveSignOut() {
        val store = MapStore()
        loaded(store).apply {
            setShootingHand(ShootingHand.LEFT)
            setInsightsEnabled(false)
        }
        runBlocking { store.clear() }

        val after = loaded(store)
        assertEquals(ShootingHand.LEFT, after.shootingHand.value)
        assertFalse(after.insightsEnabled.value)
    }
}
