package se.kjellstrand.markera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder

/**
 * The scored total with the pending series' caliber and tag, tappable to open the
 * recorder's dialogs. The scan screen and the wizard's Confirm step use this.
 */
@Composable
fun TotalBadge(total: Int) {
    val recorder = LocalSeriesRecorder.current
        ?: return TotalBadge(total, caliber = null, tag = null)
    val caliber by recorder.caliber.collectAsState()
    val tag by recorder.tag.collectAsState()
    TotalBadge(total, caliber.label, tag, recorder::openCaliberDialog, recorder::openTagDialog)
}

/**
 * The one result header: caliber | tag | total in one pill, shared by the scan
 * screen, wizard, Serie page and Historik. A null [caliber] leaves only the total;
 * a null click leaves that segment untappable; [compact] is the list-row size,
 * titling caliber/tag Cal./Tag instead of the full words (the total is Sum at both sizes).
 */
@Composable
fun TotalBadge(
    total: Int,
    caliber: String?,
    tag: String?,
    onCaliberClick: (() -> Unit)? = null,
    onTagClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            if (caliber != null) {
                PillSegment(
                    caliber.takeUnless { it.isBlank() || it == Caliber.NONE.label } ?: "–", onCaliberClick, compact,
                    caption = stringResource(if (compact) Res.string.badge_caliber_short else Res.string.badge_caliber),
                )
                VerticalDivider()
                // Capped: a 32-character tag must not push the total off the row.
                PillSegment(
                    tag?.takeUnless { it.isBlank() } ?: "–", onTagClick, compact, if (compact) 96.dp else 120.dp,
                    caption = stringResource(if (compact) Res.string.badge_tag_short else Res.string.badge_tag),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 2.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Labelled whenever caliber and tag are: a bare total needs no title.
                    if (caliber != null) PillCaption(
                        stringResource(Res.string.badge_total_short),
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = total.toString(),
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A caliber or tag section of the [TotalBadge] pill; tappable when [onClick] is set. */
@Composable
private fun PillSegment(
    label: String,
    onClick: (() -> Unit)?,
    compact: Boolean,
    maxWidth: Dp = Dp.Unspecified,
    caption: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = maxWidth)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (compact) 8.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (caption != null) PillCaption(caption, MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The short label above a [TotalBadge] segment's value. */
@Composable
private fun PillCaption(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}
