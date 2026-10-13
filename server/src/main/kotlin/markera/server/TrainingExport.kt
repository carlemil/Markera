package markera.server

import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The next training round's data, built from what the users confirmed. A YOLO zip (images, one label file per
 * image holding the confirmed holes, data.yaml) plus two sidecars the training scripts decide how to use:
 * negatives.csv (detections the user removed or dragged away: false positives) and nudges.csv (detections the
 * user only moved a little: localisation errors, the confirmed hole is the label).
 *
 * Everything here is pure; the route in Admin.kt supplies the series and the photo files.
 */

/** YOLO class names, index = the label's class: `Hole 0` .. `Hole 10`, then `Hole X` (the inner ten). */
val TRAINING_CLASSES: List<String> = (0..10).map { "Hole $it" } + "Hole X"

/** A box in the photo's own fractions: centre and size, all 0..1. */
data class ExportBox(val cx: Double, val cy: Double, val w: Double, val h: Double)

data class PositiveLabel(val classIndex: Int, val box: ExportBox)

/** A false positive: where the detector put a "hole" that is none. [conf] is null for series saved before the box was kept. */
data class NegativeBox(val box: ExportBox, val conf: Double?, val reason: String)

/** A detection dragged less than a hole diameter: the label is [confirmed], [detected] is what the model had. */
data class NudgedBox(val detected: ExportBox, val confirmed: ExportBox, val conf: Double?)

data class SeriesTraining(
    val seriesId: Long,
    val labels: List<PositiveLabel>,
    val negatives: List<NegativeBox>,
    val nudges: List<NudgedBox>,
)

/** Nominal bullet diameters in mm, the app's `Caliber` table: the server only needs them for the fallback box. */
private val CALIBER_DIAMETER_MM = mapOf(
    "22lr" to 5.66, "22wmr" to 5.70, "17hmr" to 4.37, "32" to 7.92, "380" to 9.02, "9mm" to 9.02, "38" to 9.07,
    "357" to 9.07, "40" to 10.16, "10mm" to 10.16, "44" to 10.90, "45" to 11.45, "223" to 5.69, "243" to 6.17,
    "6.5x55" to 6.71, "6.5cm" to 6.71, "270" to 7.04, "308" to 7.82, "30-06" to 7.82, "7.62x39" to 7.90,
    "8x57" to 8.20, "9.3x62" to 9.30, "300wm" to 7.82,
)
private const val DEFAULT_DIAMETER_MM = 7.92
private const val BLACK_RING_RADIUS_MM = 100.0

/**
 * Sorts every hole of [series] into a label, a false positive or a nudge, in the photo's fractions. Null when
 * the series has no frame size, since a pixel position then cannot be placed on the photo.
 *
 * - A confirmed hole with a position is a label (class = its confirmed score), boxed by the detector's own box
 *   when it was stored, else by a caliber-sized square.
 * - A detection removed by the user ([Hole.deleted]), dragged more than one hole diameter, or scored 0 by hand
 *   is a false positive at the detector's position. A dragged one is also a label where it now sits.
 * - A detection dragged less than a diameter is a [NudgedBox].
 */
fun classifySeries(series: Series): SeriesTraining? {
    val width = series.imageWidth?.takeIf { it > 0 } ?: return null
    val height = series.imageHeight?.takeIf { it > 0 } ?: return null
    val fallbackSide = fallbackSidePx(series, width)

    fun box(x: Double, y: Double, w: Double?, h: Double?): ExportBox {
        val bw = w ?: fallbackSide
        val bh = h ?: fallbackSide
        val left = max(0.0, x - bw / 2)
        val right = min(width.toDouble(), x + bw / 2)
        val top = max(0.0, y - bh / 2)
        val bottom = min(height.toDouble(), y + bh / 2)
        return ExportBox(
            (left + right) / 2 / width, (top + bottom) / 2 / height,
            (right - left) / width, (bottom - top) / height,
        )
    }

    val labels = mutableListOf<PositiveLabel>()
    val negatives = mutableListOf<NegativeBox>()
    val nudges = mutableListOf<NudgedBox>()
    for (h in series.holes) {
        val dx = h.detectedX
        val dy = h.detectedY
        val detectedBox = if (dx != null && dy != null) box(dx, dy, h.detectedW, h.detectedH) else null
        if (h.deleted) {
            // A legacy deleted copy without a detected position still has the position it was removed from.
            val at = detectedBox ?: if (h.x != null && h.y != null) box(h.x, h.y, h.detectedW, h.detectedH) else null
            if (at != null && h.detectedRing != null) negatives += NegativeBox(at, h.detectedConf, "deleted")
            continue
        }
        val x = h.x
        val y = h.y
        if (x == null || y == null) continue
        if (detectedBox != null && h.ring == 0 && !h.innerTen && h.detectedRing != 0) {
            // The user scored the detection 0: not a hole.
            negatives += NegativeBox(detectedBox, h.detectedConf, "zeroed")
            continue
        }
        val confirmed = box(x, y, h.detectedW, h.detectedH)
        labels += PositiveLabel(if (h.innerTen) TRAINING_CLASSES.lastIndex else h.ring.coerceIn(0, 10), confirmed)
        if (detectedBox != null && dx != null && dy != null && (x != dx || y != dy)) {
            val diameter = h.detectedW?.let { w -> h.detectedH?.let { (w + it) / 2 } } ?: fallbackSide
            if (hypot(x - dx, y - dy) > diameter) {
                negatives += NegativeBox(detectedBox, h.detectedConf, "moved")
            } else {
                nudges += NudgedBox(detectedBox, confirmed, h.detectedConf)
            }
        }
    }
    return SeriesTraining(series.id, labels, negatives, nudges)
}

