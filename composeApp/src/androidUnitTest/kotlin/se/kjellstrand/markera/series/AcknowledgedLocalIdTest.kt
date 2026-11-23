package se.kjellstrand.markera.series

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * #3 and #5: a Serie page opened on a local series keeps its negative id after the server
 * acknowledged it (the row then shows under its server id). Edits and Delete under that old
 * id must reach the server series, not the local row nobody sends again (or that is gone).
 */
class AcknowledgedLocalIdTest {
    private val jpeg = byteArrayOf(-1, -40, 1, 3, -1, -39)
    private val request = SeriesRequest("2026-11-20T10:00:00Z", "22lr",
        listOf(HoleDto(10.0, 20.0, 9, false, 30.0)), tag = "training", clientId = "client-a")
    private val edited = request.copy(caliber = "9mm", tag = "competition",
        holes = listOf(HoleDto(10.0, 20.0, 10, false, 30.0)))

    private class Fixture {
        var imageCode = 204
        val puts = mutableListOf<Pair<String, SeriesRequest>>()
        val deletes = mutableListOf<String>()
        /** What the server holds, so a refresh's full load lists it back. */
        val server = linkedMapOf<Long, SeriesDto>()
        private fun body(req: io.ktor.client.request.HttpRequestData) =
            seriesJson.decodeFromString<SeriesRequest>((req.body as TextContent).text)
        val api = SeriesApi(createSeriesHttpClient(MockEngine { req ->
            val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Post && path == "/series" -> {
                    val b = body(req)
                    server[41L] = SeriesDto(41L, b.timestamp, b.caliber, b.holes, tag = b.tag)
                    respond("""{"id":41}""", HttpStatusCode.Created, json)
                }
                req.method == HttpMethod.Post && path.endsWith("/image") -> {
                    if (imageCode < 300) server[41L] = server.getValue(41L).copy(hasImage = true, imageWidth = 4, imageHeight = 4)
                    respond("", HttpStatusCode.fromValue(imageCode))
                }
                req.method == HttpMethod.Put -> {
                    val b = body(req)
                    puts += path to b
                    val id = path.substringAfterLast('/').toLong()
                    server[id] = server.getValue(id).copy(caliber = b.caliber, holes = b.holes, tag = b.tag)
                    respond("", HttpStatusCode.NoContent)
                }
                req.method == HttpMethod.Delete -> {
                    deletes += path
                    server.remove(path.substringAfterLast('/').toLong())
                    respond("", HttpStatusCode.NoContent)
                }
                else -> respond(seriesJson.encodeToString(server.values.toList()), HttpStatusCode.OK, json)
            }
        }), "http://host") { "tok" }
        val db = testSeriesDb()
        val localImages = FakeImageCache()
        val repository = SeriesRepository(api, db, FakeImageCache(), testSession(api, 17L), localImages)
    }

    /** Saves locally, then lets the upload run as a refresh would while the page is open. */
    private suspend fun Fixture.savedThenAcknowledged(): Long {
        val localId = repository.keepLocal(request, EncodedImage(jpeg, 4, 4), owner = 17L)
        runCatching { repository.send(localId) }
        return localId
    }

    @Test fun anEditUnderTheLocalIdAfterTheUploadFinishedIsPutToTheServerId() = runBlocking {
        val f = Fixture()
        val localId = f.savedThenAcknowledged()
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty(), "upload finished")
        assertEquals(mapOf(localId to 41L), f.repository.acknowledgedIds.value, "what the Serie page follows")

        f.repository.update(-localId, edited)

        assertEquals(listOf("/series/41"), f.puts.map { it.first })
        assertEquals("9mm", f.puts.single().second.caliber)
        val shown = f.repository.series.value.single { it.id == 41L }
        assertEquals("9mm", shown.caliber)
        assertEquals("competition", shown.tag)
        assertEquals(10, shown.holes.single().ring)
    }

    @Test fun anEditUnderTheLocalIdWhileOnlyThePhotoIsPendingIsPutToTheServerId() = runBlocking {
        val f = Fixture().also { it.imageCode = 503 }
        val localId = f.savedThenAcknowledged()
        assertEquals(41L, f.db.seriesQueries.localById(localId).executeAsOne().server_id, "only the photo is pending")

        f.repository.update(-localId, edited)

        assertEquals(listOf("/series/41"), f.puts.map { it.first })
        assertEquals("9mm", f.repository.series.value.single { it.id == 41L }.caliber)
        // The pending photo still goes up afterwards, and the edit is not undone by it.
        f.imageCode = 204
        f.repository.refresh()
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty())
        assertEquals("9mm", f.repository.series.value.single { it.id == 41L }.caliber)
    }

    @Test fun aDeleteUnderTheLocalIdAfterTheUploadFinishedDeletesTheServerSeries() = runBlocking {
        val f = Fixture()
        val localId = f.savedThenAcknowledged()

        f.repository.delete(-localId)

        assertEquals(listOf("/series/41"), f.deletes)
        assertTrue(f.repository.series.value.none { it.id == 41L || it.id == -localId })
    }

    @Test fun aDeleteUnderTheLocalIdWhileOnlyThePhotoIsPendingDeletesTheServerSeries() = runBlocking {
        val f = Fixture().also { it.imageCode = 503 }
        val localId = f.savedThenAcknowledged()

        f.repository.delete(-localId)

        assertEquals(listOf("/series/41"), f.deletes)
        assertTrue(f.db.seriesQueries.selectLocal().executeAsList().isEmpty(), "the pending photo upload is cancelled")
        assertNull(f.localImages.read(localId))
        assertTrue(f.repository.series.value.none { it.id == 41L || it.id == -localId })
    }

    @Test fun aDeleteOfALocalIdNobodyKnowsFailsLoudly() = runBlocking {
        val f = Fixture()
        assertFails { f.repository.delete(-999L) }
        assertTrue(f.deletes.isEmpty())
    }

    @Test fun anUnsentLocalSeriesIsStillEditedAndDeletedLocally() = runBlocking {
        val f = Fixture()
        val localId = f.repository.keepLocal(request, EncodedImage(jpeg, 4, 4), owner = null)

        f.repository.update(-localId, edited)
        assertEquals("9mm", f.repository.series.value.single { it.id == -localId }.caliber)
        f.repository.delete(-localId)

        assertTrue(f.puts.isEmpty())
        assertTrue(f.deletes.isEmpty())
        assertTrue(f.repository.series.value.isEmpty())
    }
}
