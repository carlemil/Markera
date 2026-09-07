package se.kjellstrand.markera.series

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import se.kjellstrand.markera.BuildConfig

/**
 * Google sign-in via Credential Manager, exchanged for a backend session token.
 *
 * [context] must be the **Activity** context — Credential Manager needs one to
 * show its bottom sheet. Callers pass `LocalContext.current` from a composable
 * inside MainActivity, which is that activity.
 */
suspend fun signInWithProvider(context: Context, session: BackendSessionRepository): BackendAuth {
    val clientId = BuildConfig.GOOGLE_CLIENT_ID
    check(clientId.isNotBlank()) {
        "Google client id not configured (markera.google.client.id in local.properties)"
    }
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(clientId)
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .build()
    val response = CredentialManager.create(context)
        .getCredential(context, GetCredentialRequest(listOf(option)))
    val idToken = GoogleIdTokenCredential.createFrom(response.credential.data).idToken
    return session.signInGoogle(idToken)
}
