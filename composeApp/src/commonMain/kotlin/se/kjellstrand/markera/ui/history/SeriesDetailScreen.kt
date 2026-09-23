package se.kjellstrand.markera.ui.history

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesEdit
import se.kjellstrand.markera.series.SeriesEdits
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.localTime
import se.kjellstrand.markera.series.centre
import se.kjellstrand.markera.series.decodeSeriesJpeg
import se.kjellstrand.markera.series.detectedLabel
import se.kjellstrand.markera.series.isEdited
import se.kjellstrand.markera.series.canRestore
import se.kjellstrand.markera.series.kindText
import se.kjellstrand.markera.series.moveHole
import se.kjellstrand.markera.series.nearestHoleIndex
import se.kjellstrand.markera.series.normalizeTag
import se.kjellstrand.markera.series.pickInnerTen
import se.kjellstrand.markera.series.pickRing
import se.kjellstrand.markera.series.restoreHole
import se.kjellstrand.markera.series.ring
import se.kjellstrand.markera.series.withNewHole
import se.kjellstrand.markera.ui.CaliberDialog
import se.kjellstrand.markera.ui.MenuItem
import se.kjellstrand.markera.ui.StateMessage
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.LocalSnackbar
import se.kjellstrand.markera.ui.LocalToast
import se.kjellstrand.markera.ui.TagDialog
import se.kjellstrand.markera.ui.AppTopBar
import se.kjellstrand.markera.ui.markera.DetectionOverlay
import se.kjellstrand.markera.ui.markera.LocalSeriesRecorder
import se.kjellstrand.markera.ui.markera.customCalibers
import se.kjellstrand.markera.ui.markera.TotalBadge
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_COUNT
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN
import se.kjellstrand.markera.ui.markera.ScoreBox
import se.kjellstrand.markera.ui.markera.holeLetter
import se.kjellstrand.markera.ui.markera.photoGestures
import se.kjellstrand.markera.ui.markera.rememberZoomPan
import se.kjellstrand.markera.ui.markera.zoomPan
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.distanceMm

/** Same greens/oranges the live overlay uses for detected vs. hand-placed holes. */
private val DETECTED_COLOR = Color(0xFF9CCC65)

/** How close a drag has to start to a marker to grab it. */
private val GRAB_RADIUS = 24.dp

/** Plenty for the zoomable photo; the stored frame is ~3000² (36 MB decoded). */
private const val PHOTO_MAX_DIM = 1536

/**
 * One saved series: the scanned photo with a marker per positioned hole, the
 * hole list, and editing — tap the photo to add a hole, drag a marker to move
 * it, pinch to zoom in first. A score is never typed: it always comes from where
 * the hole sits. Every edit is saved at once (the whole series is PUT back) and
 * a snackbar offers to undo it.
 */
