package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import se.kjellstrand.markera.ui.markera.FrameSource
import se.kjellstrand.markera.ui.markera.ScanEngine
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.PlatformImage
import se.kjellstrand.markera.vision.RawDetection

/**
 * A drawn target for the scan screen's UI tests: a [SIZE]² white frame with the black
 * (the 6/7 edge, 100 mm) a disk of [RIM] px at the middle, so 4 px per mm. The real
 * centre, ring-probe and scoring code run on it; only OCR and the hole model are
 * stood in for, by [FakeScanEngine].
 */
object FakeTarget {
    const val SIZE = 1500
    const val RIM = 400f
    const val CENTRE = SIZE / 2f
    const val PX_PER_MM = RIM / 100f

    /** Where a hole [mm] from the centre at [degrees] (clockwise from the right) sits, in image px. */
    fun at(mm: Float, degrees: Float): Pair<Float, Float> {
        val a = Math.toRadians(degrees.toDouble())
        return (CENTRE + mm * PX_PER_MM * cos(a)).toFloat() to (CENTRE + mm * PX_PER_MM * sin(a)).toFloat()
    }

    /** One hole in each ring 10..6, well inside it: a 40 to start with. */
    val HOLES = listOf(20f to 30f, 40f to 100f, 65f to 170f, 90f to 240f, 115f to 300f).map { (mm, deg) -> at(mm, deg) }

    fun frame(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.eraseColor(android.graphics.Color.rgb(220, 220, 220))
        Canvas(bitmap).drawCircle(CENTRE, CENTRE, RIM, Paint().apply { color = android.graphics.Color.rgb(30, 30, 30); isAntiAlias = true })
    }

    /**
     * The 6..9 digits on the four rows, as the OCR reads them on a real target: digit v at
     * (v - 6.5) ring widths in from the rim, the rim itself guessed 30 px off so the probes travel.
     */
    fun digits(): List<DigitDetection> =
        listOf(Triple(-1f, 0f, -30f), Triple(1f, 0f, 30f), Triple(0f, -1f, 30f), Triple(0f, 1f, -30f)).flatMap { (ux, uy, off) ->
            (6..9).map { v ->
                val d = RIM + off - RIM / 4f * (v - 6.5f)
                val x = CENTRE + ux * d
                val y = CENTRE + uy * d
                DigitDetection(x - 12f, y - 15f, x + 12f, y + 15f, v, 1f)
            }
        }
}

/** Always hands out a fresh copy of [FakeTarget.frame]; the preview is a grey square. */
class FakeFrameSource : FrameSource {
    var captures = 0
        private set
    override val requiresCameraPermission = false

    @Composable
    override fun Preview(onError: (Throwable) -> Unit, modifier: Modifier) {
        Box(modifier.background(Color.DarkGray))
    }

    override suspend fun capture(): PlatformImage {
        captures++
        return FakeTarget.frame()
    }

    override fun onResumeLive() = Unit
}

/**
 * Reads [FakeTarget.digits] and finds [holes] (image px), 22lr-sized boxes, whatever the frame.
 * With [readsDigits] off the OCR reads nothing: no centre, so no ring, and the scan asks to rescan or mark by hand.
 */
class FakeScanEngine(var holes: List<Pair<Float, Float>> = FakeTarget.HOLES, var readsDigits: Boolean = true) : ScanEngine {
    override val inputSize = 1536
    override suspend fun digits(image: PlatformImage) = if (readsDigits) FakeTarget.digits() else emptyList()
    override suspend fun holes(inputChw: FloatArray): List<RawDetection> {
        val scale = inputSize.toFloat() / FakeTarget.SIZE
        val side = 5.66f * FakeTarget.PX_PER_MM * scale
        return holes.map { (x, y) -> RawDetection(x * scale, y * scale, side, side, 0.9f) }
    }
    override fun close() = Unit
}
