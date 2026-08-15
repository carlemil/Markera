package se.kjellstrand.markera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.kjellstrand.markera.R
import se.kjellstrand.markera.ui.competition.CompetitionListScreen
import se.kjellstrand.markera.ui.competition.LoginScreen
import se.kjellstrand.markera.ui.competition.MarkingGroupsScreen
import se.kjellstrand.markera.ui.competition.MarkingWizardScreen
import se.kjellstrand.markera.ui.markera.MarkeraScreen
import se.kjellstrand.markera.ui.markera.rememberFrameSource
import se.kjellstrand.markera.ui.markera.rememberTargetScanController
import se.kjellstrand.markera.webshooter.WebshooterServices

/** The app's screens; a simple list-backed stack, no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object FreeMarking : Screen
    data object Login : Screen
    data object Competitions : Screen
    data class MarkingGroups(val competitionId: Int) : Screen
    data class MarkingWizard(
        val competitionId: Int,
        val groupGuid: String,
        val groupName: String,
    ) : Screen
}

/**
 * Navigation root. The frame source and the scan controller (the single ONNX
 * session) live here, above the back stack, so both the free-marking screen
 * and the competition wizard share them and nothing heavy is rebuilt per
 * screen switch.
 */
@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val services = remember { WebshooterServices(context) }
    val frameSource = rememberFrameSource()
    val scanController = rememberTargetScanController()

    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val current = stack.last()
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Restore a persisted login once at startup.
    LaunchedEffect(Unit) { services.sessionRepository.restore() }
    val session by services.sessionRepository.session.collectAsState()

    when (val screen = current) {
        Screen.Home -> HomeScreen(
            onFreeMarking = { push(Screen.FreeMarking) },
            onCompetition = {
                push(if (session != null) Screen.Competitions else Screen.Login)
            },
        )

        Screen.FreeMarking -> MarkeraScreen(
            frameSource = frameSource,
            scanController = scanController,
            onBack = pop,
        )

        Screen.Login -> LoginScreen(
            services = services,
            onLoggedIn = { stack = stack.dropLast(1) + Screen.Competitions },
            onBack = pop,
        )

        Screen.Competitions -> CompetitionListScreen(
            services = services,
            onBack = pop,
            onLoggedOut = { stack = listOf(Screen.Home) },
            onSelect = { push(Screen.MarkingGroups(it)) },
        )

        is Screen.MarkingGroups -> MarkingGroupsScreen(
            services = services,
            competitionId = screen.competitionId,
            onBack = pop,
            onOpenGroup = { group ->
                push(Screen.MarkingWizard(screen.competitionId, group.guid, group.name))
            },
        )

        is Screen.MarkingWizard -> MarkingWizardScreen(
            services = services,
            frameSource = frameSource,
            scanController = scanController,
            competitionId = screen.competitionId,
            groupGuid = screen.groupGuid,
            groupName = screen.groupName,
            onExit = pop,
        )
    }
}

/** Start screen: free marking (standalone scanner) or competition marking. */
@Composable
private fun HomeScreen(
    onFreeMarking: () -> Unit,
    onCompetition: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(48.dp))
            HomeCard(
                title = stringResource(R.string.home_free_marking),
                subtitle = stringResource(R.string.home_free_marking_hint),
                icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(36.dp)) },
                onClick = onFreeMarking,
            )
            Spacer(Modifier.height(16.dp))
            HomeCard(
                title = stringResource(R.string.home_competition),
                subtitle = stringResource(R.string.home_competition_hint),
                icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null, modifier = Modifier.size(36.dp)) },
                onClick = onCompetition,
            )
        }
    }
}

@Composable
private fun HomeCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
