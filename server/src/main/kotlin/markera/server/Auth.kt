package markera.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Who the provider says the caller is. Google tokens carry `name` and `email`, Apple's only `email`. */
data class Identity(val subject: String, val name: String?)

/** Verifies an RS256 provider ID token against a JWKS and returns its `sub` plus a display name. */
class TokenVerifier(
    private val jwks: JwkProvider,
    private val audience: String,
    private val issuers: List<String>,
) {
    /** @throws JWTVerificationException if the signature, issuer, audience or expiry is wrong. */
    fun identity(idToken: String): Identity {
        val decoded = JWT.decode(idToken)
        val key = try {
            jwks.get(decoded.keyId).publicKey as RSAPublicKey
        } catch (e: Exception) {
            throw JWTVerificationException("unknown key id ${decoded.keyId}", e)
        }
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(*issuers.toTypedArray()) // java-jwt: passes when the claim matches any of them
            .withAudience(audience)
            .build()
            .verify(idToken)
        val name = listOf("name", "email")
            .firstNotNullOfOrNull { verified.getClaim(it).asString()?.ifBlank { null } }
        return Identity(verified.subject, name)
    }
}

private fun cachedJwks(url: String): JwkProvider =
    JwkProviderBuilder(URI(url).toURL()).cached(10, 24, TimeUnit.HOURS).build()

fun googleVerifier(clientId: String) = TokenVerifier(
    cachedJwks("https://www.googleapis.com/oauth2/v3/certs"),
    clientId,
    listOf("https://accounts.google.com", "accounts.google.com"),
)

fun appleVerifier(bundleId: String) = TokenVerifier(
    cachedJwks("https://appleid.apple.com/auth/keys"),
    bundleId,
    listOf("https://appleid.apple.com"),
)
