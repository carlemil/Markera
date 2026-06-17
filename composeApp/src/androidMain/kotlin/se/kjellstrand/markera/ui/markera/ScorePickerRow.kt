package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

private val PICKER_GAP = 6.dp
private val SLIDER_ITEM_WIDTH = 44.dp
private val SLIDER_ITEM_HEIGHT = 48.dp
private const val SLIDER_VISIBLE_ITEMS = 3
private const val SIDE_ITEM_MIN_ALPHA = 0.18f
private const val SIDE_ITEM_MIN_SCALE = 0.72f
private val HIGHLIGHT_SHAPE = RoundedCornerShape(10.dp)

/** Distance of [page] from the settled centre, 0f (centred) … 1f (a step away). */
private fun PagerState.pageOffset(page: Int): Float =
    (((currentPage - page) + currentPageOffsetFraction).absoluteValue).coerceIn(0f, 1f)

private fun PagerState.pageAlpha(page: Int) =
    1f - (1f - SIDE_ITEM_MIN_ALPHA) * pageOffset(page)

private fun PagerState.pageScale(page: Int) =
    1f - (1f - SIDE_ITEM_MIN_SCALE) * pageOffset(page)

/**
 * Horizontal sliding picker built on [HorizontalPager]. The selected value is
 * whichever item is centred in the viewport — highlighted with a tinted, bordered
 * slot; neighbours fade and shrink so the control reads as a slider, not a grid.
 */
@Composable
private fun HorizontalSlidingScorePicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = SCORE_PICKER_LABELS.size
    val pagerState = rememberPagerState(
        initialPage = value.coerceIn(0, SCORE_PICKER_INNER_TEN),
        pageCount = { pageCount },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != value) onValueChange(settled)
        }
    }
    LaunchedEffect(value) {
        if (pagerState.currentPage != value) pagerState.scrollToPage(value)
    }

    val sideCount = SLIDER_VISIBLE_ITEMS / 2
    Box(
        modifier = modifier
            .width(SLIDER_ITEM_WIDTH * SLIDER_VISIBLE_ITEMS)
            .height(SLIDER_ITEM_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        SelectionSlot(Modifier.width(SLIDER_ITEM_WIDTH).fillMaxHeight())
        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(SLIDER_ITEM_WIDTH),
            contentPadding = PaddingValues(horizontal = SLIDER_ITEM_WIDTH * sideCount),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            PageLabel(SCORE_PICKER_LABELS[page], pagerState.pageAlpha(page), pagerState.pageScale(page))
        }
    }
}

/**
 * Vertical mirror of [HorizontalSlidingScorePicker] — the user swipes up/down
 * and the centred item is highlighted.
 */
@Composable
private fun VerticalSlidingScorePicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = SCORE_PICKER_LABELS.size
    val pagerState = rememberPagerState(
        initialPage = value.coerceIn(0, SCORE_PICKER_INNER_TEN),
        pageCount = { pageCount },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != value) onValueChange(settled)
        }
    }
    LaunchedEffect(value) {
        if (pagerState.currentPage != value) pagerState.scrollToPage(value)
    }

    val sideCount = SLIDER_VISIBLE_ITEMS / 2
    Box(
        modifier = modifier
            .width(SLIDER_ITEM_WIDTH)
            .height(SLIDER_ITEM_HEIGHT * SLIDER_VISIBLE_ITEMS),
        contentAlignment = Alignment.Center,
    ) {
        SelectionSlot(Modifier.fillMaxWidth().height(SLIDER_ITEM_HEIGHT))
        VerticalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(SLIDER_ITEM_HEIGHT),
            contentPadding = PaddingValues(vertical = SLIDER_ITEM_HEIGHT * sideCount),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            PageLabel(SCORE_PICKER_LABELS[page], pagerState.pageAlpha(page), pagerState.pageScale(page))
        }
    }
}

/** The fixed, highlighted centre slot showing the current selection. */
@Composable
private fun SelectionSlot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), HIGHLIGHT_SHAPE)
            .border(2.dp, MaterialTheme.colorScheme.primary, HIGHLIGHT_SHAPE),
    )
}

/** One pager cell: the value, faded and shrunk by how far it is off-centre. */
@Composable
private fun PageLabel(label: String, alpha: Float, scale: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}

/** Portrait — vertical sliding pickers in a row. */
@Composable
fun ScorePickerHorizontalRow(
    values: List<Int>,
    onValueChange: (index: Int, value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PICKER_GAP),
    ) {
        values.forEachIndexed { i, v ->
            VerticalSlidingScorePicker(value = v, onValueChange = { onValueChange(i, it) })
        }
    }
}

/** Landscape — horizontal sliding pickers in a column. */
@Composable
fun ScorePickerVerticalColumn(
    values: List<Int>,
    onValueChange: (index: Int, value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PICKER_GAP),
    ) {
        values.forEachIndexed { i, v ->
            HorizontalSlidingScorePicker(value = v, onValueChange = { onValueChange(i, it) })
        }
    }
}
