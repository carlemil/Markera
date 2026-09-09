package se.kjellstrand.markera.series

import androidx.compose.runtime.Composable

/** The platform's sign-in call, bound to whatever context it needs. */
@Composable
expect fun rememberSignIn(session: BackendSessionRepository): suspend () -> BackendAuth
