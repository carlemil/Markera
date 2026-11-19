package se.kjellstrand.markera.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.dialog_cancel
import se.kjellstrand.markera.res.settings_language

/**
 * The language row: the current choice, tapped for the whole list ([LANGUAGES] is too long
 * for a segmented row). Each option is a selectable row, so a test finds it by its name.
 */
@Composable
internal fun LanguagePicker(language: String?, systemLabel: String, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf<Pair<String?, String>>(null to systemLabel) + LANGUAGES
    val current = options.firstOrNull { it.first == language }?.second ?: systemLabel
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LANGUAGE_ROW_TAG)
            .clickable(role = Role.Button) { open = true },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(current, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(Res.string.settings_language)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(options, key = { it.first ?: "" }) { (code, name) ->
                    val on = code == language
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = on, role = Role.RadioButton) {
                                open = false
                                if (!on) onPick(code)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = on, onClick = null)
                        Text(name, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.dialog_cancel)) } },
    )
}
