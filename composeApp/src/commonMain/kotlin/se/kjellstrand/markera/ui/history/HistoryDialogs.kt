package se.kjellstrand.markera.ui.history

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.total

/** The delete confirmation, shared by the history list and the detail screen. */
@Composable
internal fun DeleteSeriesDialog(series: SeriesDto, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.history_delete_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.history_delete_message,
                    localStamp(series.timestamp),
                    series.total(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.history_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.history_delete_cancel))
            }
        },
    )
}

/** The same confirmation for removing a single hole, on the scan and detail screens. */
@Composable
internal fun DeleteHoleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.hole_delete_title)) },
        text = { Text(stringResource(Res.string.hole_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.hole_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.history_delete_cancel))
            }
        },
    )
}
