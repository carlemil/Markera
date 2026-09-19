package se.kjellstrand.markera.series

import androidx.compose.runtime.Composable

/** The user backed out of the sign-in sheet: nothing to report. */
class SignInCancelledException : Exception("Sign-in cancelled")

/** The platform's sign-in call, bound to whatever context it needs. */
@Composable
expect fun rememberSignIn(session: BackendSessionRepository): suspend () -> BackendAuth
