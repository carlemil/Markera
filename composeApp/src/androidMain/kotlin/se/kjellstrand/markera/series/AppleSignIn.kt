package se.kjellstrand.markera.series

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import se.kjellstrand.markera.BuildConfig
import se.kjellstrand.markera.diag.ErrorLog
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sign in with Apple on Android, mediated by the Markera backend: Apple ships no Android SDK, its web
 * flow's `client_id` must be a Services ID on a verified https domain, and the `code` exchange needs a
 * client secret that must never live in an APK. So the browser goes to `server/`, which talks to Apple
 * and parks the finished session for us to claim.
 *
 * The browser comes back through a verified https App Link that only Markera can open (see
 * [appleStartUrl]); older backends and the done page's fallback use `markera://`, which any app can
 * register. Either link carries only [state] — the sha256 of a secret that stays here — plus the backend's
 * one-time nonce. An interceptor catches both and still lacks the secret; whoever chose the state (an
 * attacker mailing a start link) lacks the nonce.
 */
suspend fun signInWithApple(context: Context, session: BackendSessionRepository): BackendAuth {
    val secret = randomSecret()
    val state = sha256Hex(secret)
    val returned = AppleReturn.arm(state)
    ErrorLog.breadcrumb("signin", "apple: opening the browser")
    try {
        // ponytail: plain ACTION_VIEW, not a Custom Tab — androidx.browser is not on the classpath and a
        // chrome tab would buy only cosmetics.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(appleStartUrl(BuildConfig.BACKEND_URL, state)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        AppleReturn.cancel()
        throw IllegalStateException("No browser to sign in with Apple in", e)
    }
    val nonce = returned.await()
    ErrorLog.breadcrumb("signin", "apple: back from the browser, claiming the session")
    return session.signInAppleClaim(state, secret, nonce).also { ErrorLog.breadcrumb("signin", "apple: signed in") }
}

/**
 * Where the browser is sent; the backend 302s on to Apple. `return=https` asks the backend to come back
 * through the verified App Link `https://<backend host>/auth/apple/done` rather than `markera://auth/apple`,
 * which any app can register and swallow (issue #48). A backend too old to know the parameter ignores it
 * and uses `markera://`, which the manifest still catches.
 */
internal fun appleStartUrl(backendUrl: String, state: String) =
    "${backendUrl.trimEnd('/')}/auth/apple/start?state=$state&return=https"

/** The https return's path on the backend host; the manifest's autoVerify intent-filter matches it. */
internal const val APPLE_DONE_PATH = "/auth/apple/done"

/** What a return link said, once it is known to be the armed sign-in's. */
internal sealed interface AppleReturnLink {
    data class Nonce(val nonce: String) : AppleReturnLink
    data object Cancelled : AppleReturnLink
}

/**
 * Reads a sign-in return link: `markera://auth/apple?…` (older backends, or the done page's fallback) or
 * `https://<host of backendUrl>/auth/apple/done?…` (the App Link). Null for any other link, or one whose
 * `state` is not [expectedState] — that is someone else's, and the armed sign-in keeps waiting.
 * The backend sets `error` when the user backed out at Apple's page; no nonce counts as that too.
 */
internal fun parseAppleReturn(link: String?, backendUrl: String, expectedState: String?): AppleReturnLink? {
    if (link == null || expectedState == null) return null
    val uri = try { URI(link) } catch (_: URISyntaxException) { return null }
    val isCustomScheme = uri.scheme == "markera" && uri.host == "auth" && uri.path == "/apple"
    val backendHost = try { URI(backendUrl).host } catch (_: URISyntaxException) { null }
    val isAppLink = uri.scheme == "https" && backendHost != null &&
        uri.host.equals(backendHost, ignoreCase = true) && uri.path == APPLE_DONE_PATH
    if (!isCustomScheme && !isAppLink) return null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
    }.toMap()
    if (params["state"] != expectedState) return null
    val nonce = params["nonce"]
    return if (params["error"] != null || nonce == null) AppleReturnLink.Cancelled else AppleReturnLink.Nonce(nonce)
}

/** 32 bytes of hex: URL-safe without encoding, and what the backend's `state` regex expects. */
internal fun randomSecret(): String = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal fun sha256Hex(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/**
 * The return link (`https://…/auth/apple/done` or `markera://auth/apple`), waiting for `MainActivity` to
 * hand it over. Process-level, like iOS's `inFlight`, so an activity recreated while the browser is up
 * still resolves.
 */
object AppleReturn {
    private var state: String? = null
    private var pending: CompletableDeferred<String>? = null

    /** @return the deep link's nonce, once the browser comes back. */
    @Synchronized
    fun arm(state: String): CompletableDeferred<String> {
        cancel() // an earlier attempt the user walked away from
        this.state = state
        return CompletableDeferred<String>().also { pending = it }
    }

    /** The browser came back with [link] (the intent's data). @return true when it was the sign-in we armed. */
    @Synchronized
    fun deliver(link: String?): Boolean {
        val waiting = pending ?: return false
        val result = parseAppleReturn(link, BuildConfig.BACKEND_URL, state) ?: return false
        clear()
        when (result) {
            is AppleReturnLink.Nonce -> waiting.complete(result.nonce)
            AppleReturnLink.Cancelled -> waiting.completeExceptionally(SignInCancelledException())
        }
        return true
    }

    /**
     * Give up on whatever is armed. Called from `onResume`, where a still-pending deferred means the user
     * backed out of the browser.
     *
     * ponytail: resume heuristic for "user backed out"; a plain ACTION_VIEW gives no result.
     */
    @Synchronized
    fun cancel() {
        pending?.completeExceptionally(SignInCancelledException())
        clear()
    }

    private fun clear() {
        pending = null
        state = null
    }
}
