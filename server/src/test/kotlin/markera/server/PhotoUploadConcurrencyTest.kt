package markera.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR #63 (#23, #24, #40): a photo upload's file write runs on `Dispatchers.IO` and outside the Db lock, so a slow
 * write never holds up other requests. [PhotoStorageTest] proves the lock part on [Db] directly; this goes
 * through HTTP, holding a `POST /series/{id}/image` mid-write while a `GET /series` must answer promptly.
 */
class PhotoUploadConcurrencyTest {

    @Test fun `GET series answers while a photo upload is still writing its file`() {
        val dbFile = File.createTempFile("markera-upload", ".db").also { it.delete() }
        val imagesDir = File(dbFile.path + "-images")
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        // One request thread, like a busy server's: a handler that blocked it would stall every other request.
        // The test engine's default dispatcher has many threads, which would hide a missing Dispatchers.IO hop.
        val requestThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            testApplication {
                engine { dispatcher = requestThread }
                application {
                    markeraModule(
                        Config(0, dbFile.path, null, null, devAuth = true, imagesDir = imagesDir.path, minFreeDiskBytes = 0),
                        Db(dbFile.path),
                        writeUpload = { path, data ->
                            writing.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            java.nio.file.Files.write(path, data)
                        },
                    )
                }
                val client = createClient { install(ClientContentNegotiation) { json() } }
                val token = client.post("/auth/dev") {
                    contentType(ContentType.Application.Json); setBody(DevAuthRequest("uploader"))
                }.body<AuthResponse>().token
                val id = client.post("/series") {
                    bearerAuth(token); contentType(ContentType.Application.Json)
                    setBody(SeriesRequest("2026-10-20T00:00:00Z", "9mm", listOf(Hole(1.0, 2.0, 9, false))))
                }.body<IdResponse>().id
                try { coroutineScope {
                    val upload = async {
                        client.post("/series/$id/image") {
                            bearerAuth(token); contentType(ContentType.Image.JPEG)
                            setBody(byteArrayOf(-1, -40, 1, 2))
                        }.status
                    }
                    val started = withContext(Dispatchers.IO) { writing.await(10, TimeUnit.SECONDS) }
                    assertTrue(started, "the upload never started writing")

                    val t0 = System.nanoTime()
                    val list = withTimeout(5_000) { client.get("/series") { bearerAuth(token) } }
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    assertEquals(HttpStatusCode.OK, list.status)
                    assertTrue(elapsedMs < 2_000, "GET /series took $elapsedMs ms while an upload was writing")
                    // Still held: the GET did not just slip through after the write finished.
                    assertEquals(1L, release.count, "the upload was released before the GET answered")

                    release.countDown()
                    assertEquals(HttpStatusCode.NoContent, withTimeout(10_000) { upload.await() })
                    assertTrue(imageFile(imagesDir, id).isFile)
                } } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            requestThread.close()
            imagesDir.deleteRecursively()
            dbFile.delete()
        }
    }
}
