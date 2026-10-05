package se.kjellstrand.markera.series

import androidx.compose.runtime.Composable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUnderlyingErrorKey
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelNormal
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import se.kjellstrand.markera.diag.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sign in with Apple, exchanged for a backend session token.
 *
 * No scopes are requested — the backend only uses the identity token's `sub`.
 * Call from the main thread: it drives UIKit.
 */
suspend fun signInWithProvider(session: BackendSessionRepository): BackendAuth {
    val idToken = requestAppleIdToken()
    ErrorLog.breadcrumb("signin", "apple: exchanging the id token with the backend")
    return session.signInApple(idToken).also { ErrorLog.breadcrumb("signin", "apple: signed in") }
}

// ASAuthorizationController holds its delegate weakly, so park both here for
// the duration of the request.
private val inFlight = mutableListOf<Any>()

@OptIn(ExperimentalForeignApi::class)
private suspend fun requestAppleIdToken(): String {
    val result = CompletableDeferred<String>()
    val delegate = AppleSignInDelegate(result)
    val controller = ASAuthorizationController(
        authorizationRequests = listOf(ASAuthorizationAppleIDProvider().createRequest()),
    )
    controller.delegate = delegate
    controller.presentationContextProvider = delegate
    inFlight += delegate
    inFlight += controller
    ErrorLog.breadcrumb("signin", "apple: asking ASAuthorizationController")
    controller.performRequests()
    try {
        return result.await()
    } finally {
        inFlight -= delegate
        inFlight -= controller
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class AppleSignInDelegate(
    private val result: CompletableDeferred<String>,
) : NSObject(),
    ASAuthorizationControllerDelegateProtocol,
    ASAuthorizationControllerPresentationContextProvidingProtocol {

    override fun authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization: ASAuthorization,
    ) {
        val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
        val token = credential?.identityToken?.let {
            NSString.create(data = it, encoding = NSUTF8StringEncoding) as String?
        }
        ErrorLog.breadcrumb("signin", "apple: got credential, token=${if (token == null) "missing" else "present"}")
        if (token == null) {
            result.completeExceptionally(IllegalStateException("No Apple identity token"))
        } else {
            result.complete(token)
        }
    }

    override fun authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError: NSError,
    ) {
        val error = didCompleteWithError
        if (error.code == ASAuthorizationErrorCanceled) {
            ErrorLog.breadcrumb("signin", "apple: cancelled")
            result.completeExceptionally(SignInCancelledException())
        } else {
            ErrorLog.breadcrumb("signin", "apple: failed ${error.domain} ${error.code}")
            // The whole NSError goes in the message: Sentry scrubs extras with auth-ish words.
            result.completeExceptionally(IllegalStateException(describe(error)))
        }
    }

    override fun presentationAnchorForAuthorizationController(
        controller: ASAuthorizationController,
    ): ASPresentationAnchor {
        val window = keyWindow()
        val scenes = UIApplication.sharedApplication.connectedScenes.size
        ErrorLog.breadcrumb(
            "signin",
            "apple: presenting from ${window::class.simpleName}, key=${window.keyWindow}, scenes=$scenes",
        )
        return window
    }
}

/**
 * "domain code: description; reason: …; userInfo keys: […] <- underlying domain code: description …",
 * walking NSUnderlyingErrorKey up to five deep. An Apple ID error carries nothing personal.
 */
internal fun describe(error: NSError): String = buildString {
    var e: NSError? = error
    var depth = 0
    while (e != null && depth < 5) {
        if (depth > 0) append(" <- underlying ")
        append("${e.domain} ${e.code}: ${e.localizedDescription}")
        e.localizedFailureReason?.let { append("; reason: $it") }
        append("; userInfo keys: ${e.userInfo.keys.map { it.toString() }}")
        e = e.userInfo[NSUnderlyingErrorKey] as? NSError
        depth++
    }
}

/**
 * The app's key window; a sheet has nowhere to go without one. The first window of a scene can
 * be the keyboard's UITextEffectsWindow, which no sheet presents from.
 */
internal fun keyWindow(): UIWindow {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?: scenes.firstOrNull()
        ?: error("The app has no window scene to present from")
    val windows = scene.windows.filterIsInstance<UIWindow>()
    return windows.firstOrNull { it.keyWindow }
        ?: scene.keyWindow
        ?: windows.firstOrNull { it.windowLevel == UIWindowLevelNormal }
        ?: error("The app has no window to present from")
}

/** The xcconfig `MARKERA_DEV_AUTH` flag, as xcodegen wrote it into Info.plist. */
private val devAuth: Boolean by lazy {
    when (val flag = NSBundle.mainBundle.objectForInfoDictionaryKey("MarkeraDevAuth")) {
        is String -> flag.equals("YES", ignoreCase = true)
        is NSNumber -> flag.boolValue
        is Boolean -> flag
        else -> false
    }
}

@Composable
actual fun rememberSignIn(session: BackendSessionRepository): suspend () -> BackendAuth = {
    // Debug builds fall back to dev auth: the simulator has no Apple ID.
    if (devAuth) {
        try {
            signInWithProvider(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.breadcrumb("signin", "apple: failed, using dev auth")
            println("Sign in with Apple failed, using dev auth: $e")
            session.signInDev("ios-sim")
        }
    } else {
        signInWithProvider(session)
    }
}
