package markera.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifier tests against a locally generated key pair — no network. */
class TokenVerifierTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey
    private val algorithm = Algorithm.RSA256(publicKey, privateKey)

    private val jwks = JwkProvider { keyId ->
        val urlSafe = Base64.getUrlEncoder().withoutPadding()
        Jwk.fromValues(
            mapOf(
                "kty" to "RSA",
                "kid" to keyId,
                "alg" to "RS256",
                "use" to "sig",
                "n" to urlSafe.encodeToString(publicKey.modulus.toByteArray()),
                "e" to urlSafe.encodeToString(publicKey.publicExponent.toByteArray()),
            )
        )
    }

    private val verifier = TokenVerifier(jwks, "client-id", listOf("https://accounts.google.com", "accounts.google.com"))

    private fun token(
        issuer: String = "https://accounts.google.com",
        audience: String = "client-id",
        expiresAt: Instant = Instant.now().plusSeconds(600),
        name: String? = null,
        email: String? = null,
    ): String = JWT.create()
        .withKeyId("test-key")
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject("subject-123")
        .withExpiresAt(Date.from(expiresAt))
        .apply { name?.let { withClaim("name", it) }; email?.let { withClaim("email", it) } }
        .sign(algorithm)

    @Test
    fun validTokenYieldsSubject() {
        assertEquals(Identity("subject-123", null), verifier.identity(token()))
        assertEquals(Identity("subject-123", null), verifier.identity(token(issuer = "accounts.google.com")))
    }

    @Test
    fun nameComesFromTheNameClaimThenEmail() {
        val google = token(name = "Ada Lovelace", email = "ada@example.com")
        assertEquals(Identity("subject-123", "Ada Lovelace"), verifier.identity(google))
        // Apple sends no name.
        assertEquals(Identity("subject-123", "ada@example.com"), verifier.identity(token(email = "ada@example.com")))
        assertEquals(Identity("subject-123", "ada@example.com"), verifier.identity(token(name = " ", email = "ada@example.com")))
    }

    @Test
    fun wrongAudienceFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity(token(audience = "someone-elses-app")) }
    }

    @Test
    fun wrongIssuerFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity(token(issuer = "https://evil.example")) }
    }

    @Test
    fun expiredTokenFails() {
        assertFailsWith<JWTVerificationException> {
            verifier.identity(token(expiresAt = Instant.now().minusSeconds(60)))
        }
    }

    @Test
    fun tokenSignedByAnotherKeyFails() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forged = JWT.create()
            .withKeyId("test-key")
            .withIssuer("https://accounts.google.com")
            .withAudience("client-id")
            .withSubject("subject-123")
            .withExpiresAt(Date.from(Instant.now().plusSeconds(600)))
            .sign(Algorithm.RSA256(other.public as RSAPublicKey, other.private as RSAPrivateKey))
        assertFailsWith<JWTVerificationException> { verifier.identity(forged) }
    }

    @Test
    fun garbageTokenFails() {
        assertFailsWith<JWTVerificationException> { verifier.identity("not-a-jwt") }
    }
}
