package markera.server

import com.auth0.jwk.Jwk
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifier tests against locally generated key pairs and an in-memory key set: no network. */
class TokenVerifierTest {

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val keyPair = newKeyPair()
    private val algorithm = Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey)

    private fun jwk(kid: String, pair: KeyPair): Jwk {
        val publicKey = pair.public as RSAPublicKey
        val urlSafe = Base64.getUrlEncoder().withoutPadding()
        return Jwk.fromValues(
            mapOf(
                "kty" to "RSA",
                "kid" to kid,
                "alg" to "RS256",
                "use" to "sig",
                "n" to urlSafe.encodeToString(publicKey.modulus.toByteArray()),
                "e" to urlSafe.encodeToString(publicKey.publicExponent.toByteArray()),
            )
        )
    }

    /** A key set source that counts how often it is read; [keys] can change to stand in for a rotation. */
    private class FakeKeySetSource(var keys: List<Jwk>) : KeySetSource {
        var fetches = 0
        var failing = false
        override fun fetch(): List<Jwk> {
            fetches++
            if (failing) throw IOException("unreachable")
            return keys
        }
    }

    /** A hand-moved clock for the refresh interval. */
    private class FakeClock {
        var now: Instant = Instant.parse("2026-11-23T12:00:00Z")
        fun advance(d: Duration) { now = now.plus(d) }
    }

    private val source = FakeKeySetSource(listOf(jwk("test-key", keyPair)))
    private val clock = FakeClock()
    private val interval = Duration.ofSeconds(60)
    private val keySet = JwksKeySet(source, minRefreshInterval = interval, maxAge = Duration.ofHours(24), clock = { clock.now })

    private val verifier = TokenVerifier(keySet, listOf("client-id"), listOf("https://accounts.google.com", "accounts.google.com"))

    private fun token(
        issuer: String = "https://accounts.google.com",
        audience: String = "client-id",
        expiresAt: Instant = Instant.now().plusSeconds(600),
        name: String? = null,
        email: String? = null,
        kid: String = "test-key",
        signer: Algorithm = algorithm,
    ): String = JWT.create()
        .withKeyId(kid)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject("subject-123")
        .withExpiresAt(Date.from(expiresAt))
        .apply { name?.let { withClaim("name", it) }; email?.let { withClaim("email", it) } }
        .sign(signer)

    @Test
    fun validTokenYieldsSubject() {
        assertEquals(Identity("subject-123", null), verifier.identity(token()))
        assertEquals(Identity("subject-123", null), verifier.identity(token(issuer = "accounts.google.com")))
    }

    @Test
    fun nameComesFromTheNameClaimThenTheEmailLocalPart() {
        val google = token(name = "Ada Lovelace", email = "ada@example.com")
        assertEquals(Identity("subject-123", "Ada Lovelace"), verifier.identity(google))
        // Apple sends no name; the full address is never kept.
        assertEquals(Identity("subject-123", "ada"), verifier.identity(token(email = "ada@example.com")))
        assertEquals(Identity("subject-123", "ada"), verifier.identity(token(name = " ", email = "ada@example.com")))
        assertEquals(Identity("subject-123", null), verifier.identity(token(email = "@example.com")))
    }

    @Test
    fun wrongAudienceFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity(token(audience = "someone-elses-app")) }
    }

    /** Apple's two audiences: the iOS bundle id natively, the Services ID from the browser flow. */
    @Test
    fun anyOfSeveralAudiencesPasses() {
        val both = TokenVerifier(keySet, listOf("bundle-id", "services-id"), listOf("https://accounts.google.com"))
        assertEquals("subject-123", both.identity(token(audience = "bundle-id")).subject)
        assertEquals("subject-123", both.identity(token(audience = "services-id")).subject)
        assertFailsWith<JWTVerificationException> { both.identity(token(audience = "someone-elses-app")) }
    }

    @Test
    fun wrongIssuerFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity(token(issuer = "https://elsewhere.example")) }
    }

    @Test
    fun expiredTokenFails() {
        assertFailsWith<JWTVerificationException> {
            verifier.identity(token(expiresAt = Instant.now().minusSeconds(60)))
        }
    }

    @Test
    fun tokenSignedByAnotherKeyFails() {
        val other = newKeyPair()
        val otherSigner = Algorithm.RSA256(other.public as RSAPublicKey, other.private as RSAPrivateKey)
        assertFailsWith<JWTVerificationException> { verifier.identity(token(signer = otherSigner)) }
    }

    @Test
    fun tokenWithoutSubjectFails() {
        val anonymous = JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://accounts.google.com")
            .withAudience("client-id")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(algorithm)
        assertFailsWith<JWTVerificationException> { verifier.identity(anonymous) }
    }

    @Test
    fun garbageTokenFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity("not-a-jwt") }
        assertEquals(0, source.fetches)
    }

    @Test
    fun claimChecksRunBeforeAnyKeyFetch() {
        val hmac = JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://accounts.google.com")
            .withAudience("client-id")
            .withSubject("subject-123")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(Algorithm.HMAC256("secret"))
        val noKid = JWT.create()
            .withIssuer("https://accounts.google.com")
            .withAudience("client-id")
            .withSubject("subject-123")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(algorithm)
        val noExpiry = JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://accounts.google.com")
            .withAudience("client-id")
            .withSubject("subject-123")
            .sign(algorithm)
        val rejected = listOf(
            token(issuer = "https://elsewhere.example", kid = "k1"),
            token(audience = "someone-elses-app", kid = "k2"),
            token(expiresAt = Instant.now().minusSeconds(60), kid = "k3"),
            hmac,
            noKid,
            noExpiry,
        )
        for (t in rejected) assertFailsWith<JWTVerificationException> { verifier.identity(t) }
        assertEquals(0, source.fetches)
    }

    @Test
    fun unknownKeyIdsRefetchAtMostOncePerInterval() {
        verifier.identity(token()) // first use loads the set
        assertEquals(1, source.fetches)
        repeat(50) { i ->
            assertFailsWith<JWTVerificationException> { verifier.identity(token(kid = "missing-$i")) }
        }
        assertEquals(1, source.fetches) // the set was just read, so no miss reads it again
        clock.advance(interval)
        repeat(50) { i ->
            assertFailsWith<JWTVerificationException> { verifier.identity(token(kid = "absent-$i")) }
        }
        assertEquals(2, source.fetches) // the first miss after the interval read it, the rest waited
        // A cached key keeps working throughout.
        assertEquals("subject-123", verifier.identity(token()).subject)
        assertEquals(2, source.fetches)
    }

    @Test
    fun rotatedKeyVerifiesOnceTheIntervalHasPassed() {
        verifier.identity(token())
        assertEquals(1, source.fetches)

        val rotated = newKeyPair()
        val rotatedSigner = Algorithm.RSA256(rotated.public as RSAPublicKey, rotated.private as RSAPrivateKey)
        source.keys = listOf(jwk("test-key", keyPair), jwk("next-key", rotated))
        val next = token(kid = "next-key", signer = rotatedSigner)

        // Within the interval of the last fetch the new key id is not looked up again.
        clock.advance(Duration.ofSeconds(10))
        assertFailsWith<JWTVerificationException> { verifier.identity(next) }
        assertEquals(1, source.fetches)
        // Repeated lookups just before the interval ends do not push the next refresh further out.
        clock.advance(interval.minusSeconds(11))
        repeat(5) { assertFailsWith<JWTVerificationException> { verifier.identity(token(kid = "other-$it")) } }
        assertEquals(1, source.fetches)

        clock.advance(Duration.ofSeconds(1)) // exactly one interval after the first fetch
        assertEquals("subject-123", verifier.identity(next).subject)
        assertEquals(2, source.fetches)
        assertEquals("subject-123", verifier.identity(token()).subject)
        assertEquals(2, source.fetches)
    }

    @Test
    fun retiredKeyIsDroppedAfterMaxAge() {
        verifier.identity(token())
        source.keys = emptyList()
        clock.advance(Duration.ofHours(25))
        assertFailsWith<JWTVerificationException> { verifier.identity(token()) }
        assertEquals(2, source.fetches)
    }

    @Test
    fun failedFetchCountsTowardTheIntervalAndKeepsKnownKeys() {
        verifier.identity(token())
        source.failing = true
        clock.advance(interval)
        repeat(5) { assertFailsWith<JWTVerificationException> { verifier.identity(token(kid = "missing-$it")) } }
        assertEquals(2, source.fetches)
        assertEquals("subject-123", verifier.identity(token()).subject)
    }
}
