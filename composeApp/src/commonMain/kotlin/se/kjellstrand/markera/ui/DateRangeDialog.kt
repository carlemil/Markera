package se.kjellstrand.markera.ui

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeDialog(onDismiss: () -> Unit, onPicked: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            val end = state.selectedEndDateMillis
            TextButton(
                onClick = { if (start != null && end != null) onPicked(start, end) },
                enabled = start != null && end != null,
            ) { Text(stringResource(Res.string.stats_date_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.stats_date_cancel)) }
        },
    ) {
        // Weighted so the tall range picker scrolls inside the dialog instead of
        // pushing the buttons off a short screen.
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}
