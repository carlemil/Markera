package se.kjellstrand.markera.series

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.dialog_cancel
import se.kjellstrand.markera.res.sign_in_apple
import se.kjellstrand.markera.res.sign_in_google
import se.kjellstrand.markera.res.sign_in_title
import org.jetbrains.compose.resources.stringResource
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import se.kjellstrand.markera.BuildConfig

/** The providers the Android chooser offers. Google stays: every account made before this card is Google-keyed. */
private enum class Provider { GOOGLE, APPLE }

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
    val response = try {
        CredentialManager.create(context)
            .getCredential(context, GetCredentialRequest(listOf(option)))
    } catch (e: GetCredentialCancellationException) {
        throw SignInCancelledException()
    }
    val idToken = GoogleIdTokenCredential.createFrom(response.credential.data).idToken
    return session.signInGoogle(idToken)
}

@Composable
actual fun rememberSignIn(session: BackendSessionRepository): suspend () -> BackendAuth {
    // LocalContext inside MainActivity is the Activity, which is what
    // Credential Manager needs.
    val context = LocalContext.current
    var choosing by remember { mutableStateOf<CompletableDeferred<Provider>?>(null) }

    choosing?.let { choice ->
        fun pick(provider: Provider?) {
            choosing = null
            if (provider == null) choice.completeExceptionally(SignInCancelledException())
            else choice.complete(provider)
        }
        AlertDialog(
            onDismissRequest = { pick(null) },
            title = { Text(stringResource(Res.string.sign_in_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pick(Provider.GOOGLE) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.sign_in_google))
                    }
                    Button(onClick = { pick(Provider.APPLE) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.sign_in_apple))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick(null) }) { Text(stringResource(Res.string.dialog_cancel)) } },
        )
    }

    return {
        val choice = CompletableDeferred<Provider>()
        choosing = choice
        when (choice.await()) {
            Provider.GOOGLE -> signInWithProvider(context, session)
            Provider.APPLE -> signInWithApple(context, session)
        }
    }
}
