package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.DigitDetection
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.TargetLine
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.rowDigitCounts
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "CentreMosaicTest"

/** Host pushes the dataset images plus an ordered index.txt here. */
private const val DEVICE_DIR = "/data/local/tmp/centre-eval"

// Mosaic geometry mirrors eval/Mosaic.kt.
private const val COLS = 3
private const val ROWS = 3
private const val CELL = 760
private const val GAP = 8

// Overlay styling mirrors DetectionOverlay.kt.
private const val DIGIT_COLOR = 0xFF00B0FF.toInt()
private const val ROW_LINE_COLOR = 0xFFFFC400.toInt()
private const val CENTRE_COLOR = 0xFFFF1744.toInt()

/**
 * Runs the real centre pipeline (ML Kit digits -> estimateCentre) over every
 * dataset image pushed to [DEVICE_DIR] and writes annotated 3x3 mosaic PNGs to
 * the app's filesDir/centre-mosaic for the host to pull and eyeball.
 *
 * The images are read via the instrumentation's shell domain because SELinux
 * blocks untrusted apps from opening shell_data_file directly.
 */
@RunWith(AndroidJUnit4::class)
class CentreMosaicTest {

    @Test
    fun renderCentreMosaics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names = String(shellReadBytes("$DEVICE_DIR/index.txt"))
            .trim().lines().filter { it.isNotBlank() }
        assertTrue("index.txt is empty — did the host push $DEVICE_DIR?", names.isNotEmpty())
        Log.i(TAG, "processing ${names.size} images")

        val outDir = File(instrumentation.targetContext.filesDir, "centre-mosaic")
        outDir.deleteRecursively()
        outDir.mkdirs()

        val summary = StringBuilder()
        var noneCount = 0
        val stats = ArrayList<ImageStat>()
        val detector = DigitDetector()
        try {
            names.chunked(COLS * ROWS).forEachIndexed { mosaicIndex, chunk ->
                val mosaic = newMosaicCanvasBitmap()
                val canvas = Canvas(mosaic)
                chunk.forEachIndexed { cell, name ->
                    val stat = renderInto(detector, name, canvas, cell)
                    stats += stat
                    if (stat.method == CentreMethod.NONE) noneCount++
                    val line = "$name: digits=${stat.total} h=${stat.h} v=${stat.v} " +
                        "method=${stat.method} centre=(%.0f, %.0f)".format(stat.x, stat.y)
                    Log.i(TAG, line)
                    summary.appendLine(line)
                }
                writeMosaic(outDir, "mosaic_$mosaicIndex", mosaic)
            }

            // Extra mosaic of the 9 images with the fewest digits in either
            // orientation — the hardest cases, where the relaxed straddle rule
            // is most likely to make or break a centre.
            val fewest = stats
                .sortedWith(compareBy({ it.minOrientation }, { it.total }, { it.name }))
                .take(COLS * ROWS)
            val mosaic = newMosaicCanvasBitmap()
            val canvas = Canvas(mosaic)
            fewest.forEachIndexed { cell, stat -> renderInto(detector, stat.name, canvas, cell) }
            writeMosaic(outDir, "mosaic_fewest", mosaic)
            summary.appendLine(
                "fewest(min h/v): " + fewest.joinToString { "${it.name}[h${it.h}v${it.v}]" },
            )
        } finally {
            detector.close()
        }

        summary.appendLine("total=${names.size} none=$noneCount")
        File(outDir, "summary.txt").writeText(summary.toString())

