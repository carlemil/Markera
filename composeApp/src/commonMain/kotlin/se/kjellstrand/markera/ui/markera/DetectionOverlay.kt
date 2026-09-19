package se.kjellstrand.markera.ui.markera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HIT_DOT_ALPHA
import se.kjellstrand.markera.series.hitDotRadiusMm
import se.kjellstrand.markera.ui.theme.MarkeraGreen
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.INNER_RING_RADII_MM
import se.kjellstrand.markera.vision.TARGET_BLACK_RING_RADIUS_MM
import se.kjellstrand.markera.vision.TargetLine
import se.kjellstrand.markera.vision.ringOutline
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/** Hand-placed holes: the photo marker and their score box share it. */
val MANUAL_HIT_COLOR = Color(0xFFFFB74D)

private val BOX_COLOR = MarkeraGreen
private val DIGIT_COLOR = Color(0xFF00B0FF)
private val CENTRE_COLOR = Color(0xFFFF1744)
private val ROW_LINE_COLOR = Color(0xFFFFC400)
private val RING_COLOR = MarkeraGreen
private const val STROKE_WIDTH_PX = 4f

/**
 * Draws the scoring result over the frozen frame, fit-centre letterboxed to
 * line up with the Image/PreviewView: the 6/7 [ring], the [centre] crosshair, a
 * marker + ring-value label per scored hole.
 *
 * [showDebug] adds the raw detection data — hole boxes, recognised [digits]
 * boxes and the fitted digit-row lines — for diagnostics; the default clean
 * view shows only the result.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    digits: List<DigitDetection> = emptyList(),
    centre: CentreEstimate? = null,
    ring: FittedEllipse? = null,
    scores: List<HitScore> = emptyList(),
    /**
     * Key letters drawn by the hole markers, parallel to [scores]; when a
     * position is missing the caller must pass these so marker and list stay in
     * step. Defaults to the score's own index ("a", "b", …).
     */
    letters: List<String> = emptyList(),
    /** Sizes the hole dots; [Caliber.NONE] draws them at the calibration size. */
    caliber: Caliber = Caliber.NONE,
    showDebug: Boolean = false,
    scoreColor: Color = Color(0xFFFFFFFF),
    holeColor: Color = MarkeraGreen,
    /** Holes the user tapped in by hand, marker and label. */
    manualColor: Color = MANUAL_HIT_COLOR,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val offsetX = (size.width - imageWidth * scale) / 2f
        val offsetY = (size.height - imageHeight * scale) / 2f
        val labelSize = (28f * scale).coerceIn(44f, 128f)

        // 6/7 boundary ellipse: drawn at its own fitted centre — it is the fit,
        // and its fit quality is what the user reads off the photo.
        if (ring != null) {
            drawTargetRing(ring, scale, offsetX, offsetY, RING_COLOR, STROKE_WIDTH_PX + 2f)
        }

        // The ring lines inside the black 6/7 edge (7/8, 8/9, 9/10). Each is the
        // same ellipse scaled by radius/100 about the *digit-row* centre, which
        // is what the scores are measured from — so they can look slightly
        // off-concentric with the 6/7 ellipse when the two centres differ, and a
        // visibly skewed 9-line is then a real signal that the fit is off.
        // No 12.5mm inner-X circle: it lands exactly where the hits cluster and
        // would clutter the middle of the photo under the markers.
        if (ring != null && centre != null && centre.method != CentreMethod.NONE) {
            val innerRingColor = RING_COLOR.copy(alpha = 0.45f)
            INNER_RING_RADII_MM.forEach { r ->
                drawTargetRing(
                    ringOutline(ring, centre, r), scale, offsetX, offsetY,
                    innerRingColor, STROKE_WIDTH_PX * 0.6f,
                )
            }
        }

        // Holes: a full box in debug, otherwise just a small marker dot — the
        // hole itself is already visible in the photo. The dots come from the
        // scores when there are any, since only those know which holes the user
        // placed by hand (drawn orange); unscored frames fall back to the boxes.
        // Dots are sized after the bullet. `scale` is only the image→canvas fit
        // factor, so the millimetres have to come from the fitted 6/7 ellipse,
        // whose semiMajor is TARGET_BLACK_RING_RADIUS_MM by definition. No ring,
        // no mm scale: then keep the fixed dot.
        val dotRadius = if (ring != null) {
            val imagePxPerMm = ring.semiMajor / TARGET_BLACK_RING_RADIUS_MM
            max(2f, (caliber.hitDotRadiusMm() * imagePxPerMm).toFloat() * scale)
        } else {
            max(3f, STROKE_WIDTH_PX * 1.1f)
        }
        if (showDebug) {
            detections.forEach { d ->
                drawRect(
                    color = BOX_COLOR,
                    topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                    size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                    style = Stroke(width = STROKE_WIDTH_PX),
                )
            }
        } else if (scores.isNotEmpty()) {
            scores.forEach { hit ->
                drawCircle(
                    color = (if (hit.manual) manualColor else holeColor).copy(alpha = HIT_DOT_ALPHA),
                    radius = dotRadius,
                    center = Offset(hit.centerXpx * scale + offsetX, hit.centerYpx * scale + offsetY),
                )
            }
        } else {
            detections.forEach { d ->
                val hx = (d.left + d.right) / 2f * scale + offsetX
                val hy = (d.top + d.bottom) / 2f * scale + offsetY
                drawCircle(
                    holeColor.copy(alpha = HIT_DOT_ALPHA),
                    radius = dotRadius,
                    center = Offset(hx, hy),
                )
            }
        }

        // Key letter just below-right of each hole, tying the marker to its
        // score box / list row. Deliberately small and unbolded — the ring
        // value above the hole is the primary label.
        if (scores.isNotEmpty()) {
            val letterSize = labelSize * 0.52f
            scores.forEachIndexed { i, hit ->
                val layout = textMeasurer.measure(
                    letters.getOrElse(i) { holeLetter(i) },
                    TextStyle(
                        color = if (hit.manual) manualColor else holeColor,
                        fontSize = letterSize.toSp(),
                        shadow = Shadow(Color.Black, Offset(0f, 1f), blurRadius = 5f),
                    ),
                )
                drawText(
                    layout,
                    topLeft = Offset(
                        hit.centerXpx * scale + offsetX + dotRadius * 1.6f,
                        hit.centerYpx * scale + offsetY + dotRadius + letterSize * 0.9f -
                            layout.firstBaseline,
                    ),
                )
            }
        }

        // Recognised digit boxes — debug only.
        if (showDebug) {
            digits.forEach { d ->
                drawRect(
                    color = DIGIT_COLOR,
                    topLeft = Offset(d.left * scale + offsetX, d.top * scale + offsetY),
                    size = Size((d.right - d.left) * scale, (d.bottom - d.top) * scale),
                    style = Stroke(width = STROKE_WIDTH_PX),
                )
            }
        }

        // Ring-value label above each scored hole ("X" for inner-ten), nudged up
        // to avoid overlapping labels in dense clusters.
        if (scores.isNotEmpty()) {
            val gap = labelSize * 0.15f
            val placed = ArrayList<Rect>(scores.size)
            // Place top holes first so lower labels stack above them.
            scores.sortedBy { it.topYpx }.forEach { hit ->
                val label = if (hit.isInnerTen) "X" else hit.ring.toString()
                val layout = textMeasurer.measure(
                    label,
                    TextStyle(
                        color = if (hit.manual) manualColor else scoreColor,
                        fontSize = labelSize.toSp(),
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(Color.Black, Offset(0f, 2f), blurRadius = 6f),
                    ),
                )
                val w = layout.size.width.toFloat()
                val x = hit.centerXpx * scale + offsetX
                var baseline = hit.topYpx * scale + offsetY - labelSize * 0.2f
                var top = baseline - labelSize
                var guard = 0
                while (guard++ <= placed.size) {
                    val hitRect = placed.firstOrNull { r ->
                        x + w / 2f > r.left && x - w / 2f < r.right && baseline > r.top && top < r.bottom
                    } ?: break
                    baseline = hitRect.top - gap
                    top = baseline - labelSize
                }
                drawText(layout, topLeft = Offset(x - w / 2f, baseline - layout.firstBaseline))
                placed.add(Rect(x - w / 2f, top, x + w / 2f, baseline))
            }
        }

        if (centre != null && centre.method != CentreMethod.NONE) {
            // Fitted digit-row lines — debug only (infinite lines, clipped to
            // the image area so they don't bleed into the letterbox bars).
            if (showDebug) {
                clipRect(
                    left = offsetX,
                    top = offsetY,
                    right = offsetX + imageWidth * scale,
                    bottom = offsetY + imageHeight * scale,
                ) {
                    listOfNotNull(centre.horizontalLine, centre.verticalLine).forEach { line ->
                        drawRowLine(line, scale, offsetX, offsetY, ROW_LINE_COLOR, STROKE_WIDTH_PX / 2f)
                    }
                }
            }

            val cx = centre.x * scale + offsetX
            val cy = centre.y * scale + offsetY
            val arm = 24f
            drawLine(
                color = CENTRE_COLOR,
                start = Offset(cx - arm, cy),
                end = Offset(cx + arm, cy),
                strokeWidth = STROKE_WIDTH_PX + 2f,
            )
            drawLine(
                color = CENTRE_COLOR,
                start = Offset(cx, cy - arm),
                end = Offset(cx, cy + arm),
                strokeWidth = STROKE_WIDTH_PX + 2f,
            )
        }
    }
}

