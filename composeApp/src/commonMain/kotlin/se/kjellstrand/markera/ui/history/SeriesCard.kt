package se.kjellstrand.markera.ui.history

import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.decodeSeriesJpeg
import se.kjellstrand.markera.series.localTime
import se.kjellstrand.markera.series.scorePicks
import se.kjellstrand.markera.series.scorePicksByHand
import se.kjellstrand.markera.series.total
import se.kjellstrand.markera.ui.markera.ScoreMiniRow
import se.kjellstrand.markera.ui.TotalBadge

/** The list thumbnail is ~115 dp square; the stored frame is ~3000², so subsample hard. */
private const val THUMB_MAX_DIM = 384

/** Score boxes on a card; the Serie page shows every hole. */
private const val CARD_HITS = 5

@Composable
internal fun SeriesCard(
    series: SeriesDto,
    /** Which series of its day this was, counted from the first one shot. */
    ordinal: Int,
    services: SeriesServices,
    onClick: () -> Unit,
    /** Null (Home) = no long-press delete. */
    onLongPress: (() -> Unit)? = null,
) {
    // Held by the card, so a card scrolled out of the list lets its bitmap go; coming
    // back decodes the small JPEG cached on disk after the first fetch (kilobytes).
    val thumbnail by produceState<ImageBitmap?>(null, series.id, series.hasImage) {
        if (!series.hasImage) return@produceState
        val bytes = services.repository.thumbnail(series.id, THUMB_MAX_DIM) ?: return@produceState
        value = decodeSeriesJpeg(bytes, THUMB_MAX_DIM)
    }
    OutlinedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(Res.string.history_delete_confirm),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            if (series.hasImage) {
                // The Box owns the slot so the row height comes from the text column, not
                // the bitmap, and nothing reflows when the async decode lands. Its width
                // follows that height, so the photo is square whatever the text needs.
                // Flush with the card edge; the card's own shape clips the corners.
                Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                    thumbnail?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The day itself is the group header above this card.
                Text(
                    stringResource(Res.string.history_card_title, localTime(series.timestamp), ordinal),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // Display only: the card itself opens the Serie page, where these are edited.
                // Orange like the photo's hand-placed marker: what the user changed.
                // At most the five best (scorePicks is best first), so a card keeps one row.
                if (series.holes.isNotEmpty()) {
                    ScoreMiniRow(
                        series.scorePicks().take(CARD_HITS),
                        manual = series.scorePicksByHand().take(CARD_HITS),
                    )
                }
                TotalBadge(series.total(), series.caliber, series.tag, compact = true)
            }
        }
    }
}
