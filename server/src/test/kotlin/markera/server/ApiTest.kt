package markera.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private lateinit var imagesDir: File

    private fun apiTest(
        devAuth: Boolean = true,
        adminUi: Boolean = false,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) =
        testApplication {
            val dbFile = File.createTempFile("markera-test", ".db").also { it.delete(); it.deleteOnExit() }
            imagesDir = File(dbFile.path + "-images").also { it.deleteOnExit() }
            application {
                markeraModule(Config(0, dbFile.path, null, null, devAuth, imagesDir.path, adminUi), Db(dbFile.path))
            }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            block(client)
        }

    private suspend fun HttpClient.devAuth(subject: String): AuthResponse =
        post("/auth/dev") { contentType(ContentType.Application.Json); setBody(DevAuthRequest(subject)) }.body()

    private suspend fun HttpClient.createSeries(token: String, request: SeriesRequest = series()): Long =
        post("/series") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
        }.body<IdResponse>().id

    private suspend fun HttpClient.putImage(
        token: String,
        seriesId: Long,
        bytes: ByteArray,
        type: ContentType = ContentType.Image.JPEG,
    ) = post("/series/$seriesId/image") { bearerAuth(token); contentType(type); setBody(bytes) }

    private fun series(timestamp: String = "2026-09-06T12:34:56Z", caliber: String = "9mm") = SeriesRequest(
        timestamp = timestamp,
        caliber = caliber,
        holes = listOf(
            Hole(1.0, 2.0, 9, false, 31.2),
            Hole(-3.5, 4.25, 10, true, 8.0),
        ),
    )

    @Test
    fun health() = apiTest { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }

    @Test
    fun devAuthIssuesTokenAndReusesTheUser() = apiTest { client ->
        val first = client.devAuth("tester")
        val second = client.devAuth("tester")
        val other = client.devAuth("someone-else")
        assertTrue(first.token.length == 64, "expected a 32-byte hex token, got '${first.token}'")
        assertTrue(first.token != second.token, "each login gets its own session token")
        assertEquals(first.userId, second.userId)
        assertTrue(other.userId != first.userId)
    }

    @Test
    fun seriesRoundTrips() = apiTest { client ->
        val me = client.devAuth("me")
        val older = series(timestamp = "2026-09-05T10:00:00Z", caliber = "22lr")
        val newer = series(timestamp = "2026-09-06T12:34:56Z")
        for (request in listOf(older, newer)) {
            val response = client.post("/series") {
                bearerAuth(me.token); contentType(ContentType.Application.Json); setBody(request)
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.body<IdResponse>().id > 0)
        }

        val stored: List<Series> = client.get("/series") { bearerAuth(me.token) }.body()
        assertEquals(listOf(newer.timestamp, older.timestamp), stored.map { it.timestamp })
        assertEquals(listOf("9mm", "22lr"), stored.map { it.caliber })
        assertEquals(newer.holes, stored[0].holes)

        val stranger = client.devAuth("stranger")
        assertEquals(emptyList(), client.get("/series") { bearerAuth(stranger.token) }.body<List<Series>>())
    }

    @Test
    fun detectedManualAndTypedHolesRoundTrip() = apiTest { client ->
        val me = client.devAuth("me")
        val holes = listOf(
            Hole(1.0, 2.0, 9, false, 31.2, detectedRing = 9),            // detected, confirmed as-is
            Hole(1.0, 2.0, 10, true, 8.0, 8, false),                     // detected, edited to X
            Hole(-3.5, 4.25, 7, false, 60.0),                            // manual: placed by hand, no detection
            Hole(null, null, 5, false, null),                            // typed into an empty picker slot
        )
        client.createSeries(me.token, series().copy(holes = holes))

        val stored: List<Series> = client.get("/series") { bearerAuth(me.token) }.body()
        assertEquals(holes, stored.single().holes)
    }

    @Test
    fun holesWithoutTheDetectedFieldsStillPost() = apiTest { client ->
        val me = client.devAuth("me")
        // The shape an old app build posts: no detectedRing/detectedInnerTen at all.
        val response = client.post("/series") {
            bearerAuth(me.token)
            setBody(
                TextContent(
                    """{"timestamp":"2026-09-06T12:34:56Z","caliber":"9mm",""" +
                        """"holes":[{"x":1.0,"y":2.0,"ring":9,"innerTen":false,"distanceMm":31.2}]}""",
                    ContentType.Application.Json,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

        val hole = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single().holes.single()
        assertEquals(Hole(1.0, 2.0, 9, false, 31.2, null, null), hole)
    }

    @Test
    fun typedHolesPostWithTheirNullsOmitted() = apiTest { client ->
        val me = client.devAuth("me")
        // The app omits null fields (explicitNulls = false): a typed hole is just ring + innerTen.
        val response = client.post("/series") {
            bearerAuth(me.token)
            setBody(
                TextContent(
                    """{"timestamp":"2026-09-06T12:34:56Z","caliber":"9mm","holes":[{"ring":5,"innerTen":false}]}""",
                    ContentType.Application.Json,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val hole = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single().holes.single()
        assertEquals(Hole(null, null, 5, false, null), hole)
    }

    @Test
    fun oldDatabasesGainTheDetectedColumns() {
        val dbFile = File.createTempFile("markera-old-holes", ".db").also { it.delete(); it.deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE users (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         provider TEXT NOT NULL, subject TEXT NOT NULL, created_at TEXT NOT NULL,
                         UNIQUE(provider, subject))"""
                )
                st.executeUpdate(
                    """CREATE TABLE series (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         user_id INTEGER NOT NULL REFERENCES users(id),
                         timestamp TEXT NOT NULL, caliber TEXT NOT NULL, created_at TEXT NOT NULL)"""
                )
                st.executeUpdate(
                    """CREATE TABLE holes (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         series_id INTEGER NOT NULL REFERENCES series(id),
                         x REAL NOT NULL, y REAL NOT NULL, ring INTEGER NOT NULL,
                         inner_ten INTEGER NOT NULL, distance_mm REAL NOT NULL)"""
                )
                st.executeUpdate("INSERT INTO users(provider, subject, created_at) VALUES ('google', 'old', 'then')")
                st.executeUpdate(
                    "INSERT INTO series(user_id, timestamp, caliber, created_at) VALUES (1, 'then', '9mm', 'then')"
                )
                st.executeUpdate(
                    "INSERT INTO holes(series_id, x, y, ring, inner_ten, distance_mm) VALUES (1, 1.0, 2.0, 9, 1, 31.2)"
                )
            }
        }
        // The old row survives with unknown detector values, and a new hole with nulls now fits.
        Db(dbFile.path).use { db ->
            assertEquals(listOf(Hole(1.0, 2.0, 9, true, 31.2, null, null)), db.getSeries(1)?.holes)
            db.insertSeries(1, "2026-09-06T12:34:56Z", "9mm", listOf(Hole(null, null, 5, false, null)))
        }
        // Reopening must not run the rebuild again.
        Db(dbFile.path).use { db ->
            assertEquals(listOf(Hole(1.0, 2.0, 9, true, 31.2, null, null)), db.getSeries(1)?.holes)
            assertEquals(listOf(Hole(null, null, 5, false, null)), db.getSeries(2)?.holes)
        }
    }

    @Test
    fun adminMarksEditedManualAndTypedHoles() = apiTest(adminUi = true) { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(
            me.token,
            series().copy(
                holes = listOf(
                    Hole(1.0, 2.0, 9, false, 31.2, 9, false),   // untouched detection
                    Hole(1.0, 2.0, 9, false, 31.2, 8, false),   // 8 -> 9
                    Hole(1.0, 2.0, 10, true, 8.0, 10, false),   // 10 -> X
                    Hole(-3.5, 4.25, 7, false, 60.0),           // manual
                    Hole(null, null, 5, false, null),           // typed
                ),
            ),
        )

        val seriesPage = client.get("/admin/series/$id").bodyAsText()
        assertTrue("<td>8 &rarr; 9</td>" in seriesPage, seriesPage)
        assertTrue("<td>10 &rarr; X</td>" in seriesPage, seriesPage)
        assertTrue("<td>manual</td>" in seriesPage && "<td>typed</td>" in seriesPage, seriesPage)

        // The four non-plain holes are counted on the user's series list.
        assertTrue("<td>4</td>" in client.get("/admin/users/${me.userId}").bodyAsText())
    }

    @Test
    fun invalidSeriesAreRejected() = apiTest { client ->
        val token = client.devAuth("me").token
        val bad = listOf(
            series(caliber = "50bmg"),
            series().copy(holes = emptyList()),
            series(timestamp = "yesterday"),
        )
        for (request in bad) {
            val response = client.post("/series") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $request")
            assertTrue(response.body<ErrorResponse>().error.isNotEmpty())
        }
    }

    @Test
    fun seriesNeedsAValidBearerToken() = apiTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series") { bearerAuth("nope") }.status)
        val post = client.post("/series") {
            bearerAuth("nope"); contentType(ContentType.Application.Json); setBody(series())
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    @Test
    fun imageRoundTripsAndShowsUpInTheSeriesList() = apiTest { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        assertEquals(listOf(false), client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().map { it.hasImage })

        val jpeg = ByteArray(4096) { (it % 251).toByte() }
        assertEquals(HttpStatusCode.NoContent, client.putImage(me.token, id, jpeg).status)

        val download = client.get("/series/$id/image") { bearerAuth(me.token) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(ContentType.Image.JPEG, download.contentType()?.withoutParameters())
        assertContentEquals(jpeg, download.bodyAsBytes())
        assertEquals(listOf(true), client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().map { it.hasImage })
    }

    @Test
    fun imagesOfOtherUsersAndUnknownSeriesAre404() = apiTest { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        client.putImage(me.token, id, ByteArray(16))
        val stranger = client.devAuth("stranger").token

        for (target in listOf(id, 999L)) {
            assertEquals(HttpStatusCode.NotFound, client.putImage(stranger, target, ByteArray(16)).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/series/$target/image") { bearerAuth(stranger) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/series/999/image") { bearerAuth(me.token) }.status)
        // The owner's image survived the strangers' attempts.
        assertContentEquals(ByteArray(16), client.get("/series/$id/image") { bearerAuth(me.token) }.bodyAsBytes())
    }

    @Test
    fun missingImageIs404() = apiTest { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        val response = client.get("/series/$id/image") { bearerAuth(me.token) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("no image", response.body<ErrorResponse>().error)
    }

    @Test
    fun imageUploadRejectsWrongTypeAndOversizedBodies() = apiTest { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)

        val wrongType = client.putImage(me.token, id, "not a jpeg".toByteArray(), ContentType.Text.Plain)
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)

        val tooBig = client.putImage(me.token, id, ByteArray(6 * 1024 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)

        assertEquals(emptyList(), imagesDir.listFiles().orEmpty().map { it.name })
        assertEquals(HttpStatusCode.NotFound, client.get("/series/$id/image") { bearerAuth(me.token) }.status)
    }

    @Test
    fun imageRoutesNeedAValidBearerToken() = apiTest { client ->
        val id = client.createSeries(client.devAuth("me").token)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/series/$id/image").status)
        val post = client.post("/series/$id/image") {
            contentType(ContentType.Image.JPEG); setBody(ByteArray(16))
        }
        assertEquals(HttpStatusCode.Unauthorized, post.status)
    }

    @Test
    fun adminPagesShowUsersSeriesAndImages() = apiTest(adminUi = true) { client ->
        val me = client.devAuth("me")
        val other = client.devAuth("other")
        val mySeries = client.createSeries(me.token)
        client.createSeries(other.token)
        val jpeg = ByteArray(64) { it.toByte() }
        client.putImage(me.token, mySeries, jpeg)

        val users = client.get("/admin").bodyAsText()
        assertTrue("me" in users && "other" in users, users)
        // Two dev users, one series each.
        assertEquals(2, Regex("<td>1</td></tr>").findAll(users).count(), users)

        val userPage = client.get("/admin/users/${me.userId}").bodyAsText()
        assertTrue("9mm" in userPage, userPage)
        assertTrue("<td>19</td>" in userPage, "expected the 9+10 ring total: $userPage")
        assertTrue("""<a href="/admin/series/$mySeries">""" in userPage, userPage)

        val seriesPage = client.get("/admin/series/$mySeries").bodyAsText()
        assertTrue("<td>31.2</td>" in seriesPage && "<td>true</td>" in seriesPage, seriesPage)
        assertTrue("""<img src="/admin/series/$mySeries/image">""" in seriesPage, seriesPage)

        val image = client.get("/admin/series/$mySeries/image")
        assertEquals(HttpStatusCode.OK, image.status)
        assertContentEquals(jpeg, image.bodyAsBytes())
    }

    @Test
    fun adminShowsTheLoginNameFormatsNumbersAndTimestamps() = apiTest(adminUi = true) { client ->
        val me = client.devAuth("alice")
        val id = client.createSeries(
            me.token,
            series().copy(holes = listOf(Hole(1.23456, 4.25, 9, false, 31.2))),
        )

        // The dev login stores the subject as the name: linked subject cell plus a plain name cell.
        val users = client.get("/admin").bodyAsText()
        assertTrue("""<td><a href="/admin/users/${me.userId}">alice</a></td><td>alice</td>""" in users, users)

        val seriesPage = client.get("/admin/series/$id").bodyAsText()
        assertTrue("<td>1.23</td>" in seriesPage, seriesPage)
        assertTrue("<td>4.25</td>" in seriesPage && "<td>31.2</td>" in seriesPage, seriesPage)

        val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.parse("2026-09-06T12:34:56Z"))
        assertTrue(local in seriesPage, "expected '$local' in $seriesPage")
        assertTrue("2026-09-06T12:34:56Z" !in seriesPage, seriesPage)
    }

    @Test
    fun oldDatabasesGainTheNameColumn() {
        val dbFile = File.createTempFile("markera-old", ".db").also { it.delete(); it.deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE users (
                         id INTEGER PRIMARY KEY AUTOINCREMENT,
                         provider TEXT NOT NULL,
                         subject TEXT NOT NULL,
                         created_at TEXT NOT NULL,
                         UNIQUE(provider, subject))"""
                )
                st.executeUpdate("INSERT INTO users(provider, subject, created_at) VALUES ('google', 'old', 'then')")
            }
        }
        Db(dbFile.path).use { db ->
            val users = db.listUsers()
            assertEquals(listOf("old" to null), users.map { it.subject to it.name })
            assertEquals(0, users.single().seriesCount)
            db.upsertUser("google", "old", "Ada")
            assertEquals("Ada", db.listUsers().single().name)
            // A later login without a name never clears it.
            db.upsertUser("google", "old", null)
            assertEquals("Ada", db.listUsers().single().name)
        }
    }

    @Test
    fun adminEscapesDatabaseValues() = apiTest(adminUi = true) { client ->
        client.devAuth("<b>bold</b>")
        val users = client.get("/admin").bodyAsText()
        assertTrue("&lt;b&gt;bold&lt;/b&gt;" in users, users)
        assertTrue("<b>" !in users, users)
    }

    @Test
    fun adminUnknownIdsAre404() = apiTest(adminUi = true) { client ->
        for (path in listOf("/admin/users/99", "/admin/series/99", "/admin/series/99/image")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
    }

    @Test
    fun adminIsAbsentUnlessEnabled() = apiTest { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/admin").status)
    }

    @Test
    fun devAuthIsAbsentUnlessEnabled() = apiTest(devAuth = false) { client ->
        val response = client.post("/auth/dev") {
            contentType(ContentType.Application.Json); setBody(DevAuthRequest("tester"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun unconfiguredProvidersReport503() = apiTest { client ->
        val response = client.post("/auth/google") {
            contentType(ContentType.Application.Json); setBody(IdTokenRequest("whatever"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
