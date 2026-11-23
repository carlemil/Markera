package markera.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant

/** Who the provider says the caller is. [name] is the `name` claim, else the part of `email` before the `@`. */
data class Identity(val subject: String, val name: String?)

/** Reads a provider's whole published key set (JWKS). */
fun interface KeySetSource {
    fun fetch(): List<Jwk>
}

/**
 * A provider's RSA signing keys by key id. The whole set is read at most once per [minRefreshInterval],
 * whichever key id is asked for: a key id missing from a set read within the interval is answered with
 * null straight away. The interval runs from the last read attempt (failed ones included), so a key the
 * provider adds is found on the first lookup once the interval has passed. A set older than [maxAge] is
 * read again even on a hit, so retired keys drop out. Cached lookups never wait for a read in progress.
 */
class JwksKeySet(
    private val source: KeySetSource,
    private val minRefreshInterval: Duration = Duration.ofSeconds(60),
    private val maxAge: Duration = Duration.ofHours(24),
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var keys: Map<String, RSAPublicKey> = emptyMap()
    @Volatile private var lastAttempt: Instant? = null
    @Volatile private var lastSuccess: Instant? = null
    private val refreshLock = Any()

    fun key(kid: String): RSAPublicKey? {
        val cached = keys[kid]
        if (cached != null && !stale()) return cached
        synchronized(refreshLock) {
            // Another caller may have refreshed while this one waited.
            val fresh = keys[kid]
            if (fresh != null && !stale()) return fresh
            val last = lastAttempt
            val now = clock()
            if (last == null || Duration.between(last, now) >= minRefreshInterval) {
                lastAttempt = now
                try {
                    keys = source.fetch().mapNotNull(::usable).toMap()
                    lastSuccess = now
                } catch (_: Exception) {
                    // Keep the keys we have; the next read waits for the interval like any other.
                }
            }
            return keys[kid]
        }
    }

    private fun stale(): Boolean {
        val success = lastSuccess ?: return true
        return Duration.between(success, clock()) > maxAge
    }

    /** The key id and RSA key of [jwk], or null for an entry that is not a usable RSA key. */
    private fun usable(jwk: Jwk): Pair<String, RSAPublicKey>? {
        val kid = jwk.id ?: return null
        if (jwk.type != "RSA") return null
        val key = try {
            jwk.publicKey as? RSAPublicKey
        } catch (_: Exception) {
            null
        } ?: return null
        return kid to key
    }
}

/** Verifies an RS256 provider ID token against a JWKS and returns its `sub` plus a display name. */
class TokenVerifier(
    private val keys: JwksKeySet,
    /** Accepted `aud` values; any one matching is enough (native iOS carries the bundle id, Apple's web flow the Services ID). */
    private val audiences: List<String>,
    private val issuers: List<String>,
) {
    /** @throws JWTVerificationException if the signature, issuer, audience or expiry is wrong. */
    fun identity(idToken: String): Identity {
        val decoded = JWT.decode(idToken)
        // Cheap checks on the unverified claims first, so only plausible tokens reach the key set.
        // The full verification below stays the authority.
        if (decoded.algorithm != "RS256") throw JWTVerificationException("unsupported algorithm")
        val kid = decoded.keyId ?: throw JWTVerificationException("missing key id")
        if (decoded.issuer !in issuers) throw JWTVerificationException("wrong issuer")
        if (decoded.audience.orEmpty().none { it in audiences }) throw JWTVerificationException("wrong audience")
        val expiresAt = decoded.expiresAtAsInstant ?: throw JWTVerificationException("missing expiry")
        if (!expiresAt.isAfter(Instant.now())) throw JWTVerificationException("token expired")

        val key = keys.key(kid) ?: throw JWTVerificationException("unknown key id")
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(*issuers.toTypedArray()) // java-jwt: passes when the claim matches any of them
            // withAudience() would demand *all* of them; this passes on any one.
            .withAnyOfAudience(*audiences.toTypedArray())
            .withClaimPresence("sub") // without it `verified.subject` is null and the login a 500
            .build()
            .verify(idToken)
        // Never the full address: only its local part is kept, and only when there is no name.
        val name = verified.getClaim("name").asString()?.ifBlank { null }
            ?: verified.getClaim("email").asString()?.substringBefore('@')?.ifBlank { null }
        return Identity(verified.subject, name)
    }
}

private const val JWKS_CONNECT_TIMEOUT_MS = 3_000
private const val JWKS_READ_TIMEOUT_MS = 5_000

/** The key set published at [url], read with explicit connect and read timeouts. */
private fun remoteKeySet(url: String): JwksKeySet {
    val provider = UrlJwkProvider(URI(url).toURL(), JWKS_CONNECT_TIMEOUT_MS, JWKS_READ_TIMEOUT_MS)
    return JwksKeySet({ provider.all })
}

fun googleVerifier(clientId: String) = TokenVerifier(
    remoteKeySet("https://www.googleapis.com/oauth2/v3/certs"),
    listOf(clientId),
    listOf("https://accounts.google.com", "accounts.google.com"),
)

/** [audiences] is the iOS bundle id, the Apple Services ID (the Android web flow), or both. */
fun appleVerifier(audiences: List<String>) = TokenVerifier(
    remoteKeySet("https://appleid.apple.com/auth/keys"),
    audiences,
    listOf("https://appleid.apple.com"),
)
