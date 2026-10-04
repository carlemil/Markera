package se.kjellstrand.markera.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.ui.SectionHeader
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.stats.Bucket
import se.kjellstrand.markera.series.stats.Metric
import se.kjellstrand.markera.series.stats.PlottedSeries
import se.kjellstrand.markera.series.stats.trend
import se.kjellstrand.markera.series.stats.trendByCaliber
import se.kjellstrand.markera.ui.AppChip

/** The Trend tab: which row, how the x axis buckets series, one line or one per caliber. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrendTab(
    plotted: List<PlottedSeries>,
    /** Every caliber on any plottable series: a caliber's colour is its slot here, whatever the filter keeps. */
    calibers: List<Caliber>,
    metric: Metric,
    onMetric: (Metric) -> Unit,
    bucket: Bucket,
    onBucket: (Bucket) -> Unit,
    splitOffered: Boolean,
    split: Boolean,
    onSplit: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(stringResource(Res.string.stats_group_metric))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric.entries.forEach {
                AppChip(
                    selected = metric == it,
                    onClick = { onMetric(it) },
                    label = stringResource(it.label()),
                )
            }
        }
        SectionHeader(stringResource(Res.string.stats_group_bucket))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Bucket.SERIES to Res.string.stats_bucket_series,
                Bucket.DAY to Res.string.stats_bucket_day,
                Bucket.WEEK to Res.string.stats_bucket_week,
                Bucket.MONTH to Res.string.stats_bucket_month,
            ).forEach { (value, label) ->
                AppChip(
                    selected = bucket == value,
                    onClick = { onBucket(value) },
                    label = stringResource(label),
                )
            }
            if (splitOffered) {
                AppChip(
                    selected = split,
                    onClick = { onSplit(!split) },
                    label = stringResource(Res.string.stats_split_caliber),
                )
            }
        }
    }
    val lines = remember(plotted, calibers, metric, bucket, split, splitOffered) {
        if (split && splitOffered) {
            plotted.trendByCaliber(metric, bucket).map { (caliber, points) ->
                TrendLine(caliber.label, caliberColour(caliber, calibers), points)
            }
        } else {
            listOf(TrendLine("", TREND_COLOURS[0], plotted.trend(metric, bucket)))
        }
    }
    // The ticks are drawn in a Canvas, so the template is resolved here and filled there.
    val mm = stringResource(Res.string.stats_mm)
    val mark = stringResource(Res.string.decimal_mark)
    val format: (Double) -> String = when (metric) {
        Metric.SCORE -> { v -> oneDecimal(v, mark) }
        else -> { v -> mm.replace("%1\$d", v.roundToInt().toString()) }
    }
    TrendChart(lines, format) { line, point ->
        // A bucket starts at midnight, so only a per-series point carries a time of day.
        val stamp = localStamp(point.at.toString()).let { if (bucket == Bucket.SERIES) it else it.take(10) }
        val value = format(point.value)
        if (point.seriesCount > 1) {
            stringResource(Res.string.stats_trend_point_many, line.label, stamp, value, point.seriesCount)
        } else {
            stringResource(Res.string.stats_trend_point, line.label, stamp, value)
        }.trim()
    }
}

/** Chip label: the short form of the measurement row's name. */
private fun Metric.label() = when (this) {
    Metric.MEAN_DISTANCE -> Res.string.stats_short_mean_distance
    Metric.MEAN_PAIRWISE -> Res.string.stats_short_mean_pairwise
    Metric.GROUP_SIZE -> Res.string.stats_short_group_size
    Metric.MEAN_RADIUS -> Res.string.stats_short_mean_radius
    Metric.RADIAL_SD -> Res.string.stats_short_radial_sd
    Metric.SCORE -> Res.string.stats_short_mean_score
}
