package se.kjellstrand.markera.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import kotlin.math.roundToInt

/** One row of the [AppMenu]; every row closes the menu when tapped. */
data class MenuItem(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The open menu, drawn by [MenuOverlay] at the nav root so its scrim covers the
 * whole screen, not just the top bar the button sits in.
 */
class MenuHost {
    internal var anchor by mutableStateOf(Rect.Zero)
    internal var items by mutableStateOf(emptyList<MenuItem>())
    internal var open by mutableStateOf(false)

    fun close() {
        open = false
    }
}

val LocalMenuHost = staticCompositionLocalOf<MenuHost?> { null }

/** Every menu ends with the settings cogwheel; null (no nav host) leaves it out. */
val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

private val MenuButtonSize = 38.dp
private val MenuItemSpacing = 10.dp
private val MenuRowSpacing = 18.dp
private val MenuTopOffset = 52.dp

/**
 * The one menu button every top bar carries (same speed dial as FieldShootingTimer's):
 * tapping it slides [items] plus Settings down from beneath it, each a round icon
 * button with its label on the screen-middle side, over a scrim that closes it.
 */
@Composable
fun AppMenu(items: List<MenuItem>, modifier: Modifier = Modifier) {
    val host = LocalMenuHost.current
    val openSettings = LocalOpenSettings.current
    val settingsLabel = stringResource(Res.string.settings)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    MenuButton(
        icon = Icons.Default.Menu,
        label = stringResource(Res.string.menu),
        modifier = modifier
            .padding(4.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() },
    ) {
        host ?: return@MenuButton
        host.anchor = bounds
        host.items = items + listOfNotNull(
            openSettings?.let { MenuItem(Icons.Default.Settings, settingsLabel, onClick = it) },
        )
        host.open = true
    }
}

/** Draws [host]'s menu when open; put it last in a root Box that fills the window. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.MenuOverlay(host: MenuHost) {
    val progress by animateFloatAsState(
        targetValue = if (host.open) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "appMenuSlideOut",
    )
    // Rows leave the composition once the closing spring has settled.
    if (progress <= 0.01f) return
    BackHandler(enabled = host.open) { host.close() }

    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.5f * progress.coerceIn(0f, 1f)))
            .pointerInput(Unit) { detectTapGestures { host.close() } },
    )
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val anchor = host.anchor
        val rowStridePx = with(density) { (MenuButtonSize + MenuRowSpacing).toPx() }
        val topOffsetPx = with(density) { MenuTopOffset.toPx() }
        // The rows open towards the middle: a button on the left gets its labels to
        // the right (as in FieldShootingTimer), one on the right gets them to the left.
        val fromStart = anchor.center.x < constraints.maxWidth / 2f
        val align = if (fromStart) Alignment.TopStart else Alignment.TopEnd
        val edgePadding = with(density) {
            if (fromStart) {
                PaddingValues(start = anchor.left.coerceAtLeast(0f).toDp())
            } else {
                PaddingValues(end = (constraints.maxWidth - anchor.right).coerceAtLeast(0f).toDp())
            }
        }
        host.items.forEachIndexed { index, item ->
            val targetY = topOffsetPx + rowStridePx * index
            val rowClick = {
                if (item.enabled) {
                    host.close()
                    item.onClick()
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(align)
                    .padding(edgePadding)
                    .offset { IntOffset(0, (anchor.top + targetY * progress).roundToInt()) }
                    // Saturate at half the travel: the underdamped spring overshoots
                    // around 1.0, and an alpha oscillating across 1.0 flickers.
                    .graphicsLayer { alpha = (progress * 2f).coerceIn(0f, 1f) }
                    .alpha(if (item.enabled) 1f else 0.38f)
                    .clickable(interactionSource = null, indication = null, onClick = rowClick),
            ) {
                if (fromStart) {
                    MenuButton(icon = item.icon, label = item.label, onClick = rowClick)
                    Spacer(Modifier.width(MenuItemSpacing))
                }
                Text(
                    text = item.label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.onBackground, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (!fromStart) {
                    Spacer(Modifier.width(MenuItemSpacing))
                    MenuButton(icon = item.icon, label = item.label, onClick = rowClick)
                }
            }
        }
        // The menu button again, above the scrim, so it also closes what it opened.
        MenuButton(
            icon = Icons.Default.Menu,
            label = stringResource(Res.string.menu),
            modifier = Modifier
                .align(align)
                .padding(edgePadding)
                .offset { IntOffset(0, anchor.top.roundToInt()) },
        ) { host.close() }
    }
}

@Composable
internal fun MenuButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(MenuButtonSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
    }
}
