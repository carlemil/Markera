package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import se.kjellstrand.markera.webshooter.api.createWebshooterHttpClient

class SeriesEditsTest {

    private val bodies = mutableListOf<String>()
    private var status = HttpStatusCode.NoContent

    private val a = HoleDto(1.0, 1.0, 10, false, 5.0)
    private val b = HoleDto(2.0, 2.0, 8, false, 60.0)
    private val base = SeriesDto(7, "2026-09-19T10:00:00Z", "22lr", listOf(a, b))

    private val repository = run {
        val api = SeriesApi(
            createWebshooterHttpClient(
                MockEngine { request ->
                    bodies += (request.body as TextContent).text
                    respond("", status)
                },
            ),
            "http://host:8090",
        ) { "tok" }
        SeriesRepository(
            api,
            testSeriesDb(),
            FakeImageCache(),
            BackendSessionRepository(api, InMemoryBackendTokenStore(BackendAuth("tok", 1, "dev"))),
        )
    }
    private val edits = SeriesEdits(repository, base)
    private val original = SeriesEdit(base.holes, base.caliber, base.tag)

    @Test
    fun saveSendsTheFullStateAndReturnsThePreviousOne() = runBlocking {
        val next = SeriesEdit(listOf(a, b.copy(ring = 9)), "9mm", "träning")

        assertEquals(original, edits.save(next))

        assertEquals(next, edits.saved)
        val body = bodies.single()
        assertTrue(""""caliber":"9mm"""" in body, body)
        assertTrue(""""tag":"träning"""" in body, body)
        assertTrue(""""ring":9""" in body, body)
    }

    @Test
    fun aRemovedHoleIsSentFlaggedDeletedInItsOwnSaveOnly() = runBlocking {
        edits.save(SeriesEdit(listOf(a), "22lr", null), removedHole = b.copy(deleted = true))
        edits.save(SeriesEdit(listOf(a), "9mm", null))

        assertTrue(""""deleted":true""" in bodies[0], bodies[0])
        assertFalse(""""deleted":true""" in bodies[1], bodies[1])
        assertEquals(listOf(a), repository.series.value.single().holes)
    }

    @Test
    fun aFailedSaveReturnsNullAndKeepsTheSavedState() = runBlocking {
        status = HttpStatusCode.InternalServerError

        assertNull(edits.save(SeriesEdit(listOf(a), "9mm", null)))

        assertEquals(original, edits.saved)
    }

    @Test
    fun undoSavesTheEarlierStateBack() = runBlocking {
        val before = edits.save(SeriesEdit(listOf(a), "9mm", "tävling"))!!

        edits.save(before)

        assertEquals(original, edits.saved)
        assertEquals(2, bodies.size)
        val cached = repository.series.value.single()
        assertEquals(base.holes, cached.holes)
        assertEquals("22lr", cached.caliber)
        assertNull(cached.tag)
    }
}
