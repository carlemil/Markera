package se.kjellstrand.markera.ui.history

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import se.kjellstrand.markera.R
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesRequest
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.centre
import se.kjellstrand.markera.series.detectedLabel
import se.kjellstrand.markera.series.isEdited
import se.kjellstrand.markera.series.kindText
import se.kjellstrand.markera.series.manualLabel
import se.kjellstrand.markera.series.nearestHoleIndex
import se.kjellstrand.markera.series.pickInnerTen
import se.kjellstrand.markera.series.pickRing
import se.kjellstrand.markera.series.ring
import se.kjellstrand.markera.series.withDetectedScore
import se.kjellstrand.markera.series.withNewHole
import se.kjellstrand.markera.ui.competition.CompetitionTopBar
import se.kjellstrand.markera.ui.markera.DetectionOverlay
import se.kjellstrand.markera.ui.markera.PrimaryActionButton
import se.kjellstrand.markera.ui.markera.ScoreDialpadDialog
import se.kjellstrand.markera.ui.markera.photoGestures
import se.kjellstrand.markera.ui.markera.rememberZoomPan
import se.kjellstrand.markera.ui.markera.zoomPan
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.distanceMm

/** Same greens/oranges the live overlay uses for detected vs. hand-placed holes. */
private val DETECTED_COLOR = Color(0xFF9CCC65)
private val MANUAL_COLOR = Color(0xFFFFB74D)

/** How close a drag has to start to a marker to grab it. */
private val GRAB_RADIUS = 24.dp

/**
 * One saved series: the scanned photo with a marker per positioned hole, the
 * hole list, and editing — tap a row to change its score, tap the photo to add
 * a hole, drag a marker to move it, pinch to zoom in first. "Spara" PUTs the
 * whole series back; going back discards.
 */
