package markera.server

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.client.call.body
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Apple browser flow (how Android signs in with Apple), with the token exchange stubbed out. */
class AppleWebAuthTest {

    private companion object {
        /** A real P-256 key, so the client secret is actually signed rather than mocked. */
        val p8: String = Base64.getMimeEncoder().encodeToString(
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private.encoded
        )

        val webConfig = AppleWebConfig(
            servicesId = "se.kjellstrand.markera.web",
            teamId = "TEAM123456",
            keyId = "KEY1234567",
            privateKey = "-----BEGIN PRIVATE KEY-----\n$p8\n-----END PRIVATE KEY-----",
            publicUrl = "https://markera.example/",
        )

        val SECRET = "a".repeat(64)
        val STATE = sha256(SECRET)

        /** Made-up fingerprints of the right shape; real ones come from Play Console and the debug keystore. */
        val FP_PLAY = List(32) { "%02X".format(it) }.joinToString(":")
        val FP_DEBUG = List(32) { "%02X".format(255 - it) }.joinToString(":")
    }

    /** [appleWeb] null exercises the unconfigured case. */
    private fun appleTest(
        appleWeb: AppleWebConfig? = webConfig,
        identity: (String) -> Identity = { Identity("apple-sub-1", "ada@privaterelay.appleid.com") },
        certFingerprints: List<String> = emptyList(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val dbFile = File.createTempFile("markera-apple-test", ".db").also { it.delete(); it.deleteOnExit() }
        val imagesDir = File(dbFile.path + "-images").also { it.deleteOnExit() }
        application {
            markeraModule(
                Config(0, dbFile.path, null, null, false, imagesDir.path, null, null, appleWeb, androidCertSha256 = certFingerprints),
                Db(dbFile.path),
                appleCodeIdentity = identity,
            )
        }
        // The callback answers with a `markera://` redirect; following it is not the client's business.
        val client = createClient {
            followRedirects = false
            install(ClientContentNegotiation) { json() }
        }
        block(client)
    }

    /** A whole round: `/start` issues [state], then Apple's redirect lands on the callback. */
    private suspend fun HttpClient.callback(state: String = STATE, code: String = "apple-code") =
        // `/start` takes the plain hash; the https return travels as `return=https` and comes back as a suffix.
        get(
            if (state.endsWith(APPLE_HTTPS_STATE_SUFFIX))
                "/auth/apple/start?state=${state.removeSuffix(APPLE_HTTPS_STATE_SUFFIX)}&return=https"
            else "/auth/apple/start?state=$state",
        ).let { rawCallback(state, code) }

    /** The callback alone, without a `/start` before it. */
    private suspend fun HttpClient.rawCallback(state: String = STATE, code: String = "apple-code") =
        get("/auth/apple/callback?code=$code&state=$state")

    /** Runs the callback and returns the nonce its deep link carried. */
    private suspend fun HttpClient.signIn(state: String = STATE): String =
        assertNotNull(Url(assertNotNull(callback(state).headers[HttpHeaders.Location])).parameters["nonce"])

    private suspend fun HttpClient.claim(state: String, secret: String, nonce: String) =
        post("/auth/apple/claim") {
            contentType(ContentType.Application.Json)
            setBody(AppleClaimRequest(state, secret, nonce))
        }

    @Test
    fun startRedirectsToApple() = appleTest { client ->
        val response = client.get("/auth/apple/start?state=$STATE")
        assertEquals(HttpStatusCode.Found, response.status)
        val location = Url(assertNotNull(response.headers[HttpHeaders.Location]))
        assertEquals("appleid.apple.com", location.host)
        assertEquals("/auth/authorize", location.encodedPath)
        assertEquals(webConfig.servicesId, location.parameters["client_id"])
        // Character-for-character what the Services ID's Return URL must be.
        assertEquals("https://markera.example/auth/apple/callback", location.parameters["redirect_uri"])
        assertEquals("code", location.parameters["response_type"])
        assertEquals(STATE, location.parameters["state"])
    }

    @Test
    fun startRejectsAStateThatIsNotAHash() = appleTest { client ->
        assertEquals(HttpStatusCode.BadRequest, client.get("/auth/apple/start?state=../evil").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/auth/apple/start").status)
    }

    @Test
    fun callbackParksASessionTheAppThenClaims() = appleTest { client ->
        val redirect = client.callback()
        assertEquals(HttpStatusCode.Found, redirect.status)
        val location = assertNotNull(redirect.headers[HttpHeaders.Location])
        assertTrue(location.startsWith("$APPLE_DEEP_LINK?state=$STATE&nonce="), location)
        val nonce = assertNotNull(Url(location).parameters["nonce"])
        assertTrue(APPLE_STATE_SHAPE.matches(nonce), nonce)

        val auth: AuthResponse = client.claim(STATE, SECRET, nonce).body()
        // The token is a real session: it reads the (empty) series list.
        assertEquals(HttpStatusCode.OK, client.get("/series") { bearerAuth(auth.token) }.status)
    }

    @Test
    fun aClaimWorksOnlyOnce() = appleTest { client ->
        val nonce = client.signIn()
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET, nonce).status)
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, SECRET, nonce).status)
    }

    /**
     * The attacker chose the secret, mailed the victim a real start link for its state, and the victim
     * signed in. Without the nonce, which went only to the victim's browser, the login stays unclaimable.
     */
    @Test
    fun whoeverChoseTheStateCannotClaimAnotherPersonsLogin() = appleTest { client ->
        val victimsNonce = client.signIn()
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, SECRET, "").status)
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, SECRET, "0".repeat(64)).status)
        // Nor does a wrong guess burn it for the device that holds the nonce.
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET, victimsNonce).status)
    }

    @Test
    fun anAppTooOldToSendANonceIsUnauthorizedNotMalformed() = appleTest { client ->
        client.signIn()
        val response = client.post("/auth/apple/claim") {
            contentType(ContentType.Application.Json)
            setBody("""{"state":"$STATE","secret":"$SECRET"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun theWrongSecretCannotClaimSomeoneElsesLogin() = appleTest { client ->
        val nonce = client.signIn()
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, "b".repeat(64), nonce).status)
        // The real owner can still claim it: a guess must not burn the login.
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET, nonce).status)
    }

    @Test
    fun anUnknownStateIsUnauthorized() = appleTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.claim(sha256("nobody"), "nobody", "").status)
    }

    @Test
    fun theSameAppleUserKeepsTheSameAccount() = appleTest { client ->
        val first: AuthResponse = client.claim(STATE, SECRET, client.signIn()).body()
        val second: AuthResponse = client.claim(STATE, SECRET, client.signIn()).body()
        assertEquals(first.userId, second.userId)
    }

    @Test
    fun cancellingAtAppleComesBackAsACancelledDeepLink() = appleTest { client ->
        val response = client.get("/auth/apple/callback?error=user_cancelled_authorize&state=$STATE")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("$APPLE_DEEP_LINK?state=$STATE&error=cancelled", response.headers[HttpHeaders.Location])
    }

    @Test
    fun aFailedExchangeIsUnauthorized() = appleTest(identity = { error("apple said no") }) { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.callback().status)
    }

    @Test
    fun unconfiguredRoutesAnswer503() = appleTest(appleWeb = null) { client ->
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/auth/apple/start?state=$STATE").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.callback().status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.claim(STATE, SECRET, "").status)
    }

    @Test
    fun theClientSecretIsAnEs256JwtForApple() {
        val jwt = JWT.decode(appleClientSecret(webConfig))
        assertEquals("ES256", jwt.algorithm)
        assertEquals(webConfig.keyId, jwt.keyId)
        assertEquals(webConfig.teamId, jwt.issuer)
        assertEquals(webConfig.servicesId, jwt.subject)
        assertEquals(listOf("https://appleid.apple.com"), jwt.audience)
        assertTrue(jwt.expiresAtAsInstant.isAfter(jwt.issuedAtAsInstant))
    }

    // --- The https return (issue #48): new apps ask for it at start, old apps keep markera://. ---

    @Test
    fun aNewAppsStartCarriesTheHttpsReturnInsideTheStateSentToApple() = appleTest { client ->
        val location = Url(assertNotNull(client.get("/auth/apple/start?state=$STATE&return=https").headers[HttpHeaders.Location]))
        assertEquals("$STATE$APPLE_HTTPS_STATE_SUFFIX", location.parameters["state"])
    }

    @Test
    fun anUnknownReturnValueKeepsTheCustomScheme() = appleTest { client ->
        val location = Url(assertNotNull(client.get("/auth/apple/start?state=$STATE&return=ftp").headers[HttpHeaders.Location]))
        assertEquals(STATE, location.parameters["state"])
    }

    @Test
    fun aNewAppsCallbackRedirectsToTheHttpsReturnAndTheLoginIsClaimable() = appleTest { client ->
        val redirect = client.callback(state = "$STATE$APPLE_HTTPS_STATE_SUFFIX")
        assertEquals(HttpStatusCode.Found, redirect.status)
        val location = assertNotNull(redirect.headers[HttpHeaders.Location])
        // The suffix is gone: the app compares the plain hex, and the login is parked under it.
        assertTrue(location.startsWith("https://markera.example$APPLE_DONE_PATH?state=$STATE&nonce="), location)
        val nonce = assertNotNull(Url(location).parameters["nonce"])
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET, nonce).status)
    }

    @Test
    fun anOldAppsCallbackStillRedirectsToTheCustomScheme() = appleTest { client ->
        val location = assertNotNull(client.callback().headers[HttpHeaders.Location])
        assertTrue(location.startsWith("markera://auth/apple?state=$STATE&nonce="), location)
    }

    @Test
    fun aNewAppsCancelComesBackOnTheHttpsReturn() = appleTest { client ->
        val response = client.get("/auth/apple/callback?error=user_cancelled_authorize&state=$STATE$APPLE_HTTPS_STATE_SUFFIX")
        assertEquals("https://markera.example$APPLE_DONE_PATH?state=$STATE&error=cancelled", response.headers[HttpHeaders.Location])
    }

    @Test
    fun theCallbackRejectsAnyOtherSuffix() = appleTest { client ->
        assertEquals(HttpStatusCode.BadRequest, client.callback(state = "$STATE-evil").status)
    }

    @Test
    fun theDonePageHandsTheParametersToTheAppByHand() = appleTest { client ->
        val nonce = "b".repeat(64)
        val response = client.get("$APPLE_DONE_PATH?state=$STATE&nonce=$nonce")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val html = response.body<String>()
        assertTrue("href=\"markera://auth/apple?state=$STATE&amp;nonce=$nonce\"" in html, html)
        assertTrue("intent://auth/apple?state=$STATE&amp;nonce=$nonce#Intent;scheme=markera;package=se.kjellstrand.markera;end" in html, html)
    }

    @Test
    fun theDonePageCarriesACancel() = appleTest { client ->
        val html = client.get("$APPLE_DONE_PATH?state=$STATE&error=cancelled").body<String>()
        assertTrue("markera://auth/apple?state=$STATE&amp;error=cancelled" in html, html)
    }

    @Test
    fun theDonePageEchoesNothingUnchecked() = appleTest { client ->
        assertEquals(HttpStatusCode.BadRequest, client.get("$APPLE_DONE_PATH?state=$STATE&nonce=%3Cscript%3E").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$APPLE_DONE_PATH?state=%3Cscript%3E&nonce=$STATE").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$APPLE_DONE_PATH?state=$STATE&error=%3Cb%3E").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$APPLE_DONE_PATH?state=$STATE").status)
    }

    @Test
    fun assetLinksIs404WithoutFingerprints() = appleTest { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/assetlinks.json").status)
    }

    @Test
    fun assetLinksListsThePackageAndEveryFingerprint() = appleTest(certFingerprints = listOf(FP_PLAY, FP_DEBUG)) { client ->
        val response = client.get("/.well-known/assetlinks.json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val statements = Json.parseToJsonElement(response.body<String>()).jsonArray
        assertEquals(1, statements.size)
        val statement = statements[0].jsonObject
        assertEquals(
            listOf("delegate_permission/common.handle_all_urls"),
            statement["relation"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val target = statement["target"]!!.jsonObject
        assertEquals("android_app", target["namespace"]!!.jsonPrimitive.content)
        assertEquals("se.kjellstrand.markera", target["package_name"]!!.jsonPrimitive.content)
        assertEquals(listOf(FP_PLAY, FP_DEBUG), target["sha256_cert_fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun fingerprintsAreTrimmedUpperCasedAndChecked() {
        assertEquals(emptyList(), parseCertFingerprints(null))
        assertEquals(emptyList(), parseCertFingerprints(" , "))
        assertEquals(listOf(FP_PLAY, FP_DEBUG), parseCertFingerprints(" ${FP_PLAY.lowercase()} ,$FP_DEBUG"))
        assertFailsWith<IllegalArgumentException> { parseCertFingerprints("AB:CD") }
        // A SHA-1 (20 bytes) is the classic mix-up.
        assertFailsWith<IllegalArgumentException> { parseCertFingerprints(List(20) { "AB" }.joinToString(":")) }
    }

    @Test
    fun aCallbackWithoutAStartIsRefusedBeforeAppleIsAsked() {
        var exchanges = 0
        appleTest(identity = { exchanges++; Identity("apple-sub-1", null) }) { client ->
            assertEquals(HttpStatusCode.BadRequest, client.rawCallback().status)
            assertEquals(HttpStatusCode.BadRequest, client.rawCallback(sha256("never started")).status)
            assertEquals(0, exchanges)
        }
    }

    @Test
    fun aStartedStateIsGoodForOneCallback() {
        var exchanges = 0
        appleTest(identity = { exchanges++; Identity("apple-sub-1", null) }) { client ->
            assertEquals(HttpStatusCode.Found, client.callback().status)
            assertEquals(HttpStatusCode.BadRequest, client.rawCallback().status)
            assertEquals(1, exchanges)
        }
    }

    @Test
    fun anIssuedStateExpires() {
        var now = 0L
        val states = IssuedStates(ttlMillis = 1000, clock = { now })
        states.issue(STATE)
        now = 1001
        assertEquals(false, states.consume(STATE))
        states.issue(STATE)
        assertEquals(true, states.consume(STATE))
        assertEquals(false, states.consume(STATE))
    }

    @Test
    fun theCallbackIsThrottledPerClientIp() = appleTest { client ->
        repeat(MAX_APPLE_WEB_PER_WINDOW) {
            client.get("/auth/apple/callback?error=x&state=$STATE") { header("X-Forwarded-For", "203.0.113.7") }
        }
        val refused = client.get("/auth/apple/callback?error=x&state=$STATE") { header("X-Forwarded-For", "203.0.113.7") }
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertNotNull(refused.headers[HttpHeaders.RetryAfter])
        // Another sender is unaffected.
        val other = client.get("/auth/apple/callback?error=x&state=$STATE") { header("X-Forwarded-For", "203.0.113.8") }
        assertEquals(HttpStatusCode.Found, other.status)
    }

    @Test
    fun anExpiredParkedLoginCannotBeClaimed() {
        val expired = PendingLogins(ttlMillis = -1)
        val nonce = expired.park(STATE, 1)
        assertEquals(null, expired.claim(STATE, SECRET, nonce))
    }
}
