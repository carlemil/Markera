package se.kjellstrand.markera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.ClaimState
import se.kjellstrand.markera.series.LocalClaimController

/** Shared by Android and iOS; every dismissal means Stay signed out. */
@Composable
internal fun LocalClaimDialog(controller: LocalClaimController) {
    val state by controller.state.collectAsState()
    if (state == ClaimState.Hidden) return
    val working = state == ClaimState.Working
    AlertDialog(
        onDismissRequest = { if (!working) controller.dismiss() },
        title = { Text(stringResource(Res.string.local_claim_title)) },
        text = {
            when (val current = state) {
                is ClaimState.Ask -> Text(stringResource(Res.string.local_claim_body, current.count))
                is ClaimState.ConfirmDelete -> Text(stringResource(Res.string.local_claim_delete_body, current.count))
                is ClaimState.Error -> Text(stringResource(Res.string.local_claim_error))
                else -> CircularProgressIndicator()
            }
        },
        // One right-aligned column: AlertDialog's flow row mis-measures a Column passed as
        // dismissButton (a wide gap, and the last button clipped on a phone).
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                when (state) {
                    is ClaimState.Ask -> {
                        TextButton(onClick = controller::add) { Text(stringResource(Res.string.local_claim_add)) }
                        TextButton(onClick = controller::askDelete) { Text(stringResource(Res.string.local_claim_delete)) }
                        TextButton(onClick = controller::staySignedOut) { Text(stringResource(Res.string.local_claim_stay)) }
                    }
                    is ClaimState.ConfirmDelete -> Row {
                        TextButton(onClick = controller::cancelDelete) { Text(stringResource(Res.string.dialog_cancel)) }
                        TextButton(onClick = controller::confirmDelete) { Text(stringResource(Res.string.local_claim_delete)) }
                    }
                    is ClaimState.Error -> TextButton(onClick = controller::dismiss) { Text(stringResource(Res.string.help_close)) }
                    else -> Unit
                }
            }
        },
    )
}
