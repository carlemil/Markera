package se.kjellstrand.markera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.DigitDetector
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.fit67RingFromDigits
import se.kjellstrand.markera.vision.refine67ToEdge
import java.io.File
import kotlin.math.PI
import kotlin.math.max

private const val TAG = "BlackRing67Test"
private const val DEVICE_DIR = "/data/local/tmp/ring-eval"

/**
 * Real on-device 6/7-ring pipeline: ML Kit digits -> estimateCentre ->
 * fit67RingFromDigits (predict the boundary from the labelled digits) ->
 * refine67ToEdge (snap to the black/white edge). Writes one full-resolution
 * annotated overlay per image to filesDir/ring-overlays for the host to pull
 * and judge.
 */
@RunWith(AndroidJUnit4::class)
class BlackRing67Test {

    @Test
    fun detectRingsFromDigits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names = String(shellReadBytes("$DEVICE_DIR/index.txt"))
            .trim().lines().filter { it.isNotBlank() }
        assertTrue("index.txt empty — push $DEVICE_DIR", names.isNotEmpty())
        Log.i(TAG, "processing ${names.size} images")

        val outDir = File(instrumentation.targetContext.filesDir, "ring-overlays")
        outDir.deleteRecursively()
        outDir.mkdirs()

        var predicted = 0
        var refined = 0
        val detector = DigitDetector()
        try {
            for (name in names) {
                var t = System.nanoTime()
                fun lap(): Long {
                    val now = System.nanoTime()
                    val ms = (now - t) / 1_000_000
                    t = now
                    return ms
                }

                val bytes = shellReadBytes("$DEVICE_DIR/$name"); val tRead = lap()
                val bmp = decodeMutable(bytes); val tDecode = lap()
                val digits = runBlocking { detector.detect(bmp) }; val tOcr = lap()
                val centre = estimateCentre(digits, bmp.width, bmp.height); val tCentre = lap()
                val pred = if (centre.method != CentreMethod.NONE) {
                    fit67RingFromDigits(digits, centre)
                } else {
                    null
                }; val tPred = lap()
                val gray = pred?.let { toGray(bmp) }; val tGray = lap()
                val ref = pred?.let { refine67ToEdge(gray!!, bmp.width, bmp.height, it) }; val tRefine = lap()
                if (pred != null) predicted++
                if (ref != null) refined++

                annotate(bmp, digits, centre.x, centre.y, centre.method != CentreMethod.NONE, pred, ref); val tDraw = lap()
                File(outDir, "${name.substringBeforeLast('.')}.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, it)
                }; val tWrite = lap()
                val total = tRead + tDecode + tOcr + tCentre + tPred + tGray + tRefine + tDraw + tWrite
                val dims = "${bmp.width}x${bmp.height}"
                bmp.recycle()
                Log.i(TAG, "$name: centre=${centre.method} digits=${digits.size} predicted=${pred != null} refined=${ref != null}")
                Log.i(
                    TAG,
                    "$name TIMING $dims total=${total}ms | " +
                        "read=$tRead decode=$tDecode ocr=$tOcr centre=$tCentre " +
                        "pred=$tPred gray=$tGray refine=$tRefine draw=$tDraw write=$tWrite",
                )
            }
        } finally {
            detector.close()
        }
        Log.i(TAG, "predicted $predicted/${names.size}, refined $refined/${names.size}")
        assertTrue("no overlays written", (outDir.listFiles()?.size ?: 0) > 0)
    }

    private fun shellReadBytes(path: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path"),
        ).use { it.readBytes() }

    // Decode downscaled (~maxDim) and mutable: digit OCR, centre and the ellipse
    // fit don't need full resolution, and full-res mutable bitmaps + getPixels +
    // PNG compress OOM the test process on the large source photos.
    private fun decodeMutable(bytes: ByteArray, maxDim: Int = 2000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("could not decode image (${bytes.size} bytes)")
    }

    private fun toGray(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return out
    }

    private fun drawEllipse(canvas: Canvas, e: FittedEllipse, paint: Paint) {
        canvas.save()
        canvas.rotate((e.rotationRad * 180.0 / PI).toFloat(), e.cx, e.cy)
        canvas.drawOval(
            RectF(e.cx - e.semiMajor, e.cy - e.semiMinor, e.cx + e.semiMajor, e.cy + e.semiMinor),
            paint,
        )
        canvas.restore()
    }

    private fun annotate(
        bmp: Bitmap,
        digits: List<se.kjellstrand.markera.vision.DigitDetection>,
        cx: Float,
        cy: Float,
        haveCentre: Boolean,
        predicted: FittedEllipse?,
        refined: FittedEllipse?,
    ) {
        val canvas = Canvas(bmp)
        val s = max(2f, max(bmp.width, bmp.height) / 500f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // digit boxes (blue) used as evidence
        stroke.color = 0xFF00B0FF.toInt()
        stroke.strokeWidth = s
        digits.forEach { if (it.value in 6..9) canvas.drawRect(it.left, it.top, it.right, it.bottom, stroke) }

        // predicted 6/7 ellipse from digits (faint yellow)
        predicted?.let {
            stroke.color = 0xFFFFC400.toInt()
            stroke.strokeWidth = s * 1.5f
            drawEllipse(canvas, it, stroke)
        }
        // refined to edge (green)
        refined?.let {
            stroke.color = 0xFF00E676.toInt()
            stroke.strokeWidth = s * 2.5f
            drawEllipse(canvas, it, stroke)
        }
        // centre crosshair (red)
        if (haveCentre) {
            stroke.color = 0xFFFF1744.toInt()
            stroke.strokeWidth = s * 2f
            val arm = s * 12f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, stroke)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, stroke)
        }
    }
}
