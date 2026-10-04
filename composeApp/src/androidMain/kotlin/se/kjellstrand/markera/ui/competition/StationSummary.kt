package se.kjellstrand.markera.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import se.kjellstrand.markera.ui.SectionHeader
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.PrimaryActionButton
import se.kjellstrand.markera.webshooter.MarkingLogic

// ---- Station summary ------------------------------------------------------

@Composable
internal fun StationSummaryContent(
    state: WizardUiState,
    onGoToLane: (Int) -> Unit,
    onNextStation: () -> Unit,
) {
    val context = state.context ?: return
    val station = state.station ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(Res.string.wizard_station_summary, state.stationIndex + 1))
        var registered = 0
        context.lanes.forEachIndexed { index, entry ->
            val result = MarkingLogic.resultFor(entry.signup, station.sortorder)
            val scored = MarkingLogic.isScored(result)
            if (scored) registered++
            Surface(
                onClick = { onGoToLane(index) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.lane.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(36.dp),
                    )
                    Text(
                        text = entry.signup.user?.fullname ?: stringResource(Res.string.wizard_unknown_name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = result?.takeIf { scored }?.points?.toString() ?: "–",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result?.takeIf { scored }?.hits?.let { stringResource(Res.string.wizard_x_count, it) } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp),
                    )
                    if (scored) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(Res.string.wizard_registered_count, registered, context.lanes.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (!state.isLastStation) {
            PrimaryActionButton(
                text = stringResource(Res.string.wizard_next_station, state.stationIndex + 2),
                icon = Icons.Default.ChevronRight,
                onClick = onNextStation,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            FinishSummary(state)
        }
    }
}
