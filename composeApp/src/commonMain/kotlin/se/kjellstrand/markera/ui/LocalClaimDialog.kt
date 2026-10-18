package se.kjellstrand.markera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        confirmButton = {
            when (state) {
                is ClaimState.Ask -> TextButton(onClick = controller::add) { Text(stringResource(Res.string.local_claim_add)) }
                is ClaimState.ConfirmDelete -> TextButton(onClick = controller::confirmDelete) { Text(stringResource(Res.string.local_claim_delete)) }
                else -> Unit
            }
        },
        dismissButton = {
            Column {
                when (state) {
                    is ClaimState.Ask -> {
                        TextButton(onClick = controller::askDelete) { Text(stringResource(Res.string.local_claim_delete)) }
                        TextButton(onClick = controller::staySignedOut) { Text(stringResource(Res.string.local_claim_stay)) }
                    }
                    is ClaimState.ConfirmDelete -> TextButton(onClick = controller::cancelDelete) { Text(stringResource(Res.string.dialog_cancel)) }
                    is ClaimState.Error -> TextButton(onClick = controller::dismiss) { Text(stringResource(Res.string.help_close)) }
                    else -> Unit
                }
            }
        },
    )
}