        val expected = (names.size + COLS * ROWS - 1) / (COLS * ROWS)
        assertTrue(
            "standard mosaics missing",
            (0 until expected).all { File(outDir, "mosaic_$it.png").isFile },
        )
        assertTrue("fewest mosaic missing", File(outDir, "mosaic_fewest.png").isFile)
    }

    /** Per-image result; [minOrientation] ranks the sparsest-row images. */
    private data class ImageStat(
        val name: String,
        val total: Int,
        val h: Int,
        val v: Int,
        val method: CentreMethod,
        val x: Float,
        val y: Float,
    ) {
        val minOrientation: Int get() = minOf(h, v)
    }

    /** Decode, run the centre pipeline, annotate, and place into [cell]. */
    private fun renderInto(detector: DigitDetector, name: String, canvas: Canvas, cell: Int): ImageStat {
        val tile = decodeMutable(shellReadBytes("$DEVICE_DIR/$name"))
        val digits = runBlocking { detector.detect(tile) }
        val centre = estimateCentre(digits, tile.width, tile.height)
        val (h, v) = rowDigitCounts(digits)
        annotate(tile, digits, centre)
        drawTileIntoCell(canvas, tile, cell)
        tile.recycle()
        drawCellLabel(canvas, cell, name, h, v, centre)
        return ImageStat(name, digits.size, h, v, centre.method, centre.x, centre.y)
    }

    private fun writeMosaic(outDir: File, name: String, mosaic: Bitmap) {
        val file = File(outDir, "$name.png")
        file.outputStream().use { mosaic.compress(Bitmap.CompressFormat.PNG, 100, it) }
        mosaic.recycle()
        Log.i(TAG, "wrote ${file.absolutePath}")
    }

    /** Read a device file through the shell SELinux domain, binary-safe. */
    private fun shellReadBytes(path: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat $path"),
        ).use { it.readBytes() }

    private fun decodeMutable(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inMutable = true },
        ) ?: throw IllegalStateException("could not decode image (${bytes.size} bytes)")

    /**
     * Draw digit boxes, fitted row lines and the centre crosshair straight on
     * the full-res [tile], with strokes scaled so they read like the on-screen
     * overlay after the ~5x downscale into a mosaic cell.
     */
    private fun annotate(tile: Bitmap, digits: List<DigitDetection>, centre: CentreEstimate) {
        val canvas = Canvas(tile)
        val f = max(tile.width, tile.height) / CELL.toFloat()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        stroke.color = DIGIT_COLOR
        stroke.strokeWidth = 4f * f
        digits.forEach { canvas.drawRect(it.left, it.top, it.right, it.bottom, stroke) }

        if (centre.method == CentreMethod.NONE) return

        stroke.color = ROW_LINE_COLOR
        stroke.strokeWidth = 2f * f
        val reach = (tile.width + tile.height).toFloat()
        listOfNotNull(centre.horizontalLine, centre.verticalLine).forEach { l ->
            canvas.drawRowLine(l, reach, stroke)
        }

        stroke.color = CENTRE_COLOR
        stroke.strokeWidth = 6f * f
        val arm = 24f * f
        canvas.drawLine(centre.x - arm, centre.y, centre.x + arm, centre.y, stroke)
        canvas.drawLine(centre.x, centre.y - arm, centre.x, centre.y + arm, stroke)
    }

    private fun Canvas.drawRowLine(line: TargetLine, reach: Float, paint: Paint) {
        drawLine(
            line.px - line.dx * reach, line.py - line.dy * reach,
            line.px + line.dx * reach, line.py + line.dy * reach,
            paint,
        )
    }

    private fun newMosaicCanvasBitmap(): Bitmap {
        val side = COLS * CELL + (COLS + 1) * GAP
        val mosaic = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaic)
        canvas.drawColor(0xFF181818.toInt())
        val black = Paint().apply { color = Color.BLACK }
        for (i in 0 until COLS * ROWS) {
            val (x, y) = cellOrigin(i)
            canvas.drawRect(x, y, x + CELL.toFloat(), y + CELL.toFloat(), black)
        }
        return mosaic
    }

    private fun cellOrigin(i: Int): Pair<Float, Float> {
        val c = i % COLS
        val r = i / COLS
        return (GAP + c * (CELL + GAP)).toFloat() to (GAP + r * (CELL + GAP)).toFloat()
    }

    /** Letterbox the tile into cell [i], preserving aspect ratio. */
    private fun drawTileIntoCell(canvas: Canvas, tile: Bitmap, i: Int) {
        val (cellX, cellY) = cellOrigin(i)
        val scale = min(CELL.toFloat() / tile.width, CELL.toFloat() / tile.height)
        val dw = tile.width * scale
        val dh = tile.height * scale
        val dx = cellX + (CELL - dw) / 2f
        val dy = cellY + (CELL - dh) / 2f
        canvas.drawBitmap(
            tile,
            Rect(0, 0, tile.width, tile.height),
            RectF(dx, dy, dx + dw, dy + dh),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

    /** Caption band at the cell bottom: filename + per-row counts + result. */
    private fun drawCellLabel(
        canvas: Canvas,
        i: Int,
        name: String,
        h: Int,
        v: Int,
        centre: CentreEstimate,
    ) {
        val (cellX, cellY) = cellOrigin(i)
        val failed = centre.method == CentreMethod.NONE
        val text = if (failed) {
            "$name  h$h v$v  NONE"
        } else {
            "$name  h$h v$v  (%.0f, %.0f)".format(centre.x, centre.y)
        }
        val bandHeight = 34f
        val band = Paint().apply { color = 0xB0000000.toInt() }
        canvas.drawRect(
            cellX, cellY + CELL - bandHeight, cellX + CELL.toFloat(), cellY + CELL.toFloat(), band,
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (failed) CENTRE_COLOR else Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, if (failed) Typeface.BOLD else Typeface.NORMAL)
        }
        canvas.drawText(text, cellX + 8f, cellY + CELL - 10f, textPaint)
    }
}
