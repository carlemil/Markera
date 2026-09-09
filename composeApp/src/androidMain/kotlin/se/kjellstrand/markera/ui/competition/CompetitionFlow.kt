package se.kjellstrand.markera.ui.competition

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import se.kjellstrand.markera.ui.CompetitionHost
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.TargetScanController
import se.kjellstrand.markera.webshooter.WebshooterServices

/**
 * The webshooter marking flow: login → competitions → groups → wizard. Hidden
 * until it is ready (PLAN "wizard-edit follow-up").
 */
const val SHOW_COMPETITION = false

/** Null when the flow is off, and then the nav host shows no competition card. */
@Composable
fun rememberCompetitionHost(): CompetitionHost? {
    if (!SHOW_COMPETITION) return null
    val context = LocalContext.current
    val services = remember { WebshooterServices(context) }
    // Restored at app start, so the flow can skip its login screen when opened.
    LaunchedEffect(services) { services.sessionRepository.restore() }
    return remember {
        CompetitionHost { frameSource, scanController, onExit ->
            CompetitionFlow(services, frameSource, scanController, onExit)
        }
    }
}

/** The flow's own back stack; [onExit] returns to the nav host's screen below. */
private sealed interface Step {
    data object Login : Step
    data object Competitions : Step
    data class MarkingGroups(val competitionId: Int) : Step
    data class MarkingWizard(
        val competitionId: Int,
        val groupGuid: String,
        val groupName: String,
    ) : Step
}

@Composable
private fun CompetitionFlow(
    services: WebshooterServices,
    frameSource: FrameSource,
    scanController: TargetScanController,
    onExit: () -> Unit,
) {
    // A persisted login skips straight past the login screen.
    var stack by remember {
        mutableStateOf<List<Step>>(
            listOf(
                if (services.sessionRepository.session.value != null) {
                    Step.Competitions
                } else {
                    Step.Login
                }
            )
        )
    }
    val push: (Step) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }

    // The innermost enabled handler wins, so this pops the flow before the nav
    // host's own handler pops the flow off its stack.
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val step = stack.last()) {
        Step.Login -> LoginScreen(
            services = services,
            onLoggedIn = { stack = stack.dropLast(1) + Step.Competitions },
            onBack = pop,
        )

        Step.Competitions -> CompetitionListScreen(
            services = services,
            onBack = pop,
            onLoggedOut = onExit,
            onSelect = { push(Step.MarkingGroups(it)) },
        )

        is Step.MarkingGroups -> MarkingGroupsScreen(
            services = services,
            competitionId = step.competitionId,
            onBack = pop,
            onOpenGroup = { group ->
                push(Step.MarkingWizard(step.competitionId, group.guid, group.name))
            },
        )

        is Step.MarkingWizard -> MarkingWizardScreen(
            services = services,
            frameSource = frameSource,
            scanController = scanController,
            competitionId = step.competitionId,
            groupGuid = step.groupGuid,
            groupName = step.groupName,
            onExit = pop,
        )
    }
}
