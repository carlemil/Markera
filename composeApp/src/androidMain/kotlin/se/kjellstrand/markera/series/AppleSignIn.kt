package se.kjellstrand.markera.series

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import se.kjellstrand.markera.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Sign in with Apple on Android, mediated by the Markera backend: Apple ships no Android SDK, its web
 * flow's `client_id` must be a Services ID on a verified https domain, and the `code` exchange needs a
 * client secret that must never live in an APK. So the browser goes to `server/`, which talks to Apple
 * and parks the finished session for us to claim.
 *
 * Any app on the phone can register `markera://`, so the deep link carries only [state] — the sha256 of
 * a secret that stays here. An interceptor catches the hash and cannot redeem it.
 */
suspend fun signInWithApple(context: Context, session: BackendSessionRepository): BackendAuth {
    val secret = randomSecret()
    val state = sha256Hex(secret)
    val returned = AppleReturn.arm(state)
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
    returned.await()
    return session.signInAppleClaim(state, secret)
}

/** Where the browser is sent; the backend 302s on to Apple. */
internal fun appleStartUrl(backendUrl: String, state: String) =
    "${backendUrl.trimEnd('/')}/auth/apple/start?state=$state"

/** 32 bytes of hex: URL-safe without encoding, and what the backend's `state` regex expects. */
internal fun randomSecret(): String = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal fun sha256Hex(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/**
 * The `markera://auth/apple` deep link, waiting for `MainActivity` to hand it over. Process-level, like
 * iOS's `inFlight`, so an activity recreated while the browser is up still resolves.
 */
object AppleReturn {
    private var state: String? = null
    private var pending: CompletableDeferred<Unit>? = null

    @Synchronized
    fun arm(state: String): CompletableDeferred<Unit> {
        cancel() // an earlier attempt the user walked away from
        this.state = state
        return CompletableDeferred<Unit>().also { pending = it }
    }

    /** The browser came back. @return true when the intent was the sign-in we armed. */
    @Synchronized
    fun deliver(uri: Uri?): Boolean {
        val waiting = pending ?: return false
        if (uri == null || uri.getQueryParameter("state") != state) return false
        clear()
        // The backend sets `error` when the user backed out at Apple's page.
        if (uri.getQueryParameter("error") != null) waiting.completeExceptionally(SignInCancelledException())
        else waiting.complete(Unit)
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