@Composable
fun SeriesDetailScreen(series: SeriesDto, services: SeriesServices, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A plain State (not `by`), so the drag gesture — which is not recomposed —
    // reads and writes the current list.
    val holes = remember(series.id) { mutableStateOf(series.holes) }
    var photo by remember(series.id) { mutableStateOf<ImageBitmap?>(null) }
    var editing by remember { mutableIntStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val zoomPan = rememberZoomPan(series.id)
    val savedText = stringResource(R.string.detail_saved)
    val failedText = stringResource(R.string.detail_save_failed)
    val deleteFailedText = stringResource(R.string.history_delete_failed)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }

    LaunchedEffect(series.id) {
        if (!series.hasImage) return@LaunchedEffect
        try {
            val bytes = services.api.getSeriesImage(series.id)
            photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Throwable) {
            // No photo is the whole fallback; the list still works.
        }
    }

    // Without the source-frame size the hole pixels mean nothing against the
    // stored JPEG, so the overlay (and dragging) stay off for such series.
    val imageW = series.imageWidth ?: 0
    val imageH = series.imageHeight ?: 0

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            CompetitionTopBar(
                title = stringResource(R.string.detail_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.history_delete_confirm),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        // Pinch to zoom, drag a marker to move it, tap empty
                        // target to add a hole — the same loop the scan screen
                        // uses (deleting here is the row's own button).
                        .photoGestures(
                            t = zoomPan,
                            imageW = imageW,
                            imageH = imageH,
                            grabPx = grabPx,
                            key = series.geometry,
                            holeAt = { x, y, reach ->
                                holes.value.nearestHoleIndex(
                                    x.toDouble(),
                                    y.toDouble(),
                                    reach.toDouble(),
                                )
                            },
                            onMove = { i, x, y ->
                                holes.value = holes.value.moveHole(i, x to y, series)
                            },
                            onAdd = { x, y, _ ->
                                holes.value = holes.value.withNewHole(
                                    x.toDouble(),
                                    y.toDouble(),
                                    series.geometry,
                                )
                                // No geometry means no score to derive.
                                if (series.geometry == null) editing = holes.value.lastIndex
                            },
                        ),
                ) {
                    Box(modifier = Modifier.fillMaxSize().zoomPan(zoomPan)) {
                        photo?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        DetectionOverlay(
                            detections = emptyList(),
                            imageWidth = imageW,
                            imageHeight = imageH,
                            centre = series.geometry?.centre(),
                            ring = series.geometry?.ring(),
                            scores = holes.value.mapNotNull { it.asHitScore() },
                            holeColor = DETECTED_COLOR,
                            scoreColor = DETECTED_COLOR,
                            manualColor = MANUAL_COLOR,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(localStamp(series.timestamp), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (series.caliber == "-") "–" else series.caliber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = holes.value.sumOf { it.ring }.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Own column: the rows sit tight, the 16 dp outside stays
                // between the photo/header/button blocks.
                Column {
                    holes.value.forEachIndexed { i, hole ->
                        HoleRow(
                            hole = hole,
                            onEdit = { editing = i },
                            onDelete = {
                                // Indices shift, so any open edit is stale.
                                editing = -1
                                holes.value = holes.value.filterIndexed { j, _ -> j != i }
                            },
                        )
                    }
                }

                PrimaryActionButton(
                    text = stringResource(R.string.detail_save),
                    icon = Icons.Default.Save,
                    // The server rejects an empty hole list, so don't offer it.
                    enabled = !saving && holes.value.isNotEmpty() && holes.value != series.holes,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                services.api.updateSeries(
                                    series.id,
                                    SeriesRequest(
                                        series.timestamp,
                                        series.caliber,
                                        holes.value,
                                        series.geometry,
                                    ),
                                )
                                Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                                onBack()
                            } catch (_: Throwable) {
                                Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                                saving = false
                            }
                        }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteSeriesDialog(
            series = series,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    try {
                        services.api.deleteSeries(series.id)
                        onBack()
                    } catch (_: Throwable) {
                        Toast.makeText(context, deleteFailedText, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    if (editing >= 0) {
        ScoreDialpadDialog(
            // Only a hole the detector scored has something to revert to.
            onClear = holes.value.getOrNull(editing)?.detectedRing?.let {
                {
                    holes.value = holes.value.mapIndexed { i, hole ->
                        if (i == editing) hole.withDetectedScore() else hole
                    }
                    editing = -1
                }
            },
            onPick = { pick ->
                // Only the confirmed values change; detectedRing/-InnerTen stay,
                // which is what makes the row read "8 -> 9".
                holes.value = holes.value.mapIndexed { i, hole ->
                    if (i == editing) {
                        hole.copy(ring = pickRing(pick), innerTen = pickInnerTen(pick))
                    } else {
                        hole
                    }
                }
                editing = -1
            },
            onDismiss = { editing = -1 },
        )
    }
}

/**
 * Hole [index] moved to [p] (image px). The distance is re-measured from the
 * stored scan geometry — a series saved without it just loses the distance —
 * and the confirmed ring/inner-ten stay as they are.
 */
private fun List<HoleDto>.moveHole(index: Int, p: Pair<Float, Float>, series: SeriesDto) =
    mapIndexed { i, hole ->
        if (i != index) {
            hole
        } else {
            hole.copy(
                x = p.first.toDouble(),
                y = p.second.toDouble(),
                distanceMm = series.geometry?.let { g ->
                    distanceMm(p.first, p.second, g.centre(), g.ring())
                },
            )
        }
    }

@Composable
private fun HoleRow(hole: HoleDto, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Detected: read-only, struck through once the user overrode it.
        Text(
            text = hole.detectedLabel() ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = if (hole.isEdited()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            textDecoration = if (hole.isEdited()) TextDecoration.LineThrough else null,
            modifier = Modifier.width(32.dp),
        )
        // Manual: the user's own value, and the only tappable cell.
        Text(
            text = hole.manualLabel() ?: stringResource(R.string.detail_no_score),
            style = MaterialTheme.typography.titleLarge,
            color = MANUAL_COLOR,
            modifier = Modifier
                .width(40.dp)
                .clickable(onClick = onEdit)
                .padding(vertical = 6.dp),
        )
        Text(
            // Whole millimetres only — no decimals anywhere in this UI.
            text = hole.distanceMm
                ?.let { stringResource(R.string.detail_mm, it.roundToInt()) }
                ?: stringResource(R.string.detail_no_distance),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = hole.kindText(
                manual = stringResource(R.string.detail_kind_manual),
                typed = stringResource(R.string.detail_kind_typed),
                moved = stringResource(R.string.detail_kind_moved),
                detected = stringResource(R.string.detail_kind_detected),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Trimmed from the 48 dp default so the text, not the button, sets
        // the row height; the 24 dp icon still fits.
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.detail_delete_hole),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The overlay speaks [HitScore]; a stored hole without a position has no marker.
 * `manual` picks the orange marker, i.e. "the detector never saw this hole".
 */
private fun HoleDto.asHitScore(): HitScore? {
    val hx = x?.toFloat() ?: return null
    val hy = y?.toFloat() ?: return null
    // No box is stored, so the label hangs a typical hole-radius above the centre.
    return HitScore(hx, hy, hy - 10f, distanceMm ?: 0.0, ring, innerTen, manual = detectedRing == null)
}
