package se.kjellstrand.markera.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.stats.AdviceText
import se.kjellstrand.markera.series.stats.Finding
import se.kjellstrand.markera.series.stats.SeriesComparison
import se.kjellstrand.markera.series.stats.advice
import se.kjellstrand.markera.ui.settings.ShootingHand

const val COMPARISON_LINE_TAG = "comparison-line"
const val COMPARISON_DAY_LINE_TAG = "comparison-day-line"

/**
 * The quiet line(s) under the total on the Serie page: [comparison] against the shooter's average
 * and, when the day has a finding, its one-sentence summary, tappable when [onOpenInsights] is set.
 */
@Composable
internal fun ComparisonLines(
    comparison: SeriesComparison?,
    day: Pair<LocalDate, Finding>?,
    hand: ShootingHand,
    onOpenInsights: ((LocalDate) -> Unit)?,
) {
    if (comparison == null && day == null) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (comparison != null) {
            Text(
                comparisonText(comparison),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(COMPARISON_LINE_TAG),
            )
        }
        if (day != null) {
            val (date, finding) = day
            val link = onOpenInsights
            Text(
                adviceString(advice(finding, hand).summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).testTag(COMPARISON_DAY_LINE_TAG),
            )
            if (link != null) {
                Text(
                    stringResource(Res.string.detail_day_in_insights),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { link(date) },
                )
            }
        }
    }
}

@Composable
private fun comparisonText(c: SeriesComparison): String = when (c) {
    is SeriesComparison.Under -> stringResource(Res.string.detail_vs_under, c.points, c.average)
    is SeriesComparison.Over -> stringResource(Res.string.detail_vs_over, c.points, c.average)
    is SeriesComparison.OnAverage -> stringResource(Res.string.detail_vs_on, c.average)
    is SeriesComparison.Best -> stringResource(Res.string.detail_vs_best, c.days)
}

/** Resolves direction words and dates in [text]'s arguments, as [AdviceText] asks of its renderer. */
@Composable
internal fun adviceString(text: AdviceText): String {
    val args = text.args.map {
        when (it) {
            is StringResource -> stringResource(it)
            is LocalDate -> it.toString()
            else -> it
        }
    }
    return stringResource(text.res, *args.toTypedArray())
}
