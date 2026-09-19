package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*

private val PICKER_GAP = 6.dp
private val PICKER_ITEM_WIDTH = 44.dp
private val PICKER_ITEM_HEIGHT = 48.dp
private val DIALPAD_KEY_SIZE = 72.dp

/** Dialpad layout: 0 1 2 / 3 4 5 / 6 7 8 / 9 10 X, as picker indices. */
private val DIALPAD_KEYS = listOf(
    listOf(0, 1, 2),
    listOf(3, 4, 5),
    listOf(6, 7, 8),
    listOf(9, 10, SCORE_PICKER_INNER_TEN),
)

/** The key letter for hole [index]: a, b, c… — same on the photo and the box. */
fun holeLetter(index: Int): String = ('a' + index).toString()

/**
 * One hole's score: a highlighted box showing only the current value. With an
 * [onValueChange] tapping it opens the dialpad; without one (an empty slot on
 * the scan screen, or the landscape column) it just displays.
 * [letter] is the small key tying the box to its marker on the photo.
 * Also the series detail screen's score cell, so the two edit the same way.
 */
@Composable
internal fun ScoreBox(
    value: Int,
    onValueChange: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    letter: String? = null,
    /** A hand-placed hole: orange like its photo marker. */
    manual: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }
    val accent = if (manual) MANUAL_HIT_COLOR else MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .width(PICKER_ITEM_WIDTH)
            .height(PICKER_ITEM_HEIGHT)
            .background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.medium)
            .border(2.dp, accent, MaterialTheme.shapes.medium)
            .then(
                if (onValueChange == null) {
                    Modifier
                } else {
                    Modifier.clickable { showDialog = true }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SCORE_PICKER_LABELS[value.coerceIn(0, SCORE_PICKER_INNER_TEN)],
            style = MaterialTheme.typography.titleLarge,
        )
        if (letter != null) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp, top = 1.dp),
            )
        }
    }
    if (showDialog && onValueChange != null) {
        ScoreDialpadDialog(
            onPick = {
                onValueChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * The 0..10 + X dialpad as a dialog; [onPick] gets the picker index. The
 * competition wizard types any slot; the scan screen only a slot with a hole.
 */
@Composable
private fun ScoreDialpadDialog(
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
        title = { Text(stringResource(Res.string.score_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PICKER_GAP)) {
                DIALPAD_KEYS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PICKER_GAP)) {
                        row.forEach { key -> DialpadKey(key) { onPick(key) } }
                    }
                }
            }
        },
    )
}

@Composable
private fun DialpadKey(value: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(DIALPAD_KEY_SIZE)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), MaterialTheme.shapes.medium)
            .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = SCORE_PICKER_LABELS[value], style = MaterialTheme.typography.headlineSmall)
    }
}

/** Portrait — the score boxes in a row; read-only without [onValueChange]. */
@Composable
fun ScorePickerHorizontalRow(
    values: List<Int>,
    modifier: Modifier = Modifier,
    onValueChange: ((index: Int, value: Int) -> Unit)? = null,
    /** How many leading boxes carry a key letter — i.e. how many holes there are. */
    letteredCount: Int = 0,
    /** How many leading boxes [onValueChange] applies to; the rest just display. */
    editableCount: Int = values.size,
    /** Per box: a hand-placed hole, drawn orange. */
    manual: List<Boolean> = emptyList(),
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PICKER_GAP),
    ) {
        values.forEachIndexed { i, v ->
            ScoreBox(
                value = v,
                onValueChange = onValueChange?.takeIf { i < editableCount }?.let { f -> { v2: Int -> f(i, v2) } },
                letter = if (i < letteredCount) holeLetter(i) else null,
                manual = manual.getOrElse(i) { false },
            )
        }
    }
}

/** Landscape — the score boxes in a column, read-only (only the scan screen). */
@Composable
fun ScorePickerVerticalColumn(
    values: List<Int>,
    modifier: Modifier = Modifier,
    letteredCount: Int = 0,
    manual: List<Boolean> = emptyList(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PICKER_GAP),
    ) {
        values.forEachIndexed { i, v ->
            ScoreBox(
                value = v,
                onValueChange = null,
                letter = if (i < letteredCount) holeLetter(i) else null,
                manual = manual.getOrElse(i) { false },
            )
        }
    }
}
