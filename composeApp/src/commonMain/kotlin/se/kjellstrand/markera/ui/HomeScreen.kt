package se.kjellstrand.markera.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.BackendAuth
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.stats.WeekSummary
import se.kjellstrand.markera.series.stats.weekSummary
import se.kjellstrand.markera.ui.stats.oneDecimal
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import se.kjellstrand.markera.ui.history.SeriesCard
import se.kjellstrand.markera.ui.history.dayOrdinals
import se.kjellstrand.markera.ui.markera.ImageSource
import se.kjellstrand.markera.ui.markera.PickedImage
import se.kjellstrand.markera.ui.markera.ScanChips
import se.kjellstrand.markera.ui.markera.rememberImagePicker
import se.kjellstrand.markera.ui.history.adviceString
import se.kjellstrand.markera.ui.settings.LocalAppSettings
import se.kjellstrand.markera.ui.settings.ShootingHand
import se.kjellstrand.markera.ui.stats.HomeInsight
import se.kjellstrand.markera.ui.stats.InsightsDay
import se.kjellstrand.markera.ui.stats.firstShown
import se.kjellstrand.markera.ui.stats.headline
import se.kjellstrand.markera.ui.stats.homeInsights
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Start screen: free marking (standalone scanner) or competition marking. */
@OptIn(ExperimentalTime::class)
@Composable
internal fun HomeScreen(
    onFreeMarking: () -> Unit,
    /** Null hides the button: the platform supplied no competition flow. */
    onCompetition: (() -> Unit)?,
    onHistory: () -> Unit,
    onOpenSeries: (SeriesDto) -> Unit,
    onStatistics: () -> Unit,
    /** The insight card was tapped: Insights on its caliber and period. */
    onOpenInsights: (InsightsDay) -> Unit,
    /** The picked images and whether Auto import is on. */
    onImport: (List<PickedImage>, Boolean) -> Unit,
    backendAuth: BackendAuth?,
    seriesServices: SeriesServices,
) {
    var showingHelp by remember { mutableStateOf(false) }
    var showingImport by remember { mutableStateOf(false) }
    // Read when the system picker returns, so it is the switch as the dialog was left.
    var importAuto by remember { mutableStateOf(false) }
    val pickImages = rememberImagePicker { onImport(it, importAuto) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // No top bar here, so the menu floats in the top-left corner.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Centred while it fits, scrolled when it doesn't (a small phone, a large font).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(48.dp))
                Button(onClick = onFreeMarking, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(Res.string.home_free_marking), style = MaterialTheme.typography.titleLarge)
                }
                val cached by seriesServices.repository.series.collectAsState()
                // Nothing shot in the last seven days: the strip just isn't there.
                val week = remember(cached) { cached.weekSummary(Clock.System.now()) }
                if (week != null) {
                    Spacer(Modifier.height(16.dp))
                    WeekStrip(week, onStatistics)
                }
                // The strongest finding of the week, unless Insights is off or the user hid it.
                val settings = LocalAppSettings.current
                val insightsOn = settings?.insightsEnabled?.collectAsState()?.value ?: true
                val dismissed = settings?.dismissedInsights?.collectAsState()?.value ?: emptyList()
                val hand = settings?.shootingHand?.collectAsState()?.value ?: ShootingHand.RIGHT
                // Every caliber's findings, twice: off the main thread, so Home never waits on it.
                val candidates by produceState(emptyList<HomeInsight>(), cached, insightsOn) {
                    value = if (insightsOn) {
                        withContext(Dispatchers.Default) { cached.homeInsights(Clock.System.now()) }
                    } else {
                        emptyList()
                    }
                }
                val insight = candidates.firstShown(dismissed)
                if (insight != null) {
                    Spacer(Modifier.height(12.dp))
                    InsightCard(
                        insight,
                        hand,
                        onClick = { onOpenInsights(insight.day) },
                        onDismiss = { settings?.dismissInsight(insight.key) },
                    )
                }
                // Nothing saved yet: the card just isn't there.
                val latest = cached.firstOrNull() // Series.sq orders by timestamp DESC
                if (latest != null) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(Res.string.home_latest_series, localStamp(latest.timestamp).take(10)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    SeriesCard(
                        series = latest,
                        ordinal = remember(cached) { cached.dayOrdinals() }[latest.id] ?: 1,
                        services = seriesServices,
                        onClick = { onOpenSeries(latest) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeButton(Icons.Default.History, stringResource(Res.string.home_history), onHistory)
                    HomeButton(Icons.Default.BarChart, stringResource(Res.string.home_stats), onStatistics)
                    if (onCompetition != null) {
                        HomeButton(Icons.Default.EmojiEvents, stringResource(Res.string.home_competition), onCompetition)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Sign out and Delete account live in Settings; here is only the reason to sign in.
                if (backendAuth == null) {
                    SignInCard(seriesServices, localCount = cached.count { it.local != null })
                } else {
                    Text(
                        stringResource(
                            Res.string.home_signed_in,
                            backendAuth.provider.replaceFirstChar { it.uppercase() },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AppMenu(
                items = listOf(
                    MenuItem(Icons.Default.PhotoLibrary, stringResource(Res.string.import_images)) {
                        showingImport = true
                    },
                    MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) {
                        showingHelp = true
                    },
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    // + the menu's own 4 dp = 16 dp from the edges.
                    .padding(12.dp),
            )
        }
    }

    if (showingImport) {
        ImportDialog(
            onPick = { source, auto ->
                showingImport = false
                importAuto = auto
                pickImages(source)
            },
            onDismiss = { showingImport = false },
        )
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_home_title),
            sections = listOf(
                Res.string.help_home_how to Res.string.help_home_how_body,
                Res.string.help_home_marking to Res.string.help_home_marking_body,
                Res.string.help_home_import to Res.string.help_home_import_body,
                Res.string.help_home_history to Res.string.help_home_history_body,
                Res.string.help_home_stats to Res.string.help_home_stats_body,
                Res.string.help_home_account to Res.string.help_home_account_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }
}

/** The Auto import switch as last left, for the rest of the session. */
private var autoImportChoice = false

/**
 * Before the system picker: where the images come from, Auto import (saving
 * needs an account, so it is off and disabled signed out) and the caliber and
 * tag the series get.
 */
@Composable
private fun ImportDialog( onPick: (ImageSource, Boolean) -> Unit, onDismiss: () -> Unit) {
    var auto by remember { mutableStateOf(autoImportChoice) }
    val on = auto
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.import_images)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ScanChips()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) {
                        auto = it
                        autoImportChoice = it
                    },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.import_auto), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.import_auto_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(ImageSource.GALLERY, on) }) { Text(stringResource(Res.string.import_gallery)) }
        },
        dismissButton = {
            TextButton(onClick = { onPick(ImageSource.FILES, on) }) { Text(stringResource(Res.string.import_files)) }
        },
    )
}

/** The last seven days at a glance; a tap opens Statistik. */
@Composable
private fun WeekStrip(week: WeekSummary, onClick: () -> Unit) {
    val mark = stringResource(Res.string.decimal_mark)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(vertical = 10.dp)) {
            WeekStat(week.count.toString(), stringResource(Res.string.home_week_series))
            WeekStat(oneDecimal(week.average, mark), stringResource(Res.string.home_week_average))
            WeekStat(week.best.toString(), stringResource(Res.string.home_week_best))
        }
    }
}

/** The test tag of Home's insight card. */
const val HOME_INSIGHT_TAG = "home-insight"

/** One finding in one line: a tap opens Insights on it, the cross hides it until its data changes. */
@Composable
private fun InsightCard(insight: HomeInsight, hand: ShootingHand, onClick: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(HOME_INSIGHT_TAG),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                adviceString(insight.headline(hand)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(Res.string.home_insight_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.WeekStat(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One of Home's equal-width list buttons; icon above the label so three fit in Swedish. */
@Composable
private fun RowScope.HomeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
