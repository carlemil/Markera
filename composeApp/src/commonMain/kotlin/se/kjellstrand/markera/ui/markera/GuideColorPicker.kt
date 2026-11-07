package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.settings.DEFAULT_GUIDE_COLOR

/** The guide keeps its 80% opacity whatever colour is picked, so the target shows through. */
private const val GUIDE_ALPHA = 0.8f

/** Ready-made picks: the default white first, then colours that stand out on a black/white target. */
private val SWATCHES: List<Color> = listOf(
    Color(DEFAULT_GUIDE_COLOR),
    Color.Black,
    Color(0xFFFFEB3B), // yellow
    Color(0xFFFF9800), // orange
    Color(0xFFF44336), // red
    Color(0xFFE91E63), // pink
    Color(0xFF00E5FF), // cyan
    Color(0xFF76FF03), // lime
    Color(0xFF2979FF), // blue
    Color(0xFFD500F9), // purple
).map { it.copy(alpha = GUIDE_ALPHA) }

private val HUES = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }

/** ARGB as stored by [se.kjellstrand.markera.ui.settings.AppSettings.guideColor]. */
internal fun Color.toStoredArgb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * The palette button in the camera view's top-right corner: tinted with the
 * current guide colour, it opens [GuideColorDialog].
 */
@Composable
internal fun GuideColorButton(
    color: Color,
    onPick: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
    ) {
        Icon(
            imageVector = Icons.Filled.Palette,
            contentDescription = stringResource(Res.string.guide_color_title),
            tint = color.copy(alpha = 1f),
        )
    }
    if (open) {
        GuideColorDialog(
            initial = color,
            onDismiss = { open = false },
            onConfirm = {
                onPick(it)
                open = false
            },
        )
    }
}

/** Swatches plus a hue bar and a brightness slider; nothing applies until Done. */
@Composable
private fun GuideColorDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    var picked by remember { mutableStateOf(initial) }
    var hue by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(1f) }
    fun fromHue() {
        picked = Color.hsv(hue, 1f, brightness, GUIDE_ALPHA)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.guide_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Preview: the picked colour as a ring over a target-ish dark/light split.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.horizontalGradient(listOf(Color.Black, Color.White))),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(44.dp)) {
                        drawCircle(picked, radius = size.minDimension / 2f - 3.dp.toPx(), style = Stroke(3.dp.toPx()))
                        val arm = 8.dp.toPx()
                        val c = center
                        drawLine(picked, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 3.dp.toPx())
                        drawLine(picked, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 3.dp.toPx())
                    }
                }
                SWATCHES.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { swatch ->
                            val selected = swatch.toArgb() == picked.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(swatch.copy(alpha = 1f))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { picked = swatch },
                            )
                        }
                    }
                }
                Text(stringResource(Res.string.guide_color_hue), style = MaterialTheme.typography.labelLarge)
                HueBar(hue = hue, onHue = { hue = it; fromHue() })
                Text(stringResource(Res.string.guide_color_brightness), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it; fromHue() },
                    valueRange = 0.2f..1f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(Res.string.guide_color_done))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(Color(DEFAULT_GUIDE_COLOR)) }) {
                    Text(stringResource(Res.string.guide_color_reset))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.dialog_cancel))
                }
            }
        },
    )
}

/** A rainbow bar; tap or drag along it to choose a hue (0..360). */
@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    fun hueAt(x: Float, width: Int) = (x / width).coerceIn(0f, 1f) * 360f
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { onHue(hueAt(it.x, size.width)) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onHue(hueAt(it.x, size.width)) },
                ) { change, _ -> onHue(hueAt(change.position.x, size.width)) }
            },
    ) {
        val r = CornerRadius(8.dp.toPx())
        drawRoundRect(Brush.horizontalGradient(HUES), cornerRadius = r)
        val x = hue / 360f * size.width
        drawCircle(Color.White, radius = size.height / 2f - 2.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, radius = size.height / 2f, center = Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
    }
}
