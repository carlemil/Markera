package markera.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple's *web* sign-in, which is how Android reaches Sign in with Apple: there is no Apple SDK for
 * Android, and the flow's client secret (an ES256 JWT) must never ship inside an APK. So the app opens
 * a browser here, this server talks to Apple, and the app claims the session afterwards.
 *
 * All five values or none — [Config.appleWeb] is null otherwise and the routes answer 503.
 */
data class AppleWebConfig(
    /** Apple *Services ID*, not the bundle id; its Primary App ID must be the iOS bundle id or the `sub` differs. */
    val servicesId: String,
    val teamId: String,
    val keyId: String,
    /** The `.p8` key, with or without its PEM header. */
    val privateKey: String,
    /** This server's public origin, e.g. `https://markera.duckdns.org`. */
    val publicUrl: String,
) {
    /** Character-for-character what the Services ID's Return URL must be: Apple string-matches it. */
    val redirectUri: String get() = "${publicUrl.trimEnd('/')}/auth/apple/callback"
}

/**
 * Where the callback sends the browser so the app wakes up again — for app versions that predate the
 * https return. Any app can register this scheme, so it may never reach Markera (issue #48).
 */
const val APPLE_DEEP_LINK = "markera://auth/apple"

/**
 * The https return: a verified Android App Link on [AppleWebConfig.publicUrl] (see `/.well-known/assetlinks.json`),
 * which only Markera can open. When the app does not catch it, the browser loads it and gets [appleDonePage].
 */
const val APPLE_DONE_PATH = "/auth/apple/done"

/** What the app sends as `state`: hex sha256 of its secret. Echoed into a `Location`, so it is validated. */
val APPLE_STATE_SHAPE = Regex("[0-9a-f]{64}")

/**
 * How the browser gets back to the app after Apple. The app picks it at `/auth/apple/start` with
 * `return=https`; Apple echoes nothing but `state` and `code` to the callback, so the choice rides inside
 * the `state` sent to Apple as [APPLE_HTTPS_STATE_SUFFIX]. That keeps the server stateless (a restart
 * between start and callback changes nothing) and makes both deploy orders safe: an old app never sends the
 * parameter and keeps getting `markera://`, and a new app on an old server has it ignored and also gets
 * `markera://`, which its kept custom-scheme intent-filter still catches. The suffix is stripped before the
 * state is parked or echoed, so the claim and the app's own state check see exactly the hex they always did.
 */
enum class AppleReturnMode { CUSTOM_SCHEME, HTTPS }

/** The `/auth/apple/start` query parameter (`return=https`) by which an app asks for the https return. */
const val APPLE_RETURN_PARAM = "return"

/** Appended to the `state` sent to Apple when the app asked for the https return. */
const val APPLE_HTTPS_STATE_SUFFIX = "-https"

/** The `state` Apple hands the callback: the app's hex hash, plus the suffix when it asked for https. */
private val APPLE_CALLBACK_STATE_SHAPE = Regex("([0-9a-f]{64})($APPLE_HTTPS_STATE_SUFFIX)?")

/** The app's `state` and return mode from the `state` Apple echoed, or null when it has neither shape. */
fun parseAppleCallbackState(raw: String?): Pair<String, AppleReturnMode>? {
    val match = raw?.let { APPLE_CALLBACK_STATE_SHAPE.matchEntire(it) } ?: return null
    val mode = if (match.groupValues[2].isEmpty()) AppleReturnMode.CUSTOM_SCHEME else AppleReturnMode.HTTPS
    return match.groupValues[1] to mode
}

/** The `state` to send Apple for the app's [state] and chosen [mode]. */
fun appleStateForApple(state: String, mode: AppleReturnMode): String =
    if (mode == AppleReturnMode.HTTPS) state + APPLE_HTTPS_STATE_SUFFIX else state

/**
 * Where the callback sends the browser: [query] (already-validated `state=…&nonce=…` or `state=…&error=…`)
 * on the custom scheme, or on [APPLE_DONE_PATH] of the public origin.
 */
fun appleReturnUrl(config: AppleWebConfig, mode: AppleReturnMode, query: String): String = when (mode) {
    AppleReturnMode.CUSTOM_SCHEME -> "$APPLE_DEEP_LINK?$query"
    AppleReturnMode.HTTPS -> "${config.publicUrl.trimEnd('/')}$APPLE_DONE_PATH?$query"
}

/** A SHA-256 certificate fingerprint as `assetlinks.json` writes it: 32 upper-case hex pairs joined by `:`. */
private val CERT_SHA256_SHAPE = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")

/**
 * `ANDROID_CERT_SHA256`: comma-separated fingerprints, trimmed and upper-cased. A malformed one fails startup
 * rather than publishing a statement Android would silently never match.
 */
fun parseCertFingerprints(raw: String?): List<String> =
    raw.orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.onEach {
        require(CERT_SHA256_SHAPE.matches(it)) { "ANDROID_CERT_SHA256: not a SHA-256 fingerprint (AA:BB:…, 32 bytes): $it" }
    }

