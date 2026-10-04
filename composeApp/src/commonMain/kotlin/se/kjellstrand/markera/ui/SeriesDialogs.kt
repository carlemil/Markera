package se.kjellstrand.markera.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.MAX_CALIBER_LABEL_LENGTH
import se.kjellstrand.markera.series.MAX_TAG_LENGTH
import se.kjellstrand.markera.series.isValidCaliberLabel
import se.kjellstrand.markera.series.parseCaliberDiameter

/**
 * The free-text tag for the next series: the tags already in use as rows, plus a
 * field for a new one. Its own composable rather than a [CaliberDialog] variant —
 * a caliber needs a diameter as well as a name.
 */
@Composable
internal fun TagDialog(
    selected: String?,
    known: List<String>,
    /** Null clears the tag. */
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    // No tags of the user's own yet: suggest two, in the UI language — once picked they are just tags.
    val offered = known.ifEmpty {
        listOf(stringResource(Res.string.series_tag_suggest_practice), stringResource(Res.string.series_tag_suggest_competition))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.series_tag_title)) },
        confirmButton = {
            TextButton(enabled = typed.isNotBlank(), onClick = { onSelect(typed) }) {
                Text(stringResource(Res.string.series_tag_use))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
        text = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // The leading null is the "clear it" row: untagged has exactly one
                    // representation, and it is null all the way to the server.
                    (listOf(null) + offered).forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(tag == selected, role = Role.RadioButton) { onSelect(tag) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = tag == selected, onClick = null)
                            Text(
                                tag ?: stringResource(Res.string.series_tag_none),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = typed,
                        // Capped here: a longer tag is a 400 from the server.
                        onValueChange = { if (it.length <= MAX_TAG_LENGTH) typed = it },
                        label = { Text(stringResource(Res.string.series_tag_new)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
    )
}

/**
 * Tags the scanned series; shown automatically while the caliber is "-". The
 * built-ins, then the user's own [custom] calibers (only those can be removed),
 * then a name + diameter pair to add one.
 */
@Composable
internal fun CaliberDialog(
    selected: Caliber,
    custom: List<Caliber>,
    onSelect: (Caliber) -> Unit,
    /** The new caliber's name and diameter in mm, both already valid; adding also selects it. */
    onAdd: (String, Float) -> Unit,
    onRemove: (Caliber) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var diameter by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val nameOk = isValidCaliberLabel(trimmed) && (Caliber.BUILT_IN + custom).none { it.label == trimmed }
    val diameterMm = parseCaliberDiameter(diameter)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.series_caliber_title)) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
        text = {
            // Compact rows: the whole row is the tap target, so the radio's 48 dp minimum is off.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    (Caliber.BUILT_IN + custom).forEach { caliber ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(caliber == selected, role = Role.RadioButton) { onSelect(caliber) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = caliber == selected, onClick = null)
                            Text(
                                if (caliber == Caliber.NONE) stringResource(Res.string.series_caliber_none) else caliber.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp).weight(1f),
                            )
                            if (caliber in custom) {
                                IconButton(onClick = { onRemove(caliber) }, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(Res.string.series_caliber_remove, caliber.label),
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        // Capped here: a longer label is a 400 from the server.
                        onValueChange = { if (it.length <= MAX_CALIBER_LABEL_LENGTH) name = it },
                        label = { Text(stringResource(Res.string.series_caliber_new_name)) },
                        isError = name.isNotEmpty() && !nameOk,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = diameter,
                        onValueChange = { diameter = it },
                        label = { Text(stringResource(Res.string.series_caliber_new_diameter)) },
                        isError = diameter.isNotEmpty() && diameterMm == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    TextButton(
                        enabled = nameOk && diameterMm != null,
                        onClick = { diameterMm?.let { onAdd(trimmed, it) } },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(Res.string.series_caliber_add))
                    }
                }
            }
        },
    )
}
