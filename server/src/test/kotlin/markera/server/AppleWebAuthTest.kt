package markera.server

import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.assertEquals
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
    }

    /** [appleWeb] null exercises the unconfigured case. */
    private fun appleTest(
        appleWeb: AppleWebConfig? = webConfig,
        identity: (String) -> Identity = { Identity("apple-sub-1", "ada@privaterelay.appleid.com") },
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val dbFile = File.createTempFile("markera-apple-test", ".db").also { it.delete(); it.deleteOnExit() }
        val imagesDir = File(dbFile.path + "-images").also { it.deleteOnExit() }
        application {
            markeraModule(
                Config(0, dbFile.path, null, null, false, imagesDir.path, null, null, appleWeb),
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

    private suspend fun HttpClient.callback(state: String = STATE, code: String = "apple-code") =
        get("/auth/apple/callback?code=$code&state=$state")

    private suspend fun HttpClient.claim(state: String, secret: String) =
        post("/auth/apple/claim") {
            contentType(ContentType.Application.Json)
            setBody(AppleClaimRequest(state, secret))
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
        assertEquals("$APPLE_DEEP_LINK?state=$STATE", redirect.headers[HttpHeaders.Location])

        val auth: AuthResponse = client.claim(STATE, SECRET).body()
        // The token is a real session: it reads the (empty) series list.
        assertEquals(HttpStatusCode.OK, client.get("/series") { bearerAuth(auth.token) }.status)
    }

    @Test
    fun aClaimWorksOnlyOnce() = appleTest { client ->
        client.callback()
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET).status)
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, SECRET).status)
    }

    @Test
    fun theWrongSecretCannotClaimSomeoneElsesLogin() = appleTest { client ->
        client.callback()
        assertEquals(HttpStatusCode.Unauthorized, client.claim(STATE, "b".repeat(64)).status)
        // The real owner can still claim it: a guess must not burn the login.
        assertEquals(HttpStatusCode.OK, client.claim(STATE, SECRET).status)
    }

    @Test
    fun anUnknownStateIsUnauthorized() = appleTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.claim(sha256("nobody"), "nobody").status)
    }

    @Test
    fun theSameAppleUserKeepsTheSameAccount() = appleTest { client ->
        val first: AuthResponse = client.callback().let { client.claim(STATE, SECRET).body() }
        val second: AuthResponse = client.callback().let { client.claim(STATE, SECRET).body() }
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
        assertEquals(HttpStatusCode.ServiceUnavailable, client.claim(STATE, SECRET).status)
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

    @Test
    fun anExpiredParkedLoginCannotBeClaimed() {
        val expired = PendingLogins(ttlMillis = -1)
        expired.park(STATE, AuthResponse("token", 1))
        assertEquals(null, expired.claim(STATE, SECRET))
    }
}
