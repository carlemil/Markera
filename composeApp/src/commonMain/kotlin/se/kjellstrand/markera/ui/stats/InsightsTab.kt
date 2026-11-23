package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.stats.Advice
import se.kjellstrand.markera.series.stats.DEFAULT_DISTANCE_M
import se.kjellstrand.markera.series.stats.Finding
import se.kjellstrand.markera.series.stats.SightCorrection
import se.kjellstrand.markera.series.stats.SightSetup
import se.kjellstrand.markera.series.stats.parsePositiveDecimal
import se.kjellstrand.markera.series.stats.sightCorrection
import se.kjellstrand.markera.series.stats.InsightMark
import se.kjellstrand.markera.series.stats.InsightSection
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.adviceFor
import se.kjellstrand.markera.series.stats.insights
import se.kjellstrand.markera.series.stats.mark
import se.kjellstrand.markera.ui.history.adviceString
import se.kjellstrand.markera.ui.settings.AppSettings
import se.kjellstrand.markera.ui.settings.LocalAppSettings
import se.kjellstrand.markera.ui.settings.ShootingHand

/**
 * The Insights tab (docs/coaching-plan.md, card 5): for the filtered [plotted] series, one section
 * per caliber with its findings, strongest first, worded by [adviceFor] for [hand]; a target with
 * the ellipse and the offset arrow; and plain words when there are too few shots or nothing to say.
 * The maths lives in `series/stats/Insights.kt`; this only lays it out.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun InsightsTab(
    plotted: List<PlottedSeries>,
    calibers: List<Caliber>,
    hand: ShootingHand,
    viewEnd: Instant? = null,
) {
    val settings = LocalAppSettings.current
    val sights = settings?.sightSetups?.collectAsState()?.value ?: emptyMap()
    val insights = remember(plotted, sights, viewEnd) { plotted.insights(sights = sights, viewEnd = viewEnd) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (insights.sections.isEmpty()) {
            Note(stringResource(Res.string.stats_insights_no_caliber))
            return@Column
        }
        insights.sections.forEach { section ->
            // One caliber on show needs no heading: its dots and the filter row already say which.
            // Keyed, so a target's zoom and a picked finding stay with their caliber.
            key(section.caliber.label) {
                if (insights.sections.size > 1) CaliberHeading(section.caliber, calibers)
                InsightSectionView(section, calibers, hand, insights.neededHits, settings)
            }
        }
        if (insights.uncaliberedSeries > 0) {
            Text(
                pluralStringResource(Res.plurals.stats_insights_uncalibered, insights.uncaliberedSeries, insights.uncaliberedSeries),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CaliberHeading(caliber: Caliber, calibers: List<Caliber>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(caliberColour(caliber, calibers), CircleShape))
        Text(caliber.label, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun InsightSectionView(
    section: InsightSection,
    calibers: List<Caliber>,
    hand: ShootingHand,
    needed: Int,
    settings: AppSettings?,
) {
    // Five holes are too few to judge a shape: no ellipse, no arrow, just how many are needed.
    if (!section.enoughData) {
        Note(pluralStringResource(Res.plurals.stats_insights_need_more, needed, needed, section.hitCount))
        return
    }
    val advice = remember(section, hand) { adviceFor(section.findings, hand) }
    // The picked finding, by its place in the list; tapping it again lets it go.
    // Keyed on the caliber: a sight edit rebuilds the section but keeps the pick.
    var picked by remember(section.caliber.label) { mutableStateOf<Int?>(null) }
    var editingClicks by remember { mutableStateOf(false) }
    val highlight = picked?.let { advice.getOrNull(it)?.finding?.mark() } ?: InsightMark.NONE
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightsTarget(section, calibers, highlight)
        Text(
            stringResource(Res.string.stats_insights_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        section.adjustedAt?.let { at ->
            AdjustedNote(at, section.offsetHitCount) { settings?.undoSightsAdjusted(section.caliber.label) }
        }
        if (advice.isEmpty()) {
            val nothing = pluralStringResource(
                Res.plurals.stats_insights_nothing,
                section.hitCount,
                section.hitCount,
                pluralStringResource(Res.plurals.stats_insights_nothing_series, section.series.size, section.series.size),
            )
            val numbers = section.stats?.let {
                stringResource(
                    Res.string.stats_insights_numbers,
                    impactArrows(it.impactXMm, it.impactYMm),
                    it.radialSdMm.roundToInt(),
                    oneDecimal(it.meanScore, stringResource(Res.string.decimal_mark)),
                )
            }
            Note(if (numbers == null) nothing else "$nothing\n$numbers")
        }
        advice.forEachIndexed { i, a ->
            AdviceCard(a, selected = picked == i, onClick = { picked = if (picked == i) null else i }) {
                val offset = a.finding as? Finding.OffsetGroup
                if (offset != null && offset.stable) {
                    SightCorrectionView(
                        sightCorrection(offset, section.sight),
                        mmPerClick = section.sight?.mmPerClick,
                        canEdit = settings != null,
                        onEditClicks = { editingClicks = true },
                        onAdjusted = { settings?.markSightsAdjusted(section.caliber.label, nowMs()) },
                    )
                }
            }
        }
    }
    if (editingClicks && settings != null) {
        ClickValueDialog(
            caliber = section.caliber.label,
            setup = section.sight,
            onDismiss = { editingClicks = false },
            onSave = { distance, perClick ->
                settings.setSightClicks(section.caliber.label, distance, perClick)
                editingClicks = false
            },
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Under a stable offset: how far, which way and (with a click value) how many clicks to move the sights. */
@Composable
private fun SightCorrectionView(
    correction: SightCorrection,
    mmPerClick: Double?,
    canEdit: Boolean,
    onEditClicks: () -> Unit,
    onAdjusted: () -> Unit,
) {
    val mark = stringResource(Res.string.decimal_mark)
    Column(Modifier.testTag(SIGHT_CORRECTION_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(stringResource(Res.string.sight_title))
        Text(axisLine(correction.upMm, correction.upClicks, vertical = true), style = MaterialTheme.typography.bodyMedium)
        Text(axisLine(correction.rightMm, correction.rightClicks, vertical = false), style = MaterialTheme.typography.bodyMedium)
        val rule = stringResource(Res.string.sight_rule)
        val basis = if (correction.hasClicks && mmPerClick != null) {
            stringResource(Res.string.sight_clicks_basis, plainNumber(correction.distanceM, mark), plainNumber(mmPerClick, mark))
        } else {
            stringResource(Res.string.sight_hint)
        }
        Text("$rule $basis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEditClicks) {
                    Text(stringResource(if (correction.hasClicks) Res.string.sight_change_clicks else Res.string.sight_set_clicks))
                }
                OutlinedButton(onClick = onAdjusted) { Text(stringResource(Res.string.sight_adjusted_button)) }
            }
        }
    }
}

