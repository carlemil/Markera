package se.kjellstrand.markera.ui.competition

import se.kjellstrand.markera.ui.AppTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import se.kjellstrand.markera.ui.StateMessage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EmojiEvents
import se.kjellstrand.markera.ui.MenuItem
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.webshooter.WebshooterServices
import se.kjellstrand.markera.webshooter.api.dto.CompetitionSummaryDto

@Composable
fun CompetitionListScreen(
    services: WebshooterServices,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val viewModel: CompetitionListViewModel = viewModel(key = "webshooter-competitions") {
        CompetitionListViewModel(services.scoringRepository)
    }
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            AppTopBar(
                title = stringResource(Res.string.competitions_title),
                subtitle = services.sessionRepository.session.collectAsState().value?.userName,
                onBack = onBack,
                menuItems = listOf(
                    MenuItem(Icons.AutoMirrored.Filled.Logout, stringResource(Res.string.logout)) {
                        scope.launch {
                            services.sessionRepository.logout()
                            onLoggedOut()
                        }
                    },
                ),
            )
            OutlinedTextField(
                value = uiState.search,
                onValueChange = viewModel::setSearch,
                placeholder = { Text(stringResource(Res.string.competitions_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            when {
                uiState.loading -> StateMessage(loading = true)

                uiState.error -> StateMessage(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(Res.string.state_error_title),
                    hint = stringResource(Res.string.error_network),
                    actionLabel = stringResource(Res.string.retry),
                    onAction = { viewModel.load() },
                )

                uiState.competitions.isEmpty() -> StateMessage(
                    icon = Icons.Default.EmojiEvents,
                    title = stringResource(Res.string.competitions_empty),
                    hint = stringResource(Res.string.competitions_empty_hint),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.competitions, key = { it.id }) { competition ->
                        CompetitionCard(competition, onClick = { onSelect(competition.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CompetitionCard(competition: CompetitionSummaryDto, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(competition.name, style = MaterialTheme.typography.titleMedium)
            val details = listOfNotNull(
                competition.date,
                competition.contactCity,
                competition.statusHuman,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
