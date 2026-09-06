package se.kjellstrand.markera.series

import android.content.Context

/**
 * Mock flavor sign-in: the backend's dev endpoint, no Google Cloud config and
 * no UI. Same signature as the camera flavor's Credential Manager version.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun signInWithProvider(context: Context, session: BackendSessionRepository): BackendAuth =
    session.signInDev("emulator")
