package se.kjellstrand.markera.series

import androidx.compose.runtime.Composable

/** The user backed out of the sign-in sheet: nothing to report. */
class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * The device itself cannot sign in with this provider (Sign in with Apple's error 1000, returned at
 * once with no sheet, e.g. an Apple Account without two-factor authentication, as on App Review's
 * devices): not a bug, so the user gets a hint and Sentry only a breadcrumb.
 */
class SignInUnavailableException(detail: String) : Exception(detail)

/** The platform's sign-in call, bound to whatever context it needs. */
@Composable
expect fun rememberSignIn(session: BackendSessionRepository): suspend () -> BackendAuth