/** One axis: "Up 12 mm: 2 clicks", or no change when it rounds to 0 mm. */
@Composable
private fun axisLine(mm: Int, clicks: Int?, vertical: Boolean): String {
    if (mm == 0) return stringResource(if (vertical) Res.string.sight_no_height else Res.string.sight_no_side)
    val move = stringResource(
        when {
            vertical && mm > 0 -> Res.string.sight_up_mm
            vertical -> Res.string.sight_down_mm
            mm > 0 -> Res.string.sight_right_mm
            else -> Res.string.sight_left_mm
        },
        abs(mm),
    )
    return when (val n = clicks?.let { abs(it) }) {
        null -> move
        0 -> stringResource(Res.string.sight_under_half_click, move)
        1 -> stringResource(Res.string.sight_one_click, move)
        else -> pluralStringResource(Res.plurals.sight_clicks, n, move, n)
    }
}

/** A typed-in value back as text: no ".0" on a whole number, the language's decimal mark. */
internal fun plainNumber(v: Double, mark: String): String {
    val thousandths = kotlin.math.round(v * 1000).toLong()
    val whole = thousandths / 1000
    val frac = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole$mark$frac"
}

/** "Sights adjusted <date>": the offset now looks only at later shots. Undo forgets the date. */
@OptIn(ExperimentalTime::class)
@Composable
private fun AdjustedNote(at: Instant, shots: Int, onUndo: () -> Unit) {
    val date = at.toLocalDateTime(TimeZone.currentSystemDefault()).date
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(Res.plurals.sight_adjusted_note, shots, date.toString(), shots),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) { Text(stringResource(Res.string.sight_adjusted_undo)) }
    }
}

/** The caliber's shooting distance and mm per click; Remove clears the click value. */
@Composable
private fun ClickValueDialog(
    caliber: String,
    setup: SightSetup?,
    onDismiss: () -> Unit,
    onSave: (distanceM: Double, mmPerClick: Double?) -> Unit,
) {
    val mark = stringResource(Res.string.decimal_mark)
    var distance by remember { mutableStateOf(plainNumber(setup?.distanceM ?: DEFAULT_DISTANCE_M, mark)) }
    var perClick by remember { mutableStateOf(setup?.mmPerClick?.let { plainNumber(it, mark) } ?: "") }
    val distanceValue = parsePositiveDecimal(distance)
    val clickValue = parsePositiveDecimal(perClick)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sight_dialog_title, caliber)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sight_dialog_help), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = distance,
                    onValueChange = { distance = it },
                    label = { Text(stringResource(Res.string.sight_dialog_distance)) },
                    isError = distanceValue == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(SIGHT_DISTANCE_TAG),
                )
                OutlinedTextField(
                    value = perClick,
                    onValueChange = { perClick = it },
                    label = { Text(stringResource(Res.string.sight_dialog_click)) },
                    isError = perClick.isNotBlank() && clickValue == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(SIGHT_CLICK_TAG),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (distanceValue != null && clickValue != null) onSave(distanceValue, clickValue) },
                enabled = distanceValue != null && clickValue != null,
            ) { Text(stringResource(Res.string.sight_dialog_save)) }
        },
        dismissButton = {
            Row {
                if (setup?.mmPerClick != null) {
                    TextButton(onClick = { onSave(setup.distanceM, null) }) { Text(stringResource(Res.string.sight_dialog_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
            }
        },
    )
}

internal const val SIGHT_CORRECTION_TAG = "sightCorrection"
internal const val SIGHT_DISTANCE_TAG = "sightDistance"
internal const val SIGHT_CLICK_TAG = "sightClick"

/** A plain bordered box of text: the too-few-shots, nothing-found and no-caliber states. */
@Composable
private fun Note(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** One finding: what the numbers show, the usual causes, one thing to try and any note. Tap to pick it. */
@Composable
private fun AdviceCard(advice: Advice, selected: Boolean, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(adviceString(advice.summary), style = MaterialTheme.typography.bodyLarge)
            if (advice.causes.isNotEmpty()) {
                Label(stringResource(Res.string.advice_causes_title))
                advice.causes.forEach { cause ->
                    Text("•  " + adviceString(cause), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Label(stringResource(Res.string.advice_try_title))
            Text(adviceString(advice.tryThis), style = MaterialTheme.typography.bodyMedium)
            advice.note?.let {
                Text(adviceString(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            extra()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
