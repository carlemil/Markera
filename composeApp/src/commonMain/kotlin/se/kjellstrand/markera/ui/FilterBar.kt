package se.kjellstrand.markera.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
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
 * Caliber, tag and date filters for Historik and Statistik, folded into one line
 * (a Filter chip, what is picked, Clear) until opened: then one labelled row each
 * that scrolls sideways, so the block stays three rows tall however many calibers
 * and tags there are. A caliber or tag picked earlier keeps its chip
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
    // Folded by default: the list or chart below is what the screen is for.
    var open by rememberSaveable { mutableStateOf(false) }
    val active = filter != HistoryFilter()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(
                selected = active,
                onClick = { open = !open },
                label = stringResource(Res.string.filter_title),
                leadingIcon = {
                    Icon(
                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Text(
                filter.summary(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                TextButton(onClick = { onFilter(HistoryFilter()) }) {
                    Text(stringResource(Res.string.filter_clear))
                }
            }
        }
        if (open) {
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
        }
    }
}

/** The folded bar's one line: what is picked, or that nothing is. */
@Composable
private fun HistoryFilter.summary(): String {
    val parts = calibers.sortedBy { it.ordinal }.map { if (it == Caliber.NONE) "–" else it.label } +
        tags.sorted() +
        listOfNotNull(
            when (preset) {
                DatePreset.ALL -> null
                DatePreset.CUSTOM -> customRange?.let {
                    stringResource(Res.string.stats_date_range, utcDay(it.first), utcDay(it.second))
                }
                DatePreset.TODAY -> stringResource(Res.string.stats_date_today)
                DatePreset.WEEK -> stringResource(Res.string.stats_date_week)
                DatePreset.MONTH -> stringResource(Res.string.stats_date_month)
                DatePreset.YEAR -> stringResource(Res.string.stats_date_year)
            },
        )
    return if (parts.isEmpty()) stringResource(Res.string.filter_all) else parts.joinToString(" · ")
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
