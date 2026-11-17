package se.kjellstrand.markera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.SeriesServices

/**
 * Signed out: why to sign in, and the button. [localCount] is how many series exist only on
 * this phone; with some, the card counts them, since that is the reason that matters. With
 * [compact] it is one line (the Serie page, under a photo); [title] then replaces the count.
 */
@Composable
internal fun SignInCard(
    services: SeriesServices,
    localCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    title: String? = null,
) {
    val (signingIn, signIn) = rememberBackendSignIn(services)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(
                    title ?: when (localCount) {
                        0 -> stringResource(Res.string.home_signin_title)
                        1 -> stringResource(Res.string.home_local_title_one)
                        else -> stringResource(Res.string.home_local_title, localCount)
                    },
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                )
                if (!compact) Text(
                    stringResource(Res.string.home_signin_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                signingIn -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                compact -> TextButton(onClick = signIn) { Text(stringResource(Res.string.action_sign_in)) }
                else -> Button(onClick = signIn) { Text(stringResource(Res.string.action_sign_in)) }
            }
        }
    }
}

/**
 * Settings' account block. Signed out: the sign-in button. Signed in: who with, Sign out,
 * and Delete account behind a typed confirmation.
 */
@Composable
internal fun AccountActions(auth: BackendAuth?, seriesServices: SeriesServices) {
    val (signingIn, signIn) = rememberBackendSignIn(seriesServices)
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        // Scoped to the open dialog: it starts empty every time the dialog is shown.
        var typed by remember { mutableStateOf("") }
        val phrase = stringResource(Res.string.home_delete_account_phrase)
        val confirmed = typed.trim().equals(phrase, ignoreCase = true)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.home_delete_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.home_delete_account_message))
                    Text(stringResource(Res.string.home_delete_account_prompt, phrase))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(Res.string.home_delete_account_input_label)) },
                        placeholder = { Text(phrase) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = confirmed, onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        try {
                            seriesServices.api.deleteAccount()
                            seriesServices.session.signOut()
                            seriesServices.repository.clear()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Stay signed in; the account is still there.
                            toast(getString(Res.string.home_delete_account_failed))
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text(
                        stringResource(Res.string.home_delete_account_confirm),
                        color = if (confirmed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.home_delete_account_cancel))
                }
            },
        )
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (auth == null) {
                stringResource(Res.string.settings_not_signed_in)
            } else {
                stringResource(Res.string.home_signed_in, auth.provider.replaceFirstChar { it.uppercase() })
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        when {
            busy || signingIn -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            auth == null -> TextButton(onClick = signIn) { Text(stringResource(Res.string.action_sign_in)) }
            else -> {
                val signedOut = stringResource(Res.string.home_signed_out)
                TextButton(onClick = {
                    // Side by side: wiping the cache must not wait on the (best-effort) server revoke.
                    scope.launch { seriesServices.session.signOut() }
                    scope.launch { seriesServices.repository.clear() }
                    toast(signedOut)
                }) {
                    Text(stringResource(Res.string.home_sign_out))
                }
            }
        }
    }
    // On its own row: a slip from Sign out should not land on Delete account.
    if (auth != null && !busy) {
        TextButton(onClick = { confirmDelete = true }) {
            Text(stringResource(Res.string.home_delete_account), color = MaterialTheme.colorScheme.error)
        }
    }
}