/**
 * Draws one target ellipse: rotate the canvas about its centre and draw an
 * axis-aligned oval, matching the (semiMajor, semiMinor, rotationRad)
 * parametrisation the fit and [ringOutline] both use.
 */
private fun DrawScope.drawTargetRing(
    e: FittedEllipse,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    color: Color,
    strokeWidth: Float,
) {
    val ecx = e.cx * scale + offsetX
    val ecy = e.cy * scale + offsetY
    val a = e.semiMajor * scale
    val b = e.semiMinor * scale
    rotate(degrees = (e.rotationRad * 180.0 / PI).toFloat(), pivot = Offset(ecx, ecy)) {
        drawOval(
            color = color,
            topLeft = Offset(ecx - a, ecy - b),
            size = Size(a * 2f, b * 2f),
            style = Stroke(width = strokeWidth),
        )
    }
}

private fun DrawScope.drawRowLine(
    line: TargetLine,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    color: Color,
    strokeWidth: Float,
) {
    // Uniform scaling preserves the direction; extend well past the viewport
    // on both sides and let the clip do the trimming.
    val px = line.px * scale + offsetX
    val py = line.py * scale + offsetY
    val reach = size.width + size.height
    drawLine(
        color = color,
        start = Offset(px - line.dx * reach, py - line.dy * reach),
        end = Offset(px + line.dx * reach, py + line.dy * reach),
        strokeWidth = strokeWidth,
    )
}
