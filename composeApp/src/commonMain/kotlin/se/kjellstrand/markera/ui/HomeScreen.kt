package se.kjellstrand.markera.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.ui.history.SeriesCard
import se.kjellstrand.markera.ui.history.dayOrdinals
import se.kjellstrand.markera.ui.markera.ImageSource
import se.kjellstrand.markera.ui.markera.PickedImage
import se.kjellstrand.markera.ui.markera.ScanChips
import se.kjellstrand.markera.ui.markera.rememberImagePicker

/** Start screen: free marking (standalone scanner) or competition marking. */
@Composable
internal fun HomeScreen(
    onFreeMarking: () -> Unit,
    /** Null hides the button: the platform supplied no competition flow. */
    onCompetition: (() -> Unit)?,
    onHistory: () -> Unit,
    onOpenSeries: (SeriesDto) -> Unit,
    onStatistics: () -> Unit,
    /** The picked images and whether Auto import is on. */
    onImport: (List<PickedImage>, Boolean) -> Unit,
    backendAuth: BackendAuth?,
    seriesServices: SeriesServices,
) {
    var showingHelp by remember { mutableStateOf(false) }
    var showingImport by remember { mutableStateOf(false) }
    // Read when the system picker returns, so it is the switch as the dialog was left.
    var importAuto by remember { mutableStateOf(false) }
    val pickImages = rememberImagePicker { onImport(it, importAuto) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // No top bar here, so the menu floats in the top-left corner.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Centred while it fits, scrolled when it doesn't (a small phone, a large font).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(48.dp))
                Button(onClick = onFreeMarking, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.home_free_marking), style = MaterialTheme.typography.titleLarge)
                }
                // Signed out or nothing saved yet: the cache is empty and the card just isn't there.
                val cached by seriesServices.repository.series.collectAsState()
                val latest = cached.firstOrNull() // Series.sq orders by timestamp DESC
                if (latest != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(Res.string.home_latest_series, localStamp(latest.timestamp).take(10)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    SeriesCard(
                        series = latest,
                        ordinal = remember(cached) { cached.dayOrdinals() }[latest.id] ?: 1,
                        services = seriesServices,
                        onClick = { onOpenSeries(latest) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeButton(Icons.Default.History, stringResource(Res.string.home_history), onHistory)
                    HomeButton(Icons.Default.BarChart, stringResource(Res.string.home_stats), onStatistics)
                    if (onCompetition != null) {
                        HomeButton(Icons.Default.EmojiEvents, stringResource(Res.string.home_competition), onCompetition)
                    }
                }
                Spacer(Modifier.height(16.dp))
                AccountRow(backendAuth, seriesServices)
            }
            AppMenu(
                items = listOf(
                    MenuItem(Icons.Default.PhotoLibrary, stringResource(Res.string.import_images)) {
                        showingImport = true
                    },
                    MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) {
                        showingHelp = true
                    },
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    // + the menu's own 4 dp = 16 dp from the edges.
                    .padding(12.dp),
            )
        }
    }

    if (showingImport) {
        ImportDialog(
            signedIn = backendAuth != null,
            onPick = { source, auto ->
                showingImport = false
                importAuto = auto
                pickImages(source)
            },
            onDismiss = { showingImport = false },
        )
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_home_title),
            sections = listOf(
                Res.string.help_home_how to Res.string.help_home_how_body,
                Res.string.help_home_marking to Res.string.help_home_marking_body,
                Res.string.help_home_import to Res.string.help_home_import_body,
                Res.string.help_home_history to Res.string.help_home_history_body,
                Res.string.help_home_stats to Res.string.help_home_stats_body,
                Res.string.help_home_account to Res.string.help_home_account_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }
}

/** The Auto import switch as last left, for the rest of the session. */
private var autoImportChoice = false

/**
 * Before the system picker: where the images come from, Auto import (saving
 * needs an account, so it is off and disabled signed out) and the caliber and
 * tag the series get.
 */
@Composable
private fun ImportDialog(signedIn: Boolean, onPick: (ImageSource, Boolean) -> Unit, onDismiss: () -> Unit) {
    var auto by remember { mutableStateOf(autoImportChoice) }
    val on = auto && signedIn
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.import_images)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ScanChips()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().toggleable(on, enabled = signedIn, role = Role.Switch) {
                        auto = it
                        autoImportChoice = it
                    },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.import_auto), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (signedIn) Res.string.import_auto_body else Res.string.import_auto_signed_out),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = null, enabled = signedIn)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(ImageSource.GALLERY, on) }) { Text(stringResource(Res.string.import_gallery)) }
        },
        dismissButton = {
            TextButton(onClick = { onPick(ImageSource.FILES, on) }) { Text(stringResource(Res.string.import_files)) }
        },
    )
}

/** Markera-backend account: sign in to save scanned series, or sign out. */
@Composable
private fun AccountRow(auth: BackendAuth?, seriesServices: SeriesServices) {
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

    if (busy || signingIn) {
        CircularProgressIndicator(Modifier.size(24.dp))
        return
    }
    if (auth == null) {
        TextButton(onClick = signIn) {
            Text(stringResource(Res.string.home_sign_in))
        }
    } else {
        // Column, not one row: three items side by side clip on a narrow phone.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(Res.string.home_signed_in, auth.provider.replaceFirstChar { it.uppercase() }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val signedOut = stringResource(Res.string.home_signed_out)
            // Kept apart: a slip from Sign out should not land on Delete account.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                TextButton(onClick = {
                    // Side by side: wiping the cache must not wait on the (best-effort) server revoke.
                    scope.launch { seriesServices.session.signOut() }
                    scope.launch { seriesServices.repository.clear() }
                    toast(signedOut)
                }) {
                    Text(stringResource(Res.string.home_sign_out))
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        stringResource(Res.string.home_delete_account),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** One of Home's equal-width list buttons; icon above the label so three fit in Swedish. */
@Composable
private fun RowScope.HomeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
