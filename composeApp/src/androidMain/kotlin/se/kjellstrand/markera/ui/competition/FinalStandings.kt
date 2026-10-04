package se.kjellstrand.markera.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import se.kjellstrand.markera.ui.SectionHeader
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.*

/** Final standings after the last series (only when everything is registered). */
@Composable
internal fun FinishSummary(state: WizardUiState) {
    if (state.summaryLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        return
    }
    val summary = state.summary ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.wizard_final_done),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (summary.standings.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.wizard_standings))
            summary.standings.forEach { standing ->
                Surface(
                    color = if (standing.tied) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = standing.placement?.toString() ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = standing.name ?: stringResource(Res.string.wizard_unknown_name),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val sub = if (standing.tied) {
                                stringResource(Res.string.wizard_tied)
                            } else {
                                listOfNotNull(
                                    standing.lane?.let { stringResource(Res.string.wizard_standing_lane, it) },
                                    standing.club,
                                ).joinToString(" · ")
                            }
                            if (sub.isNotEmpty()) {
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "${standing.points}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.wizard_x_count, standing.hits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
