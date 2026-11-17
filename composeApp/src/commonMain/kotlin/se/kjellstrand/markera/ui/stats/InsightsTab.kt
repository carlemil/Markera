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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.stats.Advice
import se.kjellstrand.markera.series.stats.AdviceText
import se.kjellstrand.markera.series.stats.InsightMark
import se.kjellstrand.markera.series.stats.InsightSection
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.adviceFor
import se.kjellstrand.markera.series.stats.insights
import se.kjellstrand.markera.series.stats.mark
import se.kjellstrand.markera.ui.settings.ShootingHand

/**
 * The Insights tab (docs/coaching-plan.md, card 5): for the filtered [plotted] series, one section
 * per caliber with its findings, strongest first, worded by [adviceFor] for [hand]; a target with
 * the ellipse and the offset arrow; and plain words when there are too few shots or nothing to say.
 * The maths lives in `series/stats/Insights.kt`; this only lays it out.
 */
@Composable
internal fun InsightsTab(plotted: List<PlottedSeries>, calibers: List<Caliber>, hand: ShootingHand) {
    val insights = remember(plotted) { plotted.insights() }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (insights.sections.isEmpty()) {
            Note(stringResource(Res.string.stats_insights_no_caliber))
            return@Column
        }
        insights.sections.forEach { section ->
            // One caliber on show needs no heading: its dots and the filter row already say which.
            if (insights.sections.size > 1) CaliberHeading(section.caliber, calibers)
            InsightSectionView(section, calibers, hand, insights.neededHits)
        }
        if (insights.uncaliberedSeries > 0) {
            Text(
                stringResource(Res.string.stats_insights_uncalibered, insights.uncaliberedSeries),
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

@Composable
private fun InsightSectionView(section: InsightSection, calibers: List<Caliber>, hand: ShootingHand, needed: Int) {
    // Five holes are too few to judge a shape: no ellipse, no arrow, just how many are needed.
    if (!section.enoughData) {
        Note(stringResource(Res.string.stats_insights_need_more, needed, section.hitCount))
        return
    }
    val advice = remember(section, hand) { adviceFor(section.findings, hand) }
    // The picked finding, by its place in the list; tapping it again lets it go.
    var picked by remember(section) { mutableStateOf<Int?>(null) }
    val highlight = picked?.let { advice.getOrNull(it)?.finding?.mark() } ?: InsightMark.NONE
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightsTarget(section, calibers, highlight)
        Text(
            stringResource(Res.string.stats_insights_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (advice.isEmpty()) {
            val nothing = stringResource(Res.string.stats_insights_nothing, section.hitCount, section.series.size)
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
            AdviceCard(a, selected = picked == i) { picked = if (picked == i) null else i }
        }
    }
}

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
private fun AdviceCard(advice: Advice, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(advice.summary.resolve(), style = MaterialTheme.typography.bodyLarge)
            if (advice.causes.isNotEmpty()) {
                Label(stringResource(Res.string.advice_causes_title))
                advice.causes.forEach { cause ->
                    Text("•  " + cause.resolve(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Label(stringResource(Res.string.advice_try_title))
            Text(advice.tryThis.resolve(), style = MaterialTheme.typography.bodyMedium)
            advice.note?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

/**
 * The text of an [AdviceText]: a direction word ([StringResource]) is looked up and a date shown
 * as `yyyy-MM-dd`, the app's date format, before the format call, which only prints its arguments.
 */
@Composable
internal fun AdviceText.resolve(): String {
    val shown = args.map { arg ->
        when (arg) {
            is StringResource -> stringResource(arg)
            is LocalDate -> arg.toString()
            else -> arg
        }
    }
    return stringResource(res, *shown.toTypedArray())
}