@Serializable
private data class AssetLinkTarget(
    val namespace: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("sha256_cert_fingerprints") val fingerprints: List<String>,
)

@Serializable
private data class AssetLinkStatement(val relation: List<String>, val target: AssetLinkTarget)

/**
 * The Digital Asset Links statement that lets Android verify the [APPLE_DONE_PATH] App Link for
 * [packageName] signed with any of [fingerprints]: one per signing key in use (Play's app-signing key,
 * plus the upload/debug key for builds installed outside Play).
 */
fun assetLinksJson(packageName: String, fingerprints: List<String>): String = Json.encodeToString(
    ListSerializer(AssetLinkStatement.serializer()),
    listOf(
        AssetLinkStatement(
            relation = listOf("delegate_permission/common.handle_all_urls"),
            target = AssetLinkTarget("android_app", packageName, fingerprints),
        )
    )
)

fun appleAuthorizeUrl(config: AppleWebConfig, state: String): String = buildString {
    append("https://appleid.apple.com/auth/authorize?")
    append(
        // No scopes: the backend only wants `sub`, and asking for any would switch Apple to form_post.
        listOf(
            "client_id" to config.servicesId,
            "redirect_uri" to config.redirectUri,
            "response_type" to "code",
            "response_mode" to "query",
            "state" to state,
        ).formUrlEncode()
    )
}

/**
 * Apple's client secret: an ES256 JWT signed with the `.p8` key. Minted per login rather than cached —
 * signing a three-claim payload costs microseconds, and nothing can then serve a stale one.
 */
internal fun appleClientSecret(config: AppleWebConfig, now: Instant = Instant.now()): String {
    val der = Base64.getDecoder().decode(
        config.privateKey.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
    )
    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
    return JWT.create()
        .withKeyId(config.keyId)
        .withIssuer(config.teamId)
        .withSubject(config.servicesId)
        .withAudience("https://appleid.apple.com")
        .withIssuedAt(Date.from(now))
        // Apple's ceiling is 6 months; we re-mint per login anyway.
        .withExpiresAt(Date.from(now.plusSeconds(180 * 24 * 3600L)))
        .sign(Algorithm.ECDSA256(null, key))
}

private val appleHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

/** Trades Apple's one-time `code` for the `id_token` it stands for. */
internal fun exchangeAppleCode(config: AppleWebConfig, code: String): String {
    val body = listOf(
        "client_id" to config.servicesId,
        "client_secret" to appleClientSecret(config),
        "code" to code,
        "grant_type" to "authorization_code",
        "redirect_uri" to config.redirectUri,
    ).formUrlEncode()
    val response = appleHttp.send(
        HttpRequest.newBuilder(URI("https://appleid.apple.com/auth/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            // The callback is public: a slow Apple must not pin request threads indefinitely.
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    val json = Json.parseToJsonElement(response.body()).jsonObject
    return json["id_token"]?.jsonPrimitive?.content
        ?: error("Apple token exchange failed (${response.statusCode()}): ${json["error"]?.jsonPrimitive?.content}")
}

private fun List<Pair<String, String>>.formUrlEncode() =
    joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

/**
 * Apple logins finished by the callback, waiting for the app to claim them. Claiming needs two halves
 * that no single party but the signing-in device holds:
 * - the secret behind `state`, which never left the device that started the flow (so an app that
 *   intercepts the return link cannot redeem it), and
 * - the nonce minted here, which travels only in the return link to the browser that signed in (so
 *   whoever *chose* the state — an attacker who mailed the victim a start link — cannot redeem it).
 *
 * ponytail: in-memory, one container; a restart drops logins mid-flight — a 60 s window, so a table
 * would be storage for nothing.
 */
class PendingLogins(private val ttlMillis: Long = 60_000) {
    private class Parked(val userId: Long, val nonce: String, val expiresAt: Long)

    private val entries = ConcurrentHashMap<String, Parked>()
    private val random = SecureRandom()

    /** Parks [userId] under [state] and returns the nonce the deep link must carry back. */
    fun park(state: String, userId: Long): String {
        val now = System.currentTimeMillis()
        entries.values.removeIf { it.expiresAt < now }
        val nonce = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        entries[state] = Parked(userId, nonce, now + ttlMillis)
        return nonce
    }

    /**
     * The parked user id, once. A wrong [secret] or [nonce] leaves the entry alone, so a guess cannot
     * burn someone's login.
     */
    fun claim(state: String, secret: String, nonce: String): Long? {
        if (sha256(secret) != state) return null
        val parked = entries[state] ?: return null
        if (!MessageDigest.isEqual(parked.nonce.toByteArray(), nonce.toByteArray())) return null
        if (!entries.remove(state, parked)) return null
        return parked.userId.takeIf { parked.expiresAt >= System.currentTimeMillis() }
    }
}
