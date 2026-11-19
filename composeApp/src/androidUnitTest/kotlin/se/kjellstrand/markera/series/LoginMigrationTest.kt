package se.kjellstrand.markera.series

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LoginMigrationTest {

    private val token = stringPreferencesKey("token")
    private val userId = longPreferencesKey("user_id")
    private val provider = stringPreferencesKey("provider")
    private val theme = stringPreferencesKey("theme")

    @Test
    fun loginMovesOutOfTheSettingsFile() = runBlocking {
        val dir = Files.createTempDirectory("login").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            // In memory: a file store's second write fails on Windows (rename over an existing file).
            val legacy = MemoryPrefs()
            legacy.edit {
                it[token] = "t"
                it[userId] = 7
                it[provider] = "google"
                it[theme] = "dark"
            }
            val auth = PreferenceDataStoreFactory.create(
                migrations = listOf(MoveLoginOut(legacy)),
                scope = scope,
            ) { dir.resolve("markera_auth.preferences_pb") }

            val moved = auth.data.first()
            assertEquals("t", moved[token])
            assertEquals(7L, moved[userId])
            assertEquals("google", moved[provider])
            val left = legacy.data.first()
            assertNull(left[token])
            assertNull(left[userId])
            assertNull(left[provider])
            assertEquals("dark", left[theme])
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun historyViewStateIsDroppedFromTheBackedUpSettingsFile() = runBlocking {
        val filter = stringPreferencesKey("history_filter")
        val openDays = stringPreferencesKey("history_open_days")
        val dir = Files.createTempDirectory("view").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val legacy = MemoryPrefs()
            legacy.edit {
                it[filter] = "calibers=22lr;tags=;preset=WEEK"
                it[openDays] = "2026-11-19"
                it[theme] = "dark"
            }
            val view = PreferenceDataStoreFactory.create(
                migrations = listOf(DropViewStateFromSettings(legacy)),
                scope = scope,
            ) { dir.resolve("markera_view.preferences_pb") }

            // A restored backup's filter is not carried over: History opens unfiltered.
            val fresh = view.data.first()
            assertNull(fresh[filter])
            assertNull(fresh[openDays])
            val left = legacy.data.first()
            assertNull(left[filter])
            assertNull(left[openDays])
            assertEquals("dark", left[theme])
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

private class MemoryPrefs : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
        transform(data.value).also { data.value = it }
}
