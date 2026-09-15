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
import se.kjellstrand.markera.vision.RingProbe
import se.kjellstrand.markera.vision.RingProbeResult
import se.kjellstrand.markera.vision.estimateCentre
import se.kjellstrand.markera.vision.fit67Ring
import se.kjellstrand.markera.vision.fit67RingByProbes
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max

private const val TAG = "BlackRing67Test"
private const val PROBE_DEVICE_DIR = "/data/local/tmp/ring-probe"
private const val PROBE_DIAMETER_PX = 50

/**
 * On-device 6/7-ring probe fit ([fit67RingByProbes]) over real photos; writes
 * annotated overlays for the host to pull and judge.
 */
@RunWith(AndroidJUnit4::class)
class BlackRing67Test {

    /**
     * Probe-disk 6/7 fit ([fit67RingByProbes]) over the app's cached series
     * images (`cacheDir/series/<id>.jpg`) plus the `*.jpg` listed in
     * [PROBE_DEVICE_DIR]/index.txt (same name in both: the cache copy wins). Writes
     * `filesDir/ring-probe/<id>.png` (full overlay) and `<id>-zoom.png` (the
     * four probe neighbourhoods at 2x).
     */
    @Test
    fun probeRings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cached = File(context.cacheDir, "series").listFiles { f -> f.name.endsWith(".jpg") }
            .orEmpty().associate { it.name to { it.readBytes() } }
        val pushed = String(shellReadBytes("cat $PROBE_DEVICE_DIR/index.txt"))
            .lines().map { it.trim() }.filter { it.endsWith(".jpg") }
            .associateWith { name -> { shellReadBytes("$PROBE_DEVICE_DIR/$name") } }
        val images = (pushed + cached).toList()
            .sortedWith(compareBy({ it.first.substringBeforeLast('.').toLongOrNull() ?: Long.MAX_VALUE }, { it.first }))
        assertTrue("no cached series images — open Historik on the phone first", images.isNotEmpty())
        Log.i(TAG, "probeRings: ${cached.size} cached + ${pushed.size} pushed -> ${images.size} images")

        val outDir = File(context.filesDir, "ring-probe")
        outDir.deleteRecursively()
        outDir.mkdirs()

