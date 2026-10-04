package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.vision.INNER_RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.ringDigitRadiusMm
import se.kjellstrand.markera.vision.width

/**
 * Static aiming guide drawn over the live preview: a centred circle whose
 * diameter is ~70% of the smaller viewport dimension, plus a crosshair at its
 * centre. Purely a framing aid — it does not affect detection.
 */
@Composable
internal fun ViewfinderGuide(modifier: Modifier = Modifier) {
    val guideColor = Color(0xCCFFFFFF)
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = 0.35f * min(size.width, size.height)
        val stroke = 3.dp.toPx()
        drawCircle(
            color = guideColor,
            radius = radius,
            center = centre,
            style = Stroke(width = stroke),
        )
        // The 7/8, 8/9 and 9/10 lines inside the black, as fractions of the
        // 100 mm 6/7 radius — the same set the frozen frame's DetectionOverlay
        // draws, and fainter for the same reason: the 6/7 circle is what you
        // frame against, these only say where the shot landed.
        for (f in INNER_RING_RADII_MM.map { (it / TARGET_BLACK_RING_RADIUS_MM).toFloat() }) {
            drawCircle(
                color = guideColor.copy(alpha = 0.45f),
                radius = f * radius,
                center = centre,
                style = Stroke(width = stroke * 0.6f),
            )
        }
        // Digits as printed: mid-band of the 25 mm rings either side of the 100 mm 6/7 edge.
        val style = TextStyle(
            color = guideColor,
            fontSize = max(0.16f * radius, 12.sp.toPx()).toSp(),
            fontWeight = FontWeight.Bold,
            shadow = Shadow(Color.Black, Offset(0f, 1f), blurRadius = 5f),
        )
        for (digit in 9 downTo 6) {
            val factor = (ringDigitRadiusMm(digit) / TARGET_BLACK_RING_RADIUS_MM).toFloat()
            val layout = textMeasurer.measure(digit.toString(), style)
            val half = Offset(layout.size.width / 2f, layout.size.height / 2f)
            val d = factor * radius
            for (dir in listOf(Offset(-d, 0f), Offset(d, 0f), Offset(0f, -d), Offset(0f, d))) {
                drawText(layout, topLeft = centre + dir - half)
            }
        }
        val arm = 16.dp.toPx()
        drawLine(
            color = guideColor,
            start = Offset(centre.x - arm, centre.y),
            end = Offset(centre.x + arm, centre.y),
            strokeWidth = stroke,
        )
        drawLine(
            color = guideColor,
            start = Offset(centre.x, centre.y - arm),
            end = Offset(centre.x, centre.y + arm),
            strokeWidth = stroke,
        )
    }
}
