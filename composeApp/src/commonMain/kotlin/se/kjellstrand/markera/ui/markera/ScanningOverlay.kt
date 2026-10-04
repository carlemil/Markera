package se.kjellstrand.markera.ui.markera

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.theme.MarkeraGreen
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.height
import se.kjellstrand.markera.vision.width

/** One sweep lap, shared by the sweep animation and the blip reshuffle. */
private const val SCAN_SWEEP_MS = 1300

/**
 * The five targets the sweep "discovers" in one lap: (angle°, radius fraction).
 * One blip per 72° bucket, jittered inside it, so two never overlap; radii stay
 * in 0.4..0.9 of the ring so they sit inside the ellipse, off the crosshair.
 */
internal fun scanBlips(random: Random): List<Pair<Float, Float>> =
    List(5) { i ->
        (i * 72f + 6f + random.nextFloat() * 60f) to (0.4f + random.nextFloat() * 0.5f)
    }

/**
 * Radar-sweep "working" animation shown over the frozen frame while hole
 * detection runs. The sweep orbits the detected [centre] and is sized to the
 * detected 6/7 [ring], so it scans exactly the target the geometry phase found;
 * with no centre/ring it falls back to the viewport centre and a fixed radius.
 * A green sweep line drags a fading trail; blips flash as it passes, moving to
 * fresh spots once per lap.
 * Indeterminate — it loops until the [ScanPhase.HOLES] phase clears.
 */
@Composable
internal fun ScanningOverlay(
    centre: CentreEstimate?,
    ring: FittedEllipse?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
) {
    val sweep = rememberInfiniteTransition(label = "scan")
    val angle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SCAN_SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    val green = MarkeraGreen
    // Fresh blips once per lap, counted from its own clock: deriving the lap
    // from `angle` would read the animation in composition and recompose every
    // frame, while the detector already has the CPU.
    var lap by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SCAN_SWEEP_MS.toLong())
            lap++
        }
    }
    val blips = remember(lap) { scanBlips(Random) }
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Anchor the radar to the detected 6/7 ellipse — its centre, both
            // semi-axes and tilt — using the same fit-centre letterbox as the
            // DetectionOverlay, so the sweep traces the green ellipse drawn
            // underneath. With no ring, fall back to a circle at the detected
            // centre; with no geometry at all, the viewport centre.
            val hasImage = imageWidth > 0 && imageHeight > 0
            val s = if (hasImage) min(size.width / imageWidth, size.height / imageHeight) else 1f
            val offsetX = (size.width - imageWidth * s) / 2f
            val offsetY = (size.height - imageHeight * s) / 2f
            // Pivot the sweep on the true target centre — the digit-row line
            // intersection — falling back to the viewport centre (a ring is only
            // ever fitted from a found centre).
            val pivot = when {
                centre != null && centre.method != CentreMethod.NONE && hasImage ->
                    Offset(centre.x * s + offsetX, centre.y * s + offsetY)
                else -> Offset(size.width / 2f, size.height / 2f)
            }
            // The ellipse-based elements (rotating tip, guide rings, trail,
            // blips) ride the *detected* ellipse, centred on the ring centre —
            // so the sweep's outer point traces the green 6/7 ellipse exactly.
            // The sweep line still springs from the digit-centre pivot.
            val ec = if (ring != null && hasImage) {
                Offset(ring.cx * s + offsetX, ring.cy * s + offsetY)
            } else {
                pivot
            }
            // Outer ellipse shape = the detected 6/7 ring (same semi-axes + tilt);
            // a plain circle when there's no ring yet.
            val a: Float // canvas semi-major
            val b: Float // canvas semi-minor
            val rot: Float // ellipse tilt, radians
            if (ring != null && hasImage) {
                a = ring.semiMajor * s
                b = ring.semiMinor * s
                rot = ring.rotationRad
            } else {
                val r = 0.42f * min(size.width, size.height)
                a = r
                b = r
                rot = 0f
            }
            val rotDeg = (rot * 180.0 / PI).toFloat()
            val ct = cos(rot)
            val st = sin(rot)
            // Point on the detected (tilted) ellipse at parameter [t], radius
            // fraction [f] — centred on the ring centre [ec].
            fun onEllipse(t: Float, f: Float = 1f): Offset {
                val lx = a * f * cos(t)
                val ly = b * f * sin(t)
                return Offset(ec.x + lx * ct - ly * st, ec.y + lx * st + ly * ct)
            }

            val faint = green.copy(alpha = 0.18f)
            // Concentric guide ellipses, tilted with the ring.
            for (i in 1..3) {
                val fa = a * i / 3f
                val fb = b * i / 3f
                rotate(rotDeg, ec) {
                    drawOval(
                        color = faint,
                        topLeft = Offset(ec.x - fa, ec.y - fb),
                        size = Size(fa * 2f, fb * 2f),
                        style = Stroke(2f),
                    )
                }
            }
            // Crosshair along the ellipse's major and minor axes.
            drawLine(faint, onEllipse(0f), onEllipse(PI.toFloat()), 1.5f)
            drawLine(faint, onEllipse((PI / 2.0).toFloat()), onEllipse((3.0 * PI / 2.0).toFloat()), 1.5f)
            // Fading trail: a wedge swept from the pivot (the red crosshair) out
            // to the detected ellipse, brightest at the leading sweep line and
            // fading behind it. Built from the same onEllipse() points, so it
            // springs from the crosshair yet its outer edge rides the ellipse.
            val trailSpanDeg = 70f
            val trailSteps = 28
            for (i in 0 until trailSteps) {
                val t0 = ((angle - trailSpanDeg * (1f - i / trailSteps.toFloat())) * PI / 180.0).toFloat()
                val t1 = ((angle - trailSpanDeg * (1f - (i + 1) / trailSteps.toFloat())) * PI / 180.0).toFloat()
                val p0 = onEllipse(t0)
                val p1 = onEllipse(t1)
                val wedge = Path().apply {
                    moveTo(pivot.x, pivot.y)
                    lineTo(p0.x, p0.y)
                    lineTo(p1.x, p1.y)
                    close()
                }
                drawPath(wedge, color = green.copy(alpha = 0.45f * (i + 1) / trailSteps.toFloat()))
            }
            // Leading sweep line, from the crosshair out to the ellipse edge.
            val rad = (angle * PI / 180.0).toFloat()
            drawLine(green, start = pivot, end = onEllipse(rad), strokeWidth = 3f)
            // Each blip flares as the sweep passes, then fades over ~70°.
            blips.forEach { (blipAngle, r) ->
                val since = ((angle - blipAngle) % 360f + 360f) % 360f
                val alpha = (1f - since / 70f).coerceIn(0f, 1f)
                if (alpha > 0f) {
                    val p = onEllipse((blipAngle * PI / 180.0).toFloat(), r)
                    drawCircle(green.copy(alpha = alpha), radius = 5f + 5f * alpha, center = p)
                }
            }
        }
        Text(
            text = stringResource(Res.string.markera_scanning),
            color = green,
            style = MaterialTheme.typography.titleMedium.copy(
                // Strong black drop shadow so the text reads over the target.
                shadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 10f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}
