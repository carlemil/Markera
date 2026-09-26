package se.kjellstrand.markera.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.stats.DatePreset
import se.kjellstrand.markera.series.utcDay
import se.kjellstrand.markera.ui.history.HistoryFilter

/**
 * Caliber, tag and date filters for Historik and Statistik: one labelled row
 * each that scrolls sideways, so the block stays three rows tall however many
 * calibers and tags there are. A caliber or tag picked earlier keeps its chip
 * after it drops out of [calibers]/[tags], so it stays deselectable.
 */
@Composable
fun FilterBar(
    calibers: List<Caliber>,
    tags: List<String>,
    filter: HistoryFilter,
    onFilter: (HistoryFilter) -> Unit,
    onPickDates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = stringResource(Res.string.stats_caliber_all)
    Column(modifier) {
        FilterLine(Res.string.stats_group_caliber) {
            AppChip(filter.calibers.isEmpty(), { onFilter(filter.copy(calibers = emptySet())) }, all)
            (calibers + filter.calibers).distinct().sortedBy { it.ordinal }.forEach { c ->
                AppChip(
                    selected = c in filter.calibers,
                    onClick = { onFilter(filter.copy(calibers = filter.calibers.toggle(c))) },
                    label = if (c == Caliber.NONE) "–" else c.label,
                )
            }
        }
        // No tag anywhere (and none selected) means no row at all.
        val offeredTags = (tags + filter.tags).distinct().sorted()
        if (offeredTags.isNotEmpty()) {
            FilterLine(Res.string.stats_group_tag) {
                AppChip(filter.tags.isEmpty(), { onFilter(filter.copy(tags = emptySet())) }, all)
                offeredTags.forEach { tag ->
                    AppChip(tag in filter.tags, { onFilter(filter.copy(tags = filter.tags.toggle(tag))) }, tag)
                }
            }
        }
        FilterLine(Res.string.stats_group_date) {
            val preset = @Composable { value: DatePreset, label: StringResource ->
                AppChip(filter.preset == value, { onFilter(filter.copy(preset = value)) }, stringResource(label))
            }
            preset(DatePreset.ALL, Res.string.stats_date_all)
            AppChip(
                selected = filter.preset == DatePreset.CUSTOM,
                onClick = onPickDates,
                label = filter.customRange?.takeIf { filter.preset == DatePreset.CUSTOM }
                    ?.let { stringResource(Res.string.stats_date_range, utcDay(it.first), utcDay(it.second)) }
                    ?: stringResource(Res.string.stats_date_custom),
            )
            preset(DatePreset.TODAY, Res.string.stats_date_today)
            preset(DatePreset.WEEK, Res.string.stats_date_week)
            preset(DatePreset.MONTH, Res.string.stats_date_month)
            preset(DatePreset.YEAR, Res.string.stats_date_year)
        }
        if (filter != HistoryFilter()) {
            TextButton(onClick = { onFilter(HistoryFilter()) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.filter_clear))
            }
        }
    }
}

@Composable
private fun FilterLine(title: StringResource, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(title),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { chips() }
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
