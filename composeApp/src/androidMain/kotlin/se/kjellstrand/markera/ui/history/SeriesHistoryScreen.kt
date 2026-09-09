package se.kjellstrand.markera.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.decodeSeriesJpeg
import se.kjellstrand.markera.series.exportSeriesZip
import se.kjellstrand.markera.series.localStamp
import se.kjellstrand.markera.series.scoreLine
import se.kjellstrand.markera.series.total
import se.kjellstrand.markera.ui.HelpAction
import se.kjellstrand.markera.ui.HelpDialog
import se.kjellstrand.markera.ui.LocalToast
import se.kjellstrand.markera.ui.competition.CompetitionTopBar

/** The list thumbnail is 72 dp; the stored frame is ~3000², so subsample hard. */
private const val THUMB_MAX_DIM = 256

/** The cached series, newest first; opening asks the backend for a delta. */
@Composable
fun SeriesHistoryScreen(
    services: SeriesServices,
    onBack: () -> Unit,
    shareFile: suspend (path: String) -> Unit,
    onOpen: (SeriesDto) -> Unit = {},
) {
    val auth by services.session.auth.collectAsState()
    val series by services.repository.series.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<SeriesDto?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // Thumbnails are small and few; one map for the screen beats a real image
    // loader (no Coil in this app).
    val thumbnails = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    LaunchedEffect(auth, reload) {
        if (auth == null) return@LaunchedEffect
        loading = true
        error = services.repository.refresh()?.let { it.message ?: it.toString() }
        loading = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            CompetitionTopBar(
                title = stringResource(Res.string.history_title),
                onBack = onBack,
                actions = {
                    HelpAction(onClick = { showingHelp = true })
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            enabled = auth != null && series.isNotEmpty(),
                            onClick = {
                                exporting = true
                                scope.launch {
                                    try {
                                        shareFile(
                                            exportSeriesZip(
                                                services.repository,
                                                services.cacheDir,
                                            ).toString(),
                                        )
                                    } catch (_: Throwable) {
                                        toast(getString(Res.string.history_export_failed))
                                    }
                                    exporting = false
                                }
                            },
                        ) {
                            // No explicit tint: the button greys the icon when disabled.
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(Res.string.history_export),
                            )
                        }
                    }
                },
            )
            when {
                auth == null -> Centered { Text(stringResource(Res.string.history_signed_out)) }

                // A failed delta only takes over the screen with nothing cached to show.
                error != null && series.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { reload++ }) { Text(stringResource(Res.string.history_retry)) }
                }

                loading && series.isEmpty() -> Centered { CircularProgressIndicator() }

                series.isEmpty() -> Centered { Text(stringResource(Res.string.history_empty)) }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(series, key = { it.id }) { item ->
                        SeriesCard(
                            series = item,
                            services = services,
                            thumbnails = thumbnails,
                            onClick = { onOpen(item) },
                            onLongPress = { pending = item },
                        )
                    }
                }
            }
        }
    }

    if (showingHelp) {
        HelpDialog(
            title = stringResource(Res.string.help_history_title),
            sections = listOf(
                Res.string.help_history_list to Res.string.help_history_list_body,
                Res.string.help_history_detail to Res.string.help_history_detail_body,
                Res.string.help_history_export to Res.string.help_history_export_body,
                Res.string.help_history_offline to Res.string.help_history_offline_body,
            ),
            onDismiss = { showingHelp = false },
        )
    }

    pending?.let { target ->
        DeleteSeriesDialog(
            series = target,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    try {
                        services.repository.delete(target.id)
                        thumbnails.remove(target.id)
                    } catch (_: Throwable) {
                        toast(getString(Res.string.history_delete_failed))
                    }
                }
            },
        )
    }
}

/** The delete confirmation, shared by the history list and the detail screen. */
@Composable
internal fun DeleteSeriesDialog(series: SeriesDto, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.history_delete_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.history_delete_message,
                    localStamp(series.timestamp),
                    series.total(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.history_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.history_delete_cancel))
            }
        },
    )
}

/** The same confirmation for removing a single hole, on the scan and detail screens. */
@Composable
internal fun DeleteHoleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.hole_delete_title)) },
        text = { Text(stringResource(Res.string.hole_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.hole_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.history_delete_cancel))
            }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SeriesCard(
    series: SeriesDto,
    services: SeriesServices,
    thumbnails: MutableMap<Long, ImageBitmap>,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    if (series.hasImage) {
        LaunchedEffect(series.id) {
            if (thumbnails[series.id] != null) return@LaunchedEffect
            // Cached on disk after the first fetch, so reopening downloads nothing.
            val bytes = services.repository.image(series.id) ?: return@LaunchedEffect
            decodeSeriesJpeg(bytes, THUMB_MAX_DIM)?.let {
                thumbnails[series.id] = it.asImageBitmap()
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            thumbnails[series.id]?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(localStamp(series.timestamp), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (series.caliber == "-") "–" else series.caliber,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = series.total().toString(),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = series.scoreLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