@Composable
fun SeriesDetailScreen(initial: SeriesDto, services: SeriesServices, onBack: () -> Unit) {
    // Looked up in the cache by id, so an edit made anywhere else shows here.
    val cached by services.repository.series.collectAsState()
    val series = cached.firstOrNull { it.id == initial.id } ?: initial
    val toast = LocalToast.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    // A plain State (not `by`), so the drag gesture — which is not recomposed —
    // reads and writes the current list.
    val holes = remember(series.id) { mutableStateOf(series.holes) }
    var photo by remember(series.id) { mutableStateOf<ImageBitmap?>(null) }
    // Local like the holes; commit() below saves all three together. The recorder owns
    // the user's own calibers (the dialog adds and removes through it), so a custom
    // label resolves to its diameter here.
    val recorder = LocalSeriesRecorder.current
    val custom = customCalibers()
    var caliber by remember(series.id) { mutableStateOf(Caliber.fromLabel(series.caliber, custom)) }
    var pickingCaliber by remember(series.id) { mutableStateOf(false) }
    // Deliberately not routed through SeriesRecorder — that one owns the
    // *pending scan*, not this series.
    var tag by remember(series.id) { mutableStateOf(series.tag) }
    var pickingTag by remember(series.id) { mutableStateOf(false) }
    // A score only ever comes from a position, so without geometry the holes
    // can't be edited at all.
    val geometry = series.geometry
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDeleteIndex by remember(series.id) { mutableStateOf<Int?>(null) }
    var showingHelp by remember { mutableStateOf(false) }
    val zoomPan = rememberZoomPan(series.id)
    val savedText = stringResource(Res.string.detail_saved)
    val failedText = stringResource(Res.string.detail_save_failed)
    val undoText = stringResource(Res.string.undo)
    val deleteFailedText = stringResource(Res.string.history_delete_failed)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }

    val edits = remember(series.id) { SeriesEdits(services.repository, series) }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    fun restore(to: SeriesEdit) {
        holes.value = to.holes
        caliber = Caliber.fromLabel(to.caliber, custom)
        tag = to.tag
    }

    // Saves the local state as it is now; a failed save puts back what the server has.
    fun commit(removedHole: HoleDto? = null) {
        val next = SeriesEdit(holes.value, caliber.label, tag)
        if (next == edits.saved && removedHole == null) return
        scope.launch {
            val before = edits.save(next, removedHole)
            if (before == null) {
                toast(failedText)
                restore(edits.saved)
                return@launch
            }
            // One undo offer at a time: quick edits don't queue a snackbar each.
            undoJob?.cancel()
            undoJob = scope.launch {
                val result = snackbar.showSnackbar(savedText, undoText, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) {
                    restore(before)
                    if (edits.save(before) == null) {
                        toast(failedText)
                        restore(edits.saved)
                    }
                }
            }
        }
    }

    // Tells a photo that will not come ("No photo") from one still loading (spinner).
    var photoFailed by remember(series.id) { mutableStateOf(false) }
    LaunchedEffect(series.id) {
        if (!series.hasImage) return@LaunchedEffect
        // Null is a failed download with nothing cached.
        val bytes = services.repository.image(series.id)
        // Only the decode is caught, so cancellation still propagates.
        photo = bytes?.let {
            try {
                decodeSeriesJpeg(it, PHOTO_MAX_DIM)
            } catch (_: Exception) {
                null
            }
        }
        photoFailed = photo == null
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
            AppTopBar(
                title = stringResource(Res.string.detail_title),
                onBack = onBack,
                menuItems = listOf(
                    MenuItem(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(Res.string.help)) { showingHelp = true },
                    MenuItem(Icons.Default.Delete, stringResource(Res.string.history_delete_confirm)) {
                        confirmDelete = true
                    },
                ),
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
                        // uses (deleting here is the row's own button). Without
                        // stored geometry there is nothing to score a position
                        // against, so such a series only zooms.
                        .photoGestures(
                            t = zoomPan,
                            imageW = imageW,
                            imageH = imageH,
                            grabPx = grabPx,
                            key = geometry,
                            holeAt = { x, y, reach ->
                                if (geometry == null) {
                                    -1
                                } else {
                                    holes.value.nearestHoleIndex(
                                        x.toDouble(),
                                        y.toDouble(),
                                        reach.toDouble(),
                                    )
                                }
                            },
                            onMove = { i, x, y ->
                                if (geometry == null) {
                                    -1
                                } else {
                                    val moved =
                                        holes.value.moveHole(i, x.toDouble(), y.toDouble(), geometry, caliber)
                                    holes.value = moved
                                    // Rescoring re-sorts: find the hole again by
                                    // where it was just put, so the drag follows.
                                    moved.indexOfFirst {
                                        it.x == x.toDouble() && it.y == y.toDouble()
                                    }
                                }
                            },
                            onAdd = { x, y, _ ->
                                // A series is five shots: past that a tap adds nothing.
                                if (holes.value.size < SCORE_PICKER_COUNT) {
                                    geometry?.let {
                                        holes.value =
                                            holes.value.withNewHole(x.toDouble(), y.toDouble(), it, caliber)
                                        commit()
                                    }
                                }
                            },
                            // One save per drag, when the finger lifts.
                            onMoveEnd = { commit() },
                        ),
                ) {
                    Box(modifier = Modifier.fillMaxSize().zoomPan(zoomPan)) {
                        val shown = photo
                        val placeholder = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                        when {
                            shown != null -> Image(
                                bitmap = shown,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )

                            !series.hasImage || photoFailed -> StateMessage(
                                icon = Icons.Default.HideImage,
                                title = stringResource(Res.string.detail_photo_missing),
                                modifier = placeholder,
                            )

                            else -> StateMessage(loading = true, modifier = placeholder)
                        }
                        // A hole without a position has no marker, so carry the
                        // letter along from the list index — otherwise the
                        // markers would renumber and stop matching the rows.
                        val marked = holes.value.mapIndexedNotNull { i, h ->
                            h.asHitScore()?.let { it to holeLetter(i) }
                        }
                        DetectionOverlay(
                            detections = emptyList(),
                            imageWidth = imageW,
                            imageHeight = imageH,
                            centre = series.geometry?.centre(),
                            ring = series.geometry?.ring(),
                            scores = marked.map { it.first },
                            letters = marked.map { it.second },
                            // The editable state, so correcting the caliber
                            // resizes the dots right away.
                            caliber = caliber,
                            holeColor = DETECTED_COLOR,
                            scoreColor = DETECTED_COLOR,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Time over date, the two lines centred against each other.
                    Text(
                        "${localTime(series.timestamp)}\n${localStamp(series.timestamp).take(10)}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    // Tap caliber or tag to correct it.
                    TotalBadge(
                        total = holes.value.sumOf { it.ring },
                        caliber = caliber.label,
                        tag = tag,
                        onCaliberClick = { pickingCaliber = true },
                        onTagClick = { pickingTag = true },
                    )
                }

                // Own column: the rows sit tight, the 16 dp outside stays
                // between the photo and header blocks.
                Column {
                    holes.value.forEachIndexed { i, hole ->
                        HoleRow(
                            hole = hole,
                            letter = holeLetter(i),
                            // The server rejects an empty hole list, so the last
                            // hole stays; the whole series goes from the menu.
                            onDelete = if (holes.value.size > 1) {
                                { pendingDeleteIndex = i }
                            } else {
                                null
                            },
                            // Back to the detected position and score; only
                            // offered while the hole differs from it.
                            onRestore = if (geometry != null && hole.canRestore()) {
                                {
                                    holes.value = holes.value.restoreHole(i, geometry)
                                    commit()
                                }
                            } else {
                                null
                            },
                            // Same gate as the photo editing above: no geometry,
                            // no editing.
                            onScoreChange = geometry?.let {
                                { pick: Int ->
                                    holes.value = holes.value.mapIndexed { j, h ->
                                        if (j == i) {
                                            h.copy(ring = pickRing(pick), innerTen = pickInnerTen(pick))
                                        } else {
                                            h
                                        }
                                    }
                                    commit()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_detail_title),
            sections = listOf(
                Res.string.help_detail_photo to Res.string.help_detail_photo_body,
                Res.string.help_detail_holes to Res.string.help_detail_holes_body,
                Res.string.help_detail_delete to Res.string.help_detail_delete_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }

    if (pickingCaliber) {
        val select: (Caliber) -> Unit = {
            caliber = it
            pickingCaliber = false
            commit()
        }
        CaliberDialog(
            selected = caliber,
            custom = custom,
            onSelect = select,
            // Only the list lives in the recorder: this series, not the next scan, gets the new one.
            onAdd = { label, mm -> recorder?.addCaliber(label, mm)?.let(select) },
            onRemove = { recorder?.removeCaliber(it) },
            onDismiss = { pickingCaliber = false },
        )
    }

    if (pickingTag) {
        TagDialog(
            selected = tag,
            // The same offer as the scan screen: every tag in use.
            known = cached.mapNotNull { it.tag }.distinct(),
            onSelect = {
                // Typed text arrives raw; normalize so "  " is untagged, not a blank tag.
                tag = normalizeTag(it)
                pickingTag = false
                commit()
            },
            onDismiss = { pickingTag = false },
        )
    }

    pendingDeleteIndex?.let { index ->
        DeleteHoleDialog(
            onDismiss = { pendingDeleteIndex = null },
            onConfirm = {
                pendingDeleteIndex = null
                val hole = holes.value[index]
                holes.value = holes.value.filterIndexed { j, _ -> j != index }
                // Only a detection is worth keeping as training data; a hole added by hand
                // (here or at the scan) has none, so it just goes.
                commit(removedHole = hole.takeIf { it.detectedRing != null }?.copy(deleted = true))
            },
        )
    }

    if (confirmDelete) {
        DeleteSeriesDialog(
            series = series,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    try {
                        services.repository.delete(series.id)
                        onBack()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        toast(deleteFailedText)
                    }
                }
            },
        )
    }
}

/**
 * One hole: its letter + what the detector said, the editable score, the
 * distance and how the hole came about. [onScoreChange] gets a picker index
 * (see `pickRing`); null leaves the score box inert. [onRestore] puts the hole
 * back on its detected spot and score; null hides that button.
 */
@Composable
private fun HoleRow(
    hole: HoleDto,
    letter: String,
    onDelete: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onScoreChange: ((Int) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        // 12, not 16: the score box is 4 dp wider than the text cell it
        // replaced, and the kind column ("detekterad") must not wrap.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The key letter back to the marker on the photo shares a cell with the
        // detected score, so the kind column keeps its width ("detekterad" must
        // not wrap).
        Box(modifier = Modifier.width(44.dp)) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            // Detected: what the model said, struck through once the hole was
            // moved somewhere that scores differently.
            Text(
                text = hole.detectedLabel() ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = if (hole.isEdited()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textDecoration = if (hole.isEdited()) TextDecoration.LineThrough else null,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        // The hole's current score, and the same tap-to-dialpad box as the scan
        // screen. It always shows a value (not the old "—" placeholder), so
        // correcting an untouched detection is a tap on the score itself.
        // Whose value it is stays readable from the row: the detected cell to
        // the left is struck through once they differ, and the kind column
        // spells it out ("8 → 9", "manuell", "inmatad").
        ScoreBox(
            value = if (hole.innerTen) SCORE_PICKER_INNER_TEN else hole.ring,
            onValueChange = onScoreChange,
            // Same test as asHitScore's orange marker, so box and marker agree.
            manual = hole.detectedRing == null && hole.x != null,
        )
        Text(
            // Whole millimetres only — no decimals anywhere in this UI.
            text = hole.distanceMm
                ?.let { stringResource(Res.string.detail_mm, it.roundToInt()) }
                ?: stringResource(Res.string.detail_no_distance),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = hole.kindText(
                manual = stringResource(Res.string.detail_kind_manual),
                typed = stringResource(Res.string.detail_kind_typed),
                moved = stringResource(Res.string.detail_kind_moved),
                detected = stringResource(Res.string.detail_kind_detected),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Trimmed from the 48 dp default so the text, not the button, sets
        // the row height; the 24 dp icon still fits.
        if (onRestore != null) {
            IconButton(onClick = onRestore, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(Res.string.detail_restore_hole),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.detail_delete_hole),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
