package se.kjellstrand.markera.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.BackendTokenStore
import se.kjellstrand.markera.series.stats.SightSetup
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
        override suspend fun readSightSetups(): String? = values["sights"] as String?
        override suspend fun writeSightSetups(value: String) { values["sights"] = value }
        override suspend fun readDismissedInsights(): String? = values["dismissed"] as String?
        override suspend fun writeDismissedInsights(value: String) { values["dismissed"] = value }
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

    @Test
    fun sightSetupsArePerCaliberAndSurviveARestartAndSignOut() {
        val store = MapStore()
        loaded(store).apply {
            assertTrue(sightSetups.value.isEmpty())
            setSightClicks("9mm", 25.0, 6.0)
            markSightsAdjusted("9mm", 1_700_000_000_000L)
            setSightClicks(".22 lr", 50.0, 2.5)
        }
        runBlocking { store.clear() }
        val after = loaded(store)
        assertEquals(SightSetup(25.0, 6.0, 1_700_000_000_000L), after.sightSetups.value["9mm"])
        assertEquals(SightSetup(50.0, 2.5, null), after.sightSetups.value[".22 lr"])

        // Removing the click value keeps the adjustment; undoing that too forgets the caliber.
        after.setSightClicks("9mm", 25.0, null)
        assertEquals(SightSetup(25.0, null, 1_700_000_000_000L), loaded(store).sightSetups.value["9mm"])
        after.undoSightsAdjusted("9mm")
        assertEquals(setOf(".22 lr"), loaded(store).sightSetups.value.keys)
    }

    @Test
    fun undoingASecondAdjustmentGoesBackToTheFirst() {
        val store = MapStore()
        loaded(store).apply {
            markSightsAdjusted("9mm", 1_000L)
            markSightsAdjusted("9mm", 2_000L)
            undoSightsAdjusted("9mm")
        }
        assertEquals(1_000L, loaded(store).sightSetups.value["9mm"]?.adjustedAtMs)
        loaded(store).undoSightsAdjusted("9mm")
        assertTrue(loaded(store).sightSetups.value.isEmpty())
    }

    @Test
    fun aDismissedInsightSurvivesARestartAndSignOut() {
        val store = MapStore()
        loaded(store).apply {
            assertTrue(dismissedInsights.value.isEmpty())
            dismissInsight("offset|22lr|1")
            dismissInsight("trend|9mm|2")
            dismissInsight("offset|22lr|1")
            assertEquals(listOf("trend|9mm|2", "offset|22lr|1"), dismissedInsights.value)
        }
        runBlocking { store.clear() }
        assertEquals(listOf("trend|9mm|2", "offset|22lr|1"), loaded(store).dismissedInsights.value)
    }
}
