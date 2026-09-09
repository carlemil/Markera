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
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sign in with Apple, exchanged for a backend session token.
 *
 * No scopes are requested — the backend only uses the identity token's `sub`.
 * Call from the main thread: it drives UIKit.
 */
suspend fun signInWithProvider(session: BackendSessionRepository): BackendAuth {
    val idToken = requestAppleIdToken()
    return session.signInApple(idToken)
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
        result.completeExceptionally(
            IllegalStateException(didCompleteWithError.localizedDescription),
        )
    }

    override fun presentationAnchorForAuthorizationController(
        controller: ASAuthorizationController,
    ): ASPresentationAnchor = keyWindow()
}

/** The app's key window; a sheet has nowhere to go without one. */
internal fun keyWindow(): UIWindow =
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows }
        .filterIsInstance<UIWindow>()
        .firstOrNull()
        ?: error("The app has no window to present from")

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
            println("Sign in with Apple failed, using dev auth: $e")
            session.signInDev("ios-sim")
        }
    } else {
        signInWithProvider(session)
    }
}
