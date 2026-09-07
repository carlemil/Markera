package se.kjellstrand.markera.ui.history

import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import se.kjellstrand.markera.R
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.SeriesServices
import se.kjellstrand.markera.series.nextPageCursor
import se.kjellstrand.markera.series.scoreLine
import se.kjellstrand.markera.series.total
import se.kjellstrand.markera.ui.competition.CompetitionTopBar

private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** The series saved on the Markera backend, newest first, paged as you scroll. */
@Composable
fun SeriesHistoryScreen(
    services: SeriesServices,
    onBack: () -> Unit,
    onOpen: (SeriesDto) -> Unit = {},
) {
    val auth by services.session.auth.collectAsState()
    var series by remember { mutableStateOf<List<SeriesDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Cursor for the next page; null once the last (short) page arrived.
    var cursor by remember { mutableStateOf<Long?>(null) }
    var pageError by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SeriesDto?>(null) }
    // Thumbnails are small and few; one map for the screen beats a real image
    // loader (no Coil in this app).
    val thumbnails = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val listState = rememberLazyListState()
    val lastVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(auth, reload) {
        if (auth == null) return@LaunchedEffect
        series = null
        error = null
        pageError = false
        cursor = null
        try {
            val page = services.api.listSeries()
            series = page
            cursor = nextPageCursor(page)
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    // Next page once the last row is on screen. A failed page parks here until
    // the retry row clears [pageError].
    LaunchedEffect(lastVisible, cursor, pageError) {
        val before = cursor ?: return@LaunchedEffect
        val loaded = series ?: return@LaunchedEffect
        if (pageError || lastVisible < loaded.lastIndex) return@LaunchedEffect
        try {
            val page = services.api.listSeries(before = before)
            series = loaded + page
            cursor = nextPageCursor(page)
        } catch (_: Throwable) {
            pageError = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
        ) {
            CompetitionTopBar(title = stringResource(R.string.history_title), onBack = onBack)
            when {
                auth == null -> Centered { Text(stringResource(R.string.history_signed_out)) }

                error != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.history_retry)) }
                }

                series == null -> Centered { CircularProgressIndicator() }

                series!!.isEmpty() -> Centered { Text(stringResource(R.string.history_empty)) }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(series!!, key = { it.id }) { item ->
                        SeriesCard(
                            series = item,
                            services = services,
                            thumbnails = thumbnails,
                            onClick = { onOpen(item) },
                            onLongPress = { pending = item },
                        )
                    }
                    if (cursor != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (pageError) {
                                    Button(onClick = { pageError = false }) {
                                        Text(stringResource(R.string.history_retry))
                                    }
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { target ->
        DeleteSeriesDialog(
            series = target,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    try {
                        services.api.deleteSeries(target.id)
                        series = series?.filterNot { it.id == target.id }
                        thumbnails.remove(target.id)
                    } catch (_: Throwable) {
                        Toast.makeText(
                            context,
                            R.string.history_delete_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
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
        title = { Text(stringResource(R.string.history_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.history_delete_message,
                    localStamp(series.timestamp),
                    series.total(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.history_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_delete_cancel))
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
            try {
                val bytes = services.api.getSeriesImage(series.id)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    thumbnails[series.id] = it.asImageBitmap()
                }
            } catch (_: Throwable) {
                // No thumbnail is the whole fallback.
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Falls back to the raw string if the server ever sends something unparsable. */
internal fun localStamp(timestamp: String): String = try {
    stampFormat.format(Instant.parse(timestamp).atZone(ZoneId.systemDefault()))
} catch (_: Exception) {
    timestamp
}