/** A caliber-sized hole box side in source px: the caliber's diameter at the scan's scale, else 3% of the frame. */
private fun fallbackSidePx(series: Series, width: Int): Double {
    val geometry = series.geometry ?: return width * 0.03
    if (geometry.ringSemiMajor <= 0.0) return width * 0.03
    val diameter = CALIBER_DIAMETER_MM[series.caliber] ?: DEFAULT_DIAMETER_MM
    return diameter * geometry.ringSemiMajor / BLACK_RING_RADIUS_MM
}

private fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)

/** One YOLO label line per confirmed hole: `class cx cy w h`. */
fun yoloLabels(labels: List<PositiveLabel>): String =
    labels.joinToString("") { "${it.classIndex} ${f(it.box.cx)} ${f(it.box.cy)} ${f(it.box.w)} ${f(it.box.h)}\n" }

const val NEGATIVES_HEADER = "image;cx;cy;w;h;conf;reason"
const val NUDGES_HEADER = "image;detected_cx;detected_cy;cx;cy;w;h;conf"

fun negativesCsv(all: List<SeriesTraining>): String = buildString {
    append(NEGATIVES_HEADER).append('\n')
    for (s in all) for (n in s.negatives) {
        append("images/${s.seriesId}.jpg;${f(n.box.cx)};${f(n.box.cy)};${f(n.box.w)};${f(n.box.h)};")
        append(n.conf?.let { f(it) }.orEmpty()).append(';').append(n.reason).append('\n')
    }
}

fun nudgesCsv(all: List<SeriesTraining>): String = buildString {
    append(NUDGES_HEADER).append('\n')
    for (s in all) for (n in s.nudges) {
        append("images/${s.seriesId}.jpg;${f(n.detected.cx)};${f(n.detected.cy)};")
        append("${f(n.confirmed.cx)};${f(n.confirmed.cy)};${f(n.confirmed.w)};${f(n.confirmed.h)};")
        append(n.conf?.let { f(it) }.orEmpty()).append('\n')
    }
}

fun dataYaml(): String = buildString {
    append("path: .\ntrain: images\nval: images\nnc: ${TRAINING_CLASSES.size}\nnames:\n")
    TRAINING_CLASSES.forEachIndexed { i, name -> append("  $i: $name\n") }
}

/**
 * Streams the zip: `images/<id>.jpg`, `labels/<id>.txt`, `negatives.csv`, `nudges.csv` and `data.yaml`. A series
 * is in it when it is classifiable and [image] finds its photo.
 */
fun writeTrainingZip(out: OutputStream, series: Sequence<Series>, image: (Long) -> File?) {
    val written = mutableListOf<SeriesTraining>()
    ZipOutputStream(out).use { zip ->
        fun entry(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        for (s in series) {
            val training = classifySeries(s) ?: continue
            val file = image(s.id)?.takeIf { it.isFile } ?: continue
            zip.putNextEntry(ZipEntry("images/${s.id}.jpg"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            entry("labels/${s.id}.txt", yoloLabels(training.labels).toByteArray())
            written += training
        }
        entry("negatives.csv", negativesCsv(written).toByteArray())
        entry("nudges.csv", nudgesCsv(written).toByteArray())
        entry("data.yaml", dataYaml().toByteArray())
    }
}
