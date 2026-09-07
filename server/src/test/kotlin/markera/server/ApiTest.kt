package markera.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

    private companion object {
        const val ADMIN_PW = "pw"
    }

    private fun apiTest(
        devAuth: Boolean = true,
        adminPassword: String? = null,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) =
        testApplication {
            val dbFile = File.createTempFile("markera-test", ".db").also { it.delete(); it.deleteOnExit() }
            imagesDir = File(dbFile.path + "-images").also { it.deleteOnExit() }
            application {
                markeraModule(Config(0, dbFile.path, null, null, devAuth, imagesDir.path, adminPassword), Db(dbFile.path))
            }
            val client = createClient { install(ClientContentNegotiation) { json() } }
            block(client)
        }

    private suspend fun HttpClient.admin(path: String) = get(path) { basicAuth("admin", ADMIN_PW) }

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
        query: String = "",
    ) = post("/series/$seriesId/image$query") { bearerAuth(token); contentType(type); setBody(bytes) }

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
        // The old row was the detector's output verbatim, so it becomes an untouched detection.
        Db(dbFile.path).use { db ->
            // ...and the backfill puts the detected position where the old row already sat.
            assertEquals(listOf(Hole(1.0, 2.0, 9, true, 31.2, 9, true, 1.0, 2.0)), db.getSeries(1)?.holes)
            db.insertSeries(1, "2026-09-06T12:34:56Z", "9mm", listOf(Hole(null, null, 5, false, null)))
        }
        // Reopening must not run the rebuild again.
        Db(dbFile.path).use { db ->
            // ...and the backfill puts the detected position where the old row already sat.
            assertEquals(listOf(Hole(1.0, 2.0, 9, true, 31.2, 9, true, 1.0, 2.0)), db.getSeries(1)?.holes)
            assertEquals(listOf(Hole(null, null, 5, false, null)), db.getSeries(2)?.holes)
        }
    }

    @Test
    fun imageSizeIsStoredAndPlacesMarkersOnTheAdminPhoto() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(
            me.token,
            series().copy(
                holes = listOf(
                    Hole(25.0, 50.0, 9, false, 31.2, detectedRing = 9),  // detected: green
                    Hole(75.0, 50.0, 7, false, 60.0),                    // manual: orange
                    Hole(null, null, 5, false, null),                    // typed: no position, no marker
                ),
            ),
        )
        assertEquals(HttpStatusCode.NoContent, client.putImage(me.token, id, ByteArray(64), query = "?width=100&height=200").status)

        val stored = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single()
        assertEquals(100 to 200, stored.imageWidth to stored.imageHeight)

        val page = client.admin("/admin/series/$id").bodyAsText()
        assertTrue("""style="left:25%;top:25%;border-color:#9ccc65;color:#9ccc65">9</div>""" in page, page)
        assertTrue("""style="left:75%;top:25%;border-color:#ffb74d;color:#ffb74d">7</div>""" in page, page)
        assertEquals(2, Regex("""class="hit"""").findAll(page).count(), page)
    }

    @Test
    fun adminMarksEditedManualAndTypedHoles() = apiTest(adminPassword = ADMIN_PW) { client ->
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

        val seriesPage = client.admin("/admin/series/$id").bodyAsText()
        assertTrue("<td>8 &rarr; 9</td>" in seriesPage, seriesPage)
        assertTrue("<td>10 &rarr; X</td>" in seriesPage, seriesPage)
        assertTrue("<td>manual</td>" in seriesPage && "<td>typed</td>" in seriesPage, seriesPage)

        // The four non-plain holes are counted on the user's series list.
        assertTrue("<td>4</td>" in client.admin("/admin/users/${me.userId}").bodyAsText())
    }

    @Test
    fun putReplacesTheSeriesKeepingWhatTheDetectorSaid() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val detected = Hole(1.0, 2.0, 8, false, 31.2, 8, false, 1.0, 2.0)
        val id = client.createSeries(me.token, series().copy(holes = listOf(detected)))

        // Score bumped to 9 and the marker dragged; the detected fields must survive untouched.
        val edited = detected.copy(x = 40.0, y = 60.0, ring = 9)
        val response = client.put("/series/$id") {
            bearerAuth(me.token); contentType(ContentType.Application.Json)
            setBody(series(caliber = "22lr").copy(holes = listOf(edited)))
        }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())

        val stored = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single()
        assertEquals(id, stored.id)
        assertEquals("22lr", stored.caliber)
        assertEquals(listOf(edited), stored.holes)

        assertTrue("<td>8 &rarr; 9, moved</td>" in client.admin("/admin/series/$id").bodyAsText())
    }

    @Test
    fun putNeedsToBeTheOwner() = apiTest { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        val stranger = client.devAuth("stranger").token

        assertEquals(HttpStatusCode.Unauthorized, client.put("/series/$id") {
            contentType(ContentType.Application.Json); setBody(series(caliber = "22lr"))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/series/$id") {
            bearerAuth(stranger); contentType(ContentType.Application.Json); setBody(series(caliber = "22lr"))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/series/999") {
            bearerAuth(me.token); contentType(ContentType.Application.Json); setBody(series())
        }.status)
        // Untouched.
        assertEquals("9mm", client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single().caliber)
    }

    @Test
    fun adminPutEditsAnyUsersSeries() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        val fixed = series(caliber = "22lr").copy(holes = listOf(Hole(1.0, 2.0, 6, false, 90.0)))

        assertEquals(HttpStatusCode.Unauthorized, client.put("/admin/series/$id") {
            contentType(ContentType.Application.Json); setBody(fixed)
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.put("/admin/series/999") {
            basicAuth("admin", ADMIN_PW); contentType(ContentType.Application.Json); setBody(fixed)
        }.status)

        val response = client.put("/admin/series/$id") {
            basicAuth("admin", ADMIN_PW); contentType(ContentType.Application.Json); setBody(fixed)
        }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())

        val stored = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single()
        assertEquals("22lr", stored.caliber)
        assertEquals(fixed.holes, stored.holes)
    }

    @Test
    fun adminDeletesAnyUsersSeries() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val kept = client.createSeries(me.token)
        val doomed = client.createSeries(me.token)
        client.putImage(me.token, doomed, ByteArray(16))
        assertTrue(imagesDir.resolve("$doomed.jpg").isFile)
        assertTrue("""<button id="delete" data-user="${me.userId}">""" in client.admin("/admin/series/$doomed").bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/series/$doomed").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/admin/series/$doomed") { basicAuth("admin", "nope") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/admin/series/999") { basicAuth("admin", ADMIN_PW) }.status,
        )

        val response = client.delete("/admin/series/$doomed") { basicAuth("admin", ADMIN_PW) }
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.admin("/admin/series/$doomed").status)
        assertTrue(!imagesDir.resolve("$doomed.jpg").isFile)
        assertEquals(listOf(kept), client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().map { it.id })
    }

    @Test
    fun detectedPositionsAreStoredButNeverInferred() = apiTest { client ->
        val me = client.devAuth("me")
        // An old app build: detected ring, but no detectedX/Y at all.
        val old = Hole(1.0, 2.0, 9, false, 31.2, detectedRing = 9)
        val new = Hole(3.0, 4.0, 9, false, 31.2, 9, false, 5.0, 6.0)
        client.createSeries(me.token, series().copy(holes = listOf(old, new)))

        val stored = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().single().holes
        assertEquals(listOf(old, new), stored)
        assertEquals(null, stored[0].detectedX)
    }

    @Test
    fun geometryRoundTripsAndIsOptional() = apiTest { client ->
        val me = client.devAuth("me")
        val geometry = Geometry(611.2, 720.5, 610.0, 722.0, 402.5, 388.1, 0.12)
        val withGeometry = client.createSeries(me.token, series().copy(geometry = geometry))
        val without = client.createSeries(me.token)

        val stored = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().associateBy { it.id }
        assertEquals(geometry, stored[withGeometry]?.geometry)
        assertEquals(null, stored[without]?.geometry)

        // PUT sets it on the series that had none.
        val moved = geometry.copy(centreX = 600.0)
        assertEquals(HttpStatusCode.NoContent, client.put("/series/$without") {
            bearerAuth(me.token); contentType(ContentType.Application.Json)
            setBody(series().copy(geometry = moved))
        }.status)
        val reread = client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().associateBy { it.id }
        assertEquals(moved, reread[without]?.geometry)
    }

    @Test
    fun adminDrawsTheGeometryWhenThereIsOne() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val plain = client.createSeries(me.token)
        val drawn = client.createSeries(
            me.token,
            series().copy(geometry = Geometry(50.0, 100.0, 50.0, 100.0, 40.0, 30.0, 0.0)),
        )
        for (id in listOf(plain, drawn)) client.putImage(me.token, id, ByteArray(64), query = "?width=100&height=200")

        val page = client.admin("/admin/series/$drawn").bodyAsText()
        assertTrue("""<svg class="geom" viewBox="0 0 100 200"""" in page, page)
        assertTrue("""<ellipse cx="50" cy="100" rx="40" ry="30"""" in page, page)
        assertTrue(""""ringSemiMajor":40.0""" in page, page)

        val bare = client.admin("/admin/series/$plain").bodyAsText()
        assertTrue("<svg" !in bare && "<ellipse" !in bare, bare)
    }

    @Test
    fun invalidSeriesAreRejected() = apiTest { client ->
        val token = client.devAuth("me").token
        val bad = listOf(
            series(caliber = "50bmg"),
            series().copy(holes = emptyList()),
            series(timestamp = "yesterday"),
            series().copy(geometry = Geometry(1.0, 2.0, 1.0, 2.0, 40.0, 0.0, 0.0)),
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
    fun adminPagesShowUsersSeriesAndImages() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val other = client.devAuth("other")
        val mySeries = client.createSeries(me.token)
        client.createSeries(other.token)
        val jpeg = ByteArray(64) { it.toByte() }
        client.putImage(me.token, mySeries, jpeg)

        val users = client.admin("/admin").bodyAsText()
        assertTrue("me" in users && "other" in users, users)
        // Two dev users, one series each.
        assertEquals(2, Regex("<td>1</td></tr>").findAll(users).count(), users)

        val userPage = client.admin("/admin/users/${me.userId}").bodyAsText()
        assertTrue("9mm" in userPage, userPage)
        assertTrue("<td>19</td>" in userPage, "expected the 9+10 ring total: $userPage")
        assertTrue("""<a href="/admin/series/$mySeries">""" in userPage, userPage)

        val seriesPage = client.admin("/admin/series/$mySeries").bodyAsText()
        // Whole millimetres, and the X hole (manual, so no empty option) has X selected.
        assertTrue("<td>31</td>" in seriesPage, seriesPage)
        assertTrue("""<option value="X" selected>X</option>""" in seriesPage, seriesPage)
        assertTrue("""<img src="/admin/series/$mySeries/image">""" in seriesPage, seriesPage)
        // Uploaded without the frame size, so the photo shows but carries no markers.
        assertTrue("""class="hit"""" !in seriesPage, seriesPage)

        // Whole rows navigate; the id cell keeps its link for the no-JS case.
        assertTrue("""<tr onclick="location.href='/admin/users/${me.userId}'">""" in users, users)
        assertTrue("""<tr onclick="location.href='/admin/series/$mySeries'">""" in userPage, userPage)

        val image = client.admin("/admin/series/$mySeries/image")
        assertEquals(HttpStatusCode.OK, image.status)
        assertContentEquals(jpeg, image.bodyAsBytes())
    }

    @Test
    fun adminShowsTheLoginNameFormatsNumbersAndTimestamps() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("alice")
        val id = client.createSeries(
            me.token,
            series().copy(holes = listOf(Hole(1.23456, 4.25, 9, false, 31.2))),
        )

        // The dev login stores the subject as the name: linked subject cell plus a plain name cell.
        val users = client.admin("/admin").bodyAsText()
        assertTrue("""<td><a href="/admin/users/${me.userId}">alice</a></td><td>alice</td>""" in users, users)

        val seriesPage = client.admin("/admin/series/$id").bodyAsText()
        // x, y and distanceMm are rounded to whole numbers: 1.23456 -> 1, 4.25 -> 4, 31.2 -> 31.
        assertTrue("<td>1</td><td>4</td>" in seriesPage, seriesPage)
        assertTrue("<td>31</td>" in seriesPage, seriesPage)

        val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.parse("2026-09-06T12:34:56Z"))
        assertTrue(local in seriesPage, "expected '$local' in $seriesPage")
        // The raw instant survives only in the state blob the editor PUTs back, never as visible text.
        assertEquals(1, Regex("2026-09-06T12:34:56Z").findAll(seriesPage).count(), seriesPage)
    }

    @Test
    fun adminSeriesPageIsAnEditor() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(
            me.token,
            series().copy(
                holes = listOf(
                    Hole(25.0, 50.0, 9, false, 31.2, 9, false, 25.0, 50.0),    // detected, unedited
                    Hole(30.0, 60.0, 10, true, 8.0, 8, false, 30.0, 60.0),     // detected 8, confirmed X
                    Hole(75.0, 50.0, 7, false, 60.0),                          // manual: no detection
                ),
            ),
        )
        client.putImage(me.token, id, ByteArray(64), query = "?width=100&height=200")

        val page = client.admin("/admin/series/$id").bodyAsText()
        // The starting state the script edits, holes and frame size included.
        assertTrue("""<script type="application/json" id="series">""" in page, page)
        assertTrue(""""detectedX":25.0""" in page && """"imageWidth":100""" in page, page)
        assertTrue("<th>detected</th><th>manual</th>" in page, page)
        // Unedited detection: plain detected cell, and the manual select sits on its empty option.
        assertTrue(
            """<tr data-i="0"><td>25</td><td>50</td><td>9</td>""" +
                """<td><select class="manual"><option value="" selected></option>""" in page,
            page,
        )
        // Overridden: the detected cell is struck through and the confirmed X is selected.
        assertTrue(
            """<tr data-i="1"><td>30</td><td>60</td><td class="dim">8</td>""" +
                """<td><select class="manual"><option value=""></option>""" in page,
            page,
        )
        assertTrue("""<option value="X" selected>X</option>""" in page, page)
        // Manual hole: nothing detected, and no empty option to fall back to.
        assertTrue(
            """<tr data-i="2"><td>75</td><td>50</td><td></td>""" +
                """<td><select class="manual"><option value="0">0</option>""" in page,
            page,
        )
        assertTrue("""<option value="7" selected>7</option>""" in page, page)
        // One manual select per hole, plus the blank one in the row template.
        assertEquals(4, Regex("""<select class="manual">""").findAll(page).count(), page)
        assertTrue("""<button id="save">Save</button>""" in page, page)
        assertTrue("""<button class="del">Delete</button>""" in page, page)
        assertTrue("""<div class="hit" data-i="0"""" in page, page)
        // The photo has a frame size, so nothing warns about placing markers and holes are added by
        // clicking the photo — no position-less "Add hole" button to leave an empty typed row behind.
        assertTrue("class=\"note\"" !in page, page)
        assertTrue("""id="add"""" !in page, page)
    }

    @Test
    fun adminSeriesPageSaysWhenMarkersCannotBePlaced() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val id = client.createSeries(me.token)
        // No image at all, and then an image uploaded without the frame size: neither can place a marker.
        val noImage = client.admin("/admin/series/$id").bodyAsText()
        assertTrue("""<p class="note">""" in noImage && """class="hit"""" !in noImage, noImage)

        client.putImage(me.token, id, ByteArray(64))
        val noSize = client.admin("/admin/series/$id").bodyAsText()
        assertTrue("""<p class="note">""" in noSize && """class="hit"""" !in noSize, noSize)
        // The "Add hole" button is the way in when there is nowhere to click.
        assertTrue("""<button id="add">Add hole</button>""" in noSize, noSize)
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
    fun adminEscapesDatabaseValues() = apiTest(adminPassword = ADMIN_PW) { client ->
        client.devAuth("<b>bold</b>")
        val users = client.admin("/admin").bodyAsText()
        assertTrue("&lt;b&gt;bold&lt;/b&gt;" in users, users)
        assertTrue("<b>" !in users, users)
    }

    @Test
    fun adminUnknownIdsAre404() = apiTest(adminPassword = ADMIN_PW) { client ->
        for (path in listOf("/admin/users/99", "/admin/series/99", "/admin/series/99/image")) {
            assertEquals(HttpStatusCode.NotFound, client.admin(path).status, path)
        }
    }

    @Test
    fun adminIsAbsentWithoutAPassword() = apiTest { client ->
        assertEquals(HttpStatusCode.NotFound, client.admin("/admin").status)
    }

    @Test
    fun adminNeedsTheRightPassword() = apiTest(adminPassword = ADMIN_PW) { client ->
        val anonymous = client.get("/admin")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("""Basic realm="markera-admin"""", anonymous.headers[HttpHeaders.WWWAuthenticate])
        for (bad in listOf("admin" to "nope", "root" to ADMIN_PW)) {
            val response = client.get("/admin") { basicAuth(bad.first, bad.second) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$bad")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin") { header(HttpHeaders.Authorization, "Basic !!") }.status)
        assertEquals(HttpStatusCode.OK, client.admin("/admin").status)
    }

    @Test
    fun deleteSeriesRemovesItAndItsImage() = apiTest { client ->
        val me = client.devAuth("me")
        val kept = client.createSeries(me.token)
        val doomed = client.createSeries(me.token)
        client.putImage(me.token, doomed, ByteArray(16))
        assertTrue(imagesDir.resolve("$doomed.jpg").isFile)

        val stranger = client.devAuth("stranger").token
        assertEquals(HttpStatusCode.NotFound, client.delete("/series/$doomed") { bearerAuth(stranger) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/series/$doomed").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/series/999") { bearerAuth(me.token) }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/series/$doomed") { bearerAuth(me.token) }.status)
        assertEquals(listOf(kept), client.get("/series") { bearerAuth(me.token) }.body<List<Series>>().map { it.id })
        assertTrue(!imagesDir.resolve("$doomed.jpg").isFile)
    }

    @Test
    fun deleteAccountRemovesEverythingAndInvalidatesTheToken() = apiTest(adminPassword = ADMIN_PW) { client ->
        val me = client.devAuth("me")
        val other = client.devAuth("other")
        val mine = client.createSeries(me.token)
        client.putImage(me.token, mine, ByteArray(16))
        val theirs = client.createSeries(other.token)
        client.putImage(other.token, theirs, ByteArray(16))

        assertEquals(HttpStatusCode.Unauthorized, client.delete("/account").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/account") { bearerAuth(me.token) }.status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/series") { bearerAuth(me.token) }.status)
        assertTrue(!imagesDir.resolve("$mine.jpg").isFile)
        val users = client.admin("/admin").bodyAsText()
        assertTrue("/admin/users/${me.userId}" !in users, users)
        assertTrue("/admin/users/${other.userId}" in users, users)
        // The other user is untouched.
        assertEquals(listOf(theirs), client.get("/series") { bearerAuth(other.token) }.body<List<Series>>().map { it.id })
        assertTrue(imagesDir.resolve("$theirs.jpg").isFile)
    }

    @Test
    fun seriesListPagesNewestFirst() = apiTest { client ->
        val me = client.devAuth("me")
        val ids = (1..5).map { client.createSeries(me.token, series(timestamp = "2026-09-0${it}T10:00:00Z")) }

        suspend fun page(query: String) =
            client.get("/series$query") { bearerAuth(me.token) }.body<List<Series>>().map { it.id }

        assertEquals(ids.reversed().take(2), page("?limit=2"))
        assertEquals(ids.reversed().drop(2).take(2), page("?limit=2&before=${ids[3]}"))
        assertEquals(listOf(ids[0]), page("?limit=2&before=${ids[1]}"))
        assertEquals(emptyList(), page("?limit=2&before=${ids[0]}"))
        // Out-of-range limits clamp instead of failing.
        assertEquals(listOf(ids[4]), page("?limit=0"))
        assertEquals(ids.reversed(), page("?limit=999"))
        assertEquals(ids.reversed(), page(""))
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