        var withCentre = 0
        var withEllipse = 0
        val pathCounts = sortedMapOf<String, Int>()
        val detector = DigitDetector()
        try {
            for ((name, read) in images) {
                val id = name.substringBeforeLast('.')
                val bmp = decodeMutable(read(), maxDim = 3072)
                val digits = runBlocking { detector.detect(bmp) }
                val centre = estimateCentre(digits, bmp.width, bmp.height)
                val haveCentre = centre.method != CentreMethod.NONE
                val gray = if (haveCentre) toGray(bmp) else null
                val result = gray?.let {
                    fit67RingByProbes(it, bmp.width, bmp.height, digits, centre, PROBE_DIAMETER_PX)
                }
                // The scan's choice (the probe ellipse if plausible, else no ring), logged beside the probes.
                val ringPath = if (gray?.let { fit67Ring(it, bmp.width, bmp.height, digits, centre) } != null) "probes" else "none"
                pathCounts[ringPath] = (pathCounts[ringPath] ?: 0) + 1
                if (haveCentre) withCentre++
                if (result?.ellipse != null) withEllipse++

                // Zoom sheet first, from the clean frame (no second full-size copy).
                result?.let { writePng(zoomSheet(bmp, it), File(outDir, "$id-zoom.png")) }
                val canvas = Canvas(bmp)
                val s = max(2f, max(bmp.width, bmp.height) / 500f)
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s }
                stroke.color = 0xFF00B0FF.toInt()
                digits.forEach { if (it.value in 6..9) canvas.drawRect(it.left, it.top, it.right, it.bottom, stroke) }
                if (haveCentre) {
                    stroke.color = 0xFFFF1744.toInt()
                    stroke.strokeWidth = s * 2f
                    val arm = s * 12f
                    canvas.drawLine(centre.x - arm, centre.y, centre.x + arm, centre.y, stroke)
                    canvas.drawLine(centre.x, centre.y - arm, centre.x, centre.y + arm, stroke)
                }
                result?.let { drawProbes(canvas, it, s) }
                writePng(bmp, File(outDir, "$id.png"))

                val probes = result?.probes.orEmpty()
                fun List<RingProbe>.fmt(f: (RingProbe) -> Any) = joinToString(",", "[", "]") { f(it).toString() }
                Log.i(
                    TAG,
                    "$id ${bmp.width}x${bmp.height}: centre=${centre.method} ring=$ringPath digits=${digits.size} " +
                        "start=${probes.fmt { "%.0f".format(Locale.US, hypot(it.startX - centre.x, it.startY - centre.y)) }} " +
                        "dark=${probes.fmt { "%.3f".format(Locale.US, it.darkFraction) }} " +
                        "iter=${probes.fmt { it.iterations }} " +
                        "travel=${probes.fmt { "%.1f".format(Locale.US, hypot(it.x - it.startX, it.y - it.startY)) }} " +
                        "ellipse=${result?.ellipse}",
                )
                bmp.recycle()
            }
        } finally {
            detector.close()
        }
        Log.i(TAG, "probeRings summary: images=${images.size} withCentre=$withCentre withEllipse=$withEllipse ringPaths=$pathCounts")
        assertTrue("no overlays written", (outDir.listFiles()?.size ?: 0) > 0)
    }

    private fun writePng(bmp: Bitmap, file: File) =
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }

    /** Probe markers; [s] is the stroke unit in image px (the zoom sheet passes 1). */
    private fun drawProbes(canvas: Canvas, result: RingProbeResult, s: Float) {
        val rho = PROBE_DIAMETER_PX / 2f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        result.ellipse?.let {
            stroke.color = 0xFF00E676.toInt()
            stroke.strokeWidth = s * 1.5f
            drawEllipse(canvas, it, stroke)
        }
        for (p in result.probes) {
            stroke.color = Color.WHITE
            stroke.strokeWidth = s * 0.5f
            canvas.drawLine(p.startX, p.startY, p.x, p.y, stroke)
            stroke.color = 0xFFFFEA00.toInt()
            stroke.strokeWidth = s
            val arm = s * 5f
            canvas.drawLine(p.startX - arm, p.startY - arm, p.startX + arm, p.startY + arm, stroke)
            canvas.drawLine(p.startX - arm, p.startY + arm, p.startX + arm, p.startY - arm, stroke)
            stroke.color = 0xFF00E5FF.toInt()
            stroke.strokeWidth = s * 0.75f
            canvas.drawCircle(p.x, p.y, rho, stroke)
            fill.color = 0xFFFF00FF.toInt()
            canvas.drawCircle(p.x, p.y, s * 1.5f, fill)
            fill.color = Color.WHITE
            fill.textSize = s * 10f
            fill.setShadowLayer(s, 0f, 0f, Color.BLACK)
            canvas.drawText(
                "%.3f %dit".format(Locale.US, p.darkFraction, p.iterations),
                p.x + rho + 3f * s, p.y - rho, fill,
            )
            fill.clearShadowLayer()
        }
    }

    /** 2x2 sheet (L R / T B) of 300 px crops around each final probe, shown at 2x. */
    private fun zoomSheet(src: Bitmap, result: RingProbeResult): Bitmap {
        val tile = 600f
        val sheet = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        result.probes.forEachIndexed { i, p ->
            val tx = (i % 2) * tile
            val ty = (i / 2) * tile
            canvas.save()
            canvas.clipRect(tx, ty, tx + tile, ty + tile)
            canvas.translate(tx, ty)
            canvas.scale(2f, 2f)
            canvas.translate(150f - p.x, 150f - p.y)
            canvas.drawBitmap(src, 0f, 0f, null)
            drawProbes(canvas, result, 1f)
            canvas.restore()
            canvas.drawText("LRTB"[i].toString(), tx + 12f, ty + 48f, label)
        }
        return sheet
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
}
