package se.kjellstrand.markera.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.webshooter.WebshooterServices
import se.kjellstrand.markera.webshooter.api.dto.MarkingGroupDto

/**
 * "Markering — välj markeringsgrupp": the lane-group list, mirroring the web's
 * marking tab (progress per group, last-marked hint, complete badge with a
 * confirm before re-entering a finished hall).
 */
@Composable
fun MarkingGroupsScreen(
    services: WebshooterServices,
    competitionId: Int,
    onBack: () -> Unit,
    onOpenGroup: (MarkingGroupDto) -> Unit,
) {
    val viewModel: MarkingGroupsViewModel = viewModel(key = "marking-groups-$competitionId") {
        MarkingGroupsViewModel(services.scoringRepository, competitionId)
    }
    val uiState by viewModel.uiState.collectAsState()
    // Which complete group is asking "gå in ändå?"; null = none.
    var confirmingGroupId by remember { mutableStateOf<Int?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            CompetitionTopBar(
                title = stringResource(Res.string.marking_title),
                subtitle = uiState.targets?.competition?.name,
                onBack = onBack,
            )
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.error -> ErrorRetry(onRetry = viewModel::load)

                else -> {
                    val targets = uiState.targets
                    val activePatrol = targets?.activePatrol
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            if (uiState.notEnabled || activePatrol == null) {
                                NoActivePatrolBanner(onRetry = viewModel::load)
                            } else {
                                activePatrol.sortorder?.let { sortorder ->
                                    Text(
                                        text = stringResource(Res.string.marking_active_patrol, sortorder) +
                                            (activePatrol.startTimeHuman?.let { " ($it)" } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                            }
                        }
                        val groups = targets?.markingGroups.orEmpty()
                        if (groups.isEmpty() && !uiState.notEnabled) {
                            item {
                                Text(
                                    text = stringResource(Res.string.marking_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(groups, key = { it.id }) { group ->
                            MarkingGroupCard(
                                group = group,
                                showProgress = activePatrol != null,
                                confirming = confirmingGroupId == group.id,
                                onClick = {
                                    if (group.complete && confirmingGroupId != group.id) {
                                        confirmingGroupId = group.id
                                    } else {
                                        confirmingGroupId = null
                                        onOpenGroup(group)
                                    }
                                },
                                onConfirm = {
                                    confirmingGroupId = null
                                    onOpenGroup(group)
                                },
                                onCancel = { confirmingGroupId = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoActivePatrolBanner(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.marking_no_active_patrol),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onRetry) {
                Text(stringResource(Res.string.wizard_retry))
            }
        }
    }
}

@Composable
private fun MarkingGroupCard(
    group: MarkingGroupDto,
    showProgress: Boolean,
    confirming: Boolean,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(Res.string.marking_lanes, group.laneStart, group.laneEnd),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.complete) {
                    CompleteBadge()
                } else {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showProgress) {
                if (group.expectedCount > 0) {
                    Text(
                        text = stringResource(
                            Res.string.marking_progress, group.markedCount, group.expectedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val last = group.lastMarked
                if (last?.serie != null && last.lane != null && last.shooter != null) {
                    Text(
                        text = stringResource(Res.string.marking_last, last.serie!!, last.shooter!!, last.lane!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.marking_none_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (confirming) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.marking_reenter_question),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm) {
                        Text(stringResource(Res.string.marking_reenter_yes))
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(Res.string.marking_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompleteBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.height(14.dp),
            )
            Text(
                text = stringResource(Res.string.marking_complete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
