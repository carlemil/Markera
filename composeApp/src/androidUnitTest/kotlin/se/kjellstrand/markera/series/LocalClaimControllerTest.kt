package se.kjellstrand.markera.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.kjellstrand.markera.series.db.MarkeraDb

/** #652 is deliberately red until #654 implements the real gate/controller contracts.
 * Seed SQLite directly: no test dies in keepLocal before reaching the claim behavior it specifies.
 * No Compose test harness exists here; rendering and back/outside taps remain #654 phone checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalClaimControllerTest {
    private enum class Provider(val path: String, val stored: String) {
        GOOGLE("/auth/google", "google"), APPLE("/auth/apple", "apple"),
        APPLE_CLAIM("/auth/apple/claim", "apple"), DEV("/auth/dev", "dev");
        suspend fun signIn(session: BackendSessionRepository): BackendAuth = when (this) {
            GOOGLE -> session.signInGoogle("google-id")
            APPLE -> session.signInApple("apple-id")
            APPLE_CLAIM -> session.signInAppleClaim("state", "secret", "nonce")
            DEV -> session.signInDev("subject")
        }
    }

    private class RecordingStore : BackendTokenStore {
        var saved: BackendAuth? = null
        val writes = mutableListOf<BackendAuth>()
        var beforeWrite: () -> Unit = {}
        override suspend fun read() = saved
        override suspend fun write(auth: BackendAuth) { beforeWrite(); saved = auth; writes += auth }
        override suspend fun clear() { saved = null }
    }

    private class Fixture(val test: TestScope) : AutoCloseable {
        val path = Files.createTempFile("markera-claim-", ".sqlite")
        var driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        var db = MarkeraDb(driver)
        val synced = FakeImageCache()
        val local = FakeImageCache()
        val store = RecordingStore()
        val rowIdsAtPublication = mutableListOf<List<Long>>()
        val photoIdsAtPublication = mutableListOf<Set<Long>>()
        val calls = mutableListOf<HttpRequestData>()
        val posts = mutableListOf<SeriesRequest>()
        val successful = linkedMapOf<Long, SeriesRequest>()
        val postTokens = mutableListOf<String?>()
        var user = 42L
        var authStatus = HttpStatusCode.OK
        var cancelAuth = false
        var failPost: Int? = null
        var holdPost: CompletableDeferred<Unit>? = null
        var postEntered = CompletableDeferred<Unit>()
        private var nextId = 100L
        lateinit var session: BackendSessionRepository
        lateinit var repository: SeriesRepository
        lateinit var controller: LocalClaimController
        lateinit var controllerScope: CoroutineScope
        val engine = MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(test.testScheduler)
            addHandler { request ->
                calls += request
                val path = request.url.encodedPath
                val body = when {
                    path.startsWith("/auth/") && request.method == HttpMethod.Post -> {
                        if (cancelAuth) throw CancellationException("provider cancelled")
                        if (authStatus == HttpStatusCode.OK) """{"token":"token-$user","userId":$user}"""
                        else """{"error":"sign-in failed"}"""
                    }
                    path == "/series" && request.method == HttpMethod.Post -> {
                        val payload = seriesJson.decodeFromString<SeriesRequest>((request.body as TextContent).text)
                        posts += payload
                        postTokens += request.headers[HttpHeaders.Authorization]
                        postEntered.complete(Unit)
                        holdPost?.await()
                        if (posts.size == failPost) return@addHandler respond(
                            """{"error":"offline backend"}""", HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                        val id = nextId++
                        successful[id] = payload
                        """{"id":$id}"""
                    }
                    path == "/series" && request.method == HttpMethod.Get -> seriesJson.encodeToString(
                        successful.map { (id, r) -> SeriesDto(id, r.timestamp, r.caliber, r.holes.filterNot { it.deleted },
                            hasImage = true, geometry = r.geometry, tag = r.tag) },
                    )
                    path.endsWith("/image") -> "{}"
                    path == "/auth/session" -> "{}"
                    else -> error("Unexpected HTTP ${request.method} $path")
                }
                respond(body, if (path.startsWith("/auth/")) authStatus else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        })
        val client = createSeriesHttpClient(engine)
        val api = SeriesApi(client, "http://claim.test") { session.currentToken }
        init {
            MarkeraDb.Schema.create(driver)
            store.beforeWrite = {
                rowIdsAtPublication += snapshot().map { it.local_id }
                photoIdsAtPublication += local.files.keys.toSet()
            }
            connect()
        }

        private fun connect() {
            session = BackendSessionRepository(api, store)
            repository = SeriesRepository(api, db, synced, session, local)
            session.claimGate = repository
            controllerScope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
            controller = LocalClaimController(repository, session, controllerScope)
        }

        fun request(id: Long) = SeriesRequest(
            timestamp = "2026-10-01T12:34:0${id}Z", caliber = "9mm", tag = "tävling-$id", clientId = "claim-$id",
            geometry = GeometryDto(100.0, 101.0, 98.0, 103.0, 70.0, 60.0, 0.2),
            holes = listOf(
                HoleDto(12.0, 15.0, 10, true, 1.5, 9, false, 11.0, 14.0, 5.0, 6.0, 0.91),
                HoleDto(null, null, 7, false, null),
                HoleDto(24.0, 30.0, 0, false, 110.0, 6, false, 23.0, 29.0, 4.0, 4.5, 0.8, deleted = true),
            ),
        )

        fun seed(id: Long = 1, owner: Long? = null, photo: Boolean = true) {
            // SQL literals are fixture constants, never application input. JSON is still bound.
            driver.execute(null, """INSERT INTO local_series(local_id,client_id,owner_user_id,request,
                image_width,image_height,has_image,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", 8) {
                bindLong(0, id); bindString(1, "claim-$id"); bindString(2, owner?.toString())
                bindString(3, seriesJson.encodeToString(request(id))); bindLong(4, if (photo) 3072L else null)
                bindLong(5, if (photo) 2048L else null); bindLong(6, if (photo) 1L else 0L)
                bindString(7, request(id).timestamp)
            }
            if (photo) { local.write(id, byteArrayOf(id.toByte(), 2, 3)); local.writeThumb(id, byteArrayOf(9)) }
        }

        suspend fun signIn(provider: Provider = Provider.GOOGLE) {
            val offered = snapshot().count { it.owner_user_id != user.toString() }
            provider.signIn(session)
            test.runCurrent()
            if (offered > 0) withContext(Dispatchers.Default) {
                withTimeout(5_000) { controller.state.first { it == ClaimState.Ask(offered) } }
            }
        }
        suspend fun awaitChoiceFinished() {
            test.runCurrent()
            // The repository uses Dispatchers.Default for SQLite/image work. Virtual-time
            // advanceUntilIdle alone can return while that real dispatcher is still running.
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    controller.state.first { it == ClaimState.Hidden || it is ClaimState.Error }
                }
            }
            test.runCurrent()
        }
        fun assertHeld(count: Int) {
            assertNull(session.auth.value); assertNull(session.currentToken); assertNull(store.saved)
            assertTrue(store.writes.isEmpty()); assertEquals(user, session.pendingAuth.value?.userId)
            assertEquals(ClaimState.Ask(count), controller.state.value)
        }
        fun snapshot() = db.seriesQueries.selectLocal().executeAsList()
        suspend fun restart() {
            // Model process loss, without letting dispose perform any business cleanup first.
            controllerScope.cancel(); test.runCurrent()
            driver.close(); driver = JdbcSqliteDriver("jdbc:sqlite:$path"); db = MarkeraDb(driver)
            connect(); session.restore(); test.runCurrent()
        }
        override fun close() { controllerScope.cancel(); client.close(); driver.close(); Files.deleteIfExists(path) }
    }

    private fun scenario(body: suspend TestScope.(Fixture) -> Unit) = runTest {
        Fixture(this).use { body(it) }
    }

    private fun held(provider: Provider) = scenario { f ->
        f.seed(); f.signIn(provider); f.assertHeld(1)
        assertEquals(provider.path, f.calls.single().url.encodedPath)
        assertEquals(provider.stored, f.session.pendingAuth.value?.provider)
        assertTrue(f.posts.isEmpty())
    }
    @Test fun googleHoldsAuthUntilChoice() = held(Provider.GOOGLE)
    @Test fun nativeAppleHoldsAuthUntilChoice() = held(Provider.APPLE)
    @Test fun androidAppleClaimHoldsAuthUntilChoice() = held(Provider.APPLE_CLAIM)
    @Test fun devHoldsAuthUntilChoice() = held(Provider.DEV)

    @Test fun zeroLocalRowsPublishWithoutPromptForEveryProvider() = scenario { f ->
        for (provider in Provider.entries) {
            f.signIn(provider)
            assertEquals(BackendAuth("token-42", 42L, provider.stored), f.session.auth.value)
            assertEquals(f.session.auth.value, f.store.saved); assertNull(f.session.pendingAuth.value)
            assertEquals(ClaimState.Hidden, f.controller.state.value)
            f.session.signOut()
        }
    }

    @Test fun ownAccountQueueResumesSilentlyWithoutBeingOffered() = scenario { f ->
        f.seed(owner = 42); f.signIn()
        assertEquals(ClaimState.Hidden, f.controller.state.value); assertNull(f.session.pendingAuth.value)
        assertEquals(42L, f.session.auth.value?.userId)
        assertNull(f.repository.refresh())
        assertEquals(listOf(f.request(1)), f.posts); assertTrue(f.snapshot().isEmpty())
    }

    @Test fun failuresAndCancellationForEveryProviderNeverOpenClaimOrLoseData() = scenario { f ->
        f.seed(); val rows = f.snapshot(); val photo = f.local.read(1)!!.copyOf()
        for (provider in Provider.entries) {
            f.authStatus = HttpStatusCode.Unauthorized
            assertFailsWith<SeriesApiException> { provider.signIn(f.session) }
            f.authStatus = HttpStatusCode.OK; f.cancelAuth = true
            assertFailsWith<CancellationException> { provider.signIn(f.session) }
            f.cancelAuth = false; runCurrent()
            assertEquals(ClaimState.Hidden, f.controller.state.value); assertNull(f.session.pendingAuth.value)
            assertNull(f.session.auth.value); assertNull(f.store.saved); assertTrue(f.store.writes.isEmpty())
            assertEquals(rows, f.snapshot()); assertContentEquals(photo, f.local.read(1))
        }
        assertTrue(f.posts.isEmpty())
    }

    @Test fun addPreservesPayloadPhotosAndHistoryAndDoesNotPromptNextSignIn() = scenario { f ->
        f.seed(); f.seed(2, photo = false); f.signIn(); f.assertHeld(2)
        f.controller.add(); f.awaitChoiceFinished()
        assertEquals(listOf(f.request(1), f.request(2)), f.posts)
        assertTrue(f.postTokens.all { it == "Bearer token-42" })
        assertTrue(f.snapshot().isEmpty()); assertTrue(f.local.files.isEmpty())
        assertContentEquals(byteArrayOf(1, 2, 3), f.synced.read(100))
        val upload = f.calls.single { it.url.encodedPath == "/series/100/image" }
        assertEquals(HttpMethod.Post, upload.method)
        assertContentEquals(byteArrayOf(1, 2, 3), upload.body.toByteArray())
        assertEquals("3072", upload.url.parameters["width"]); assertEquals("2048", upload.url.parameters["height"])
        assertEquals(setOf(100L, 101L), f.repository.series.value.map { it.id }.toSet())
        assertTrue(f.repository.series.value.all { it.local == null })
        assertEquals(ClaimState.Hidden, f.controller.state.value); assertNull(f.session.pendingAuth.value)
        f.session.signOut(); f.signIn(); assertEquals(ClaimState.Hidden, f.controller.state.value)
    }

    @Test fun doubleTapWhileFirstPostIsSuspendedDoesNotUploadTwice() = scenario { f ->
        f.seed(); f.signIn(); f.holdPost = CompletableDeferred()
        f.controller.add(); runCurrent()
        // Wait for the boundary even when repository work crosses to Dispatchers.Default.
        // The real-time timeout is bounded and does not race the test scheduler's clock.
        withContext(Dispatchers.Default) { withTimeout(5_000) { f.postEntered.await() } }
        assertEquals(ClaimState.Working, f.controller.state.value)
        f.controller.add(); runCurrent(); assertEquals(1, f.posts.size)
        f.holdPost!!.complete(Unit); f.awaitChoiceFinished()
        assertEquals(listOf("claim-1"), f.posts.map { it.clientId }); assertTrue(f.snapshot().isEmpty())
    }

    @Test fun thirdOfFiveFailingLeavesRemainingOwnedQueueWithStableClientIds() = scenario { f ->
        (1L..5L).forEach { f.seed(it) }; f.signIn(); f.failPost = 3
        f.controller.add(); f.awaitChoiceFinished()
        assertEquals(listOf("claim-1", "claim-2", "claim-3"), f.posts.map { it.clientId })
        assertEquals(listOf("claim-3", "claim-4", "claim-5"), f.snapshot().map { it.client_id })
        assertTrue(f.snapshot().all { it.owner_user_id == "42" }); assertEquals(2, f.successful.size)
        assertEquals(42L, f.session.auth.value?.userId); assertNull(f.session.pendingAuth.value)
        assertFalse(f.controller.state.value is ClaimState.Ask)
        assertEquals(setOf(3L, 4L, 5L), f.local.files.keys)
        f.failPost = null; assertNull(f.repository.refresh())
        assertEquals(listOf("claim-1", "claim-2", "claim-3", "claim-3", "claim-4", "claim-5"), f.posts.map { it.clientId })
        assertEquals(5, f.successful.size); assertTrue(f.snapshot().isEmpty())
        assertEquals((1L..5L).map(f::request), f.successful.values.toList())
    }

    @Test fun deleteConfirmationCountsOnlyOfferedRowsAndCancelChangesNothing() = scenario { f ->
        f.seed(); f.seed(2, owner = 99); f.seed(3, owner = 42)
        val rows = f.snapshot(); f.signIn(); f.assertHeld(2)
        f.controller.askDelete(); runCurrent()
        assertEquals(ClaimState.ConfirmDelete(2), f.controller.state.value)
        assertNull(f.store.saved); assertEquals(rows, f.snapshot())
        f.controller.cancelDelete(); runCurrent(); f.assertHeld(2)
        assertEquals(rows, f.snapshot()); assertEquals(setOf(1L, 2L, 3L), f.local.files.keys)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun confirmedDeleteRemovesOnlyOfferedRowsAndPhotosBeforePublishingAuth() = scenario { f ->
        f.seed(); f.seed(2, owner = 99); f.seed(3, owner = 42)
        f.signIn(); f.controller.askDelete(); runCurrent(); f.controller.confirmDelete(); f.awaitChoiceFinished()
        assertEquals(listOf(3L), f.snapshot().map { it.local_id })
        assertEquals(setOf(3L), f.local.files.keys); assertEquals(setOf(3L), f.local.thumbs.keys)
        assertEquals(listOf(listOf(3L)), f.rowIdsAtPublication)
        assertEquals(listOf(setOf(3L)), f.photoIdsAtPublication)
        assertTrue(f.posts.isEmpty()); assertTrue(f.calls.none { it.method == HttpMethod.Delete })
        assertEquals(42L, f.store.saved?.userId); assertEquals(f.store.saved, f.session.auth.value)
        assertNull(f.session.pendingAuth.value); assertEquals(ClaimState.Hidden, f.controller.state.value)
    }

    private fun abandon(dismiss: Boolean) = scenario { f ->
        f.seed(); val rows = f.snapshot(); f.signIn(); f.assertHeld(1)
        if (dismiss) f.controller.dismiss() else f.controller.staySignedOut()
        f.awaitChoiceFinished()
        assertNull(f.session.auth.value); assertNull(f.session.pendingAuth.value); assertNull(f.store.saved)
        assertTrue(f.store.writes.isEmpty()); assertEquals(rows, f.snapshot())
        assertContentEquals(byteArrayOf(1, 2, 3), f.local.read(1)); assertTrue(f.posts.isEmpty())
        assertEquals(ClaimState.Hidden, f.controller.state.value)
        f.signIn(); f.assertHeld(1)
    }
    @Test fun staySignedOutKeepsRowsAndPromptsAtNextSignIn() = abandon(false)
    @Test fun dismissKeepsRowsAndPromptsAtNextSignIn() = abandon(true)

    @Test fun recreatedSessionDoesNotRestorePendingAuthAndOffersSamePersistedRows() = scenario { f ->
        f.seed(); f.signIn(); f.assertHeld(1); val rows = f.snapshot()
        f.restart()
        assertNull(f.session.auth.value); assertNull(f.session.pendingAuth.value); assertNull(f.store.saved)
        assertEquals(rows, f.snapshot()); assertContentEquals(byteArrayOf(1, 2, 3), f.local.read(1))
        f.signIn(Provider.APPLE); f.assertHeld(1)
    }

    @Test fun accountSwitchOffersUnclaimedAndPreviousAccountQueueAndClearsOnlySyncedCache() = scenario { f ->
        f.seed(); f.seed(2, owner = 99); f.seed(3, owner = 42)
        val previous = SeriesDto(900, "2026-09-01T00:00:00Z", "22lr", emptyList())
        f.db.seriesQueries.upsert(900, previous.timestamp, seriesJson.encodeToString(previous))
        f.db.seriesQueries.setLastSync("99", "2026-09-01T00:00:00Z"); f.synced.write(900, byteArrayOf(7))
        val rows = f.snapshot(); f.signIn(); f.assertHeld(2)
        assertEquals(rows, f.snapshot()); assertTrue(f.posts.isEmpty())
        f.controller.askDelete(); runCurrent(); f.controller.confirmDelete(); f.awaitChoiceFinished()
        // Isolate the account-switch wipe from queue draining: leave the current-account row offline.
        f.failPost = 1; f.repository.refresh()
        assertTrue(f.db.seriesQueries.selectAll().executeAsList().none { it.id == 900L })
        assertNull(f.synced.read(900)); assertEquals(listOf(3L), f.snapshot().map { it.local_id })
        assertContentEquals(byteArrayOf(3, 2, 3), f.local.read(3))
        assertTrue(f.posts.all { it.clientId == "claim-3" })
    }
}
