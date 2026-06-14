package eval

import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.calibrateBlackRing
import se.kjellstrand.markera.vision.calibrateFromGrayscale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min

/**
 * Compares three black-7-ring selectors over the dataset and writes annotated
 * 3x3 mosaics for each:
 *   base   - recovered generic selector (calibrateFromGrayscale)
 *   tight  - guide-circle prior, seeded at the image centre
 *   seed   - guide-circle prior, seeded at the digit-line centre
 *
 * The digit-line centres come from the on-device CentreMosaicTest run
 * (build/centre-mosaics/summary.txt), mapped from full-res into the square
 * work space; images whose digit centre was NONE fall back to the image
 * centre (so seed == tight for those). Mirrors the app's square capture.
 */
class BlackRingMosaicTest {

    private val imagesDir = System.getProperty("mosaic.images", "D:/ml/holes/dataset/images")
    private val centresFile = System.getProperty(
        "mosaic.centres", "D:/source/Markera/build/centre-mosaics/summary.txt",
    )
    private val workSize = 1024
    private val guideRadiusPx = 0.35f * workSize
    private val cols = 3
    private val rows = 3

    @Test
    fun `compares base, tight, and digit-seeded black-ring fit`() {
        val dir = File(imagesDir)
        assertTrue("images dir not found: $imagesDir", dir.isDirectory)
        val all = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp") }
            .sortedBy { it.name }
            .toList()
        assertTrue("no images in $imagesDir", all.isNotEmpty())

        val centres = loadCentres(File(centresFile))
        println("[blackring] ${all.size} images; ${centres.size} digit centres loaded")

        val results = all.map { processOne(it, centres) }
        val outDir = File("build/mosaic").apply { mkdirs() }
        writeMosaics(outDir, "black_ring_base_mosaic", results.map { it.baseTile })
        writeMosaics(outDir, "black_ring_tight_mosaic", results.map { it.tightTile })
        writeMosaics(outDir, "black_ring_seed_mosaic", results.map { it.seedTile })

        val baseFit = results.count { it.base != null }
        val tightFit = results.count { it.tight != null }
        val seedFit = results.count { it.seed != null }
        val seedablesWithCentre = results.count { it.hasSeed }

        println("[blackring] === fit counts ===")
        println("[blackring] base : $baseFit/${results.size}")
        println("[blackring] tight: $tightFit/${results.size}")
        println("[blackring] seed : $seedFit/${results.size}  (digit centre available on $seedablesWithCentre)")

        println("[blackring] === digit-seed vs image-centre (only images with a digit centre) ===")
        results.filter { it.hasSeed }.forEach { r ->
            val t = r.tight
            val s = r.seed
            val tag = when {
                t == null && s != null -> "GAINED   "
                t != null && s == null -> "LOST     "
                t != null && s != null && moved(t, s) -> "MOVED    "
                t == null && s == null -> "still-none"
                else -> "same     "
            }
            if (tag.trim() in setOf("GAINED", "LOST", "MOVED")) {
                println("[blackring]   $tag ${r.name}  tight=${fmt(t)}  seed=${fmt(s)}")
            }
        }
        assertTrue("no mosaic written", File(outDir, "black_ring_seed_mosaic_0.png").isFile)
    }

    private fun moved(a: TargetCalibration, b: TargetCalibration): Boolean {
        val dc = abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)
        val dr = abs(a.semiMajorPx - b.semiMajorPx)
        return dc > 8f || dr > 0.1f * a.semiMajorPx
    }

    private fun fmt(c: TargetCalibration?): String =
        if (c == null) "none" else "(%.0f,%.0f a=%.0f)".format(c.centerX, c.centerY, c.semiMajorPx)

    private class TileResult(
        val name: String,
        val base: TargetCalibration?,
        val tight: TargetCalibration?,
        val seed: TargetCalibration?,
        val hasSeed: Boolean,
        val baseTile: BufferedImage,
        val tightTile: BufferedImage,
        val seedTile: BufferedImage,
    )

    private fun processOne(file: File, centres: Map<String, Pair<Float, Float>>): TileResult {
        val src = ImageIO.read(file) ?: error("could not decode ${file.name}")
        val work = squareDownscale(src, workSize)
        val gray = toGray(work)

        val base = calibrateFromGrayscale(gray, work.width, work.height)
        val tight = calibrateBlackRing(gray, work.width, work.height, guideRadiusPx)

        val digit = centres[file.name]
        val seedXY = digit?.let { toWork(it.first, it.second, src.width, src.height) }
        val seed = if (seedXY != null) {
            calibrateBlackRing(gray, work.width, work.height, guideRadiusPx, seedXY.first, seedXY.second)
        } else {
            tight
        }

        return TileResult(
            name = file.name,
            base = base,
            tight = tight,
            seed = seed,
            hasSeed = seedXY != null,
            baseTile = annotate(work, base, file.name, guide = false, seed = null),
            tightTile = annotate(work, tight, file.name, guide = true, seed = null),
            seedTile = annotate(work, seed, file.name, guide = true, seed = seedXY),
        )
    }

    /** Map a full-res point into the square-cropped, downscaled work space. */
    private fun toWork(px: Float, py: Float, srcW: Int, srcH: Int): Pair<Float, Float> {
        val side = min(srcW, srcH)
        val x0 = (srcW - side) / 2f
        val y0 = (srcH - side) / 2f
        val s = workSize / side.toFloat()
        return (px - x0) * s to (py - y0) * s
    }

    private fun loadCentres(path: File): Map<String, Pair<Float, Float>> {
        if (!path.isFile) {
            println("[blackring] no centres file at $path; seed falls back to image centre")
            return emptyMap()
        }
        val re = Regex("""^(\S+):\s+.*method=(\w+)\s+centre=\((-?\d+), (-?\d+)\)""")
        val map = HashMap<String, Pair<Float, Float>>()
        path.readLines().forEach { line ->
            val m = re.find(line) ?: return@forEach
            val (name, method, x, y) = m.destructured
            if (method != "NONE") map[name] = x.toFloat() to y.toFloat()
        }
        return map
    }

    private fun writeMosaics(outDir: File, prefix: String, tiles: List<BufferedImage>) {
        tiles.chunked(cols * rows).forEachIndexed { i, chunk ->
            val grid = mosaic(chunk, cols, rows)
            ImageIO.write(grid, "png", File(outDir, "${prefix}_$i.png"))
        }
    }

    private fun squareDownscale(src: BufferedImage, size: Int): BufferedImage {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null)
        g.dispose()
        return out
    }

    private fun toGray(img: BufferedImage): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h)
        for (yy in 0 until h) {
            val rowBase = yy * w
            for (xx in 0 until w) {
                val rgb = img.getRGB(xx, yy)
                val r = (rgb ushr 16) and 0xFF
                val gch = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                out[rowBase + xx] = ((r * 299 + gch * 587 + b * 114) / 1000).toByte()
            }
        }
        return out
    }

    private fun annotate(
        img: BufferedImage,
        calib: TargetCalibration?,
        name: String,
        guide: Boolean,
        seed: Pair<Float, Float>?,
    ): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, null)
        val stroke = maxOf(2f, img.width / 320f)
        if (guide) {
            g.color = Color(0x55, 0xFF, 0xFF, 0x80)
            g.stroke = BasicStroke(maxOf(1f, stroke / 2f))
            val r = guideRadiusPx
            g.draw(Ellipse2D.Double((img.width / 2f - r).toDouble(), (img.height / 2f - r).toDouble(), (2 * r).toDouble(), (2 * r).toDouble()))
        }
        if (seed != null) {
            // Digit-line centre used as the selector seed.
            g.color = Color(0xFF, 0xC4, 0x00)
            g.stroke = BasicStroke(stroke)
            val (sx, sy) = seed
            val a = img.width / 50f
            g.drawLine((sx - a).toInt(), sy.toInt(), (sx + a).toInt(), sy.toInt())
            g.drawLine(sx.toInt(), (sy - a).toInt(), sx.toInt(), (sy + a).toInt())
        }
        if (calib != null) {
            g.color = Color(0x00, 0xE6, 0x76)
            g.stroke = BasicStroke(stroke)
            val tx = AffineTransform()
            tx.translate(calib.centerX.toDouble(), calib.centerY.toDouble())
            tx.rotate(calib.rotationRad.toDouble())
            val ell = Ellipse2D.Double(
                -calib.semiMajorPx.toDouble(),
                -calib.semiMinorPx.toDouble(),
                2.0 * calib.semiMajorPx,
                2.0 * calib.semiMinorPx,
            )
            g.draw(tx.createTransformedShape(ell))
            g.color = Color(0xFF, 0x17, 0x44)
            val arm = img.width / 40f
            g.drawLine((calib.centerX - arm).toInt(), calib.centerY.toInt(), (calib.centerX + arm).toInt(), calib.centerY.toInt())
            g.drawLine(calib.centerX.toInt(), (calib.centerY - arm).toInt(), calib.centerX.toInt(), (calib.centerY + arm).toInt())
            g.color = Color.WHITE
            g.drawString("%s  conf=%.2f".format(name, calib.confidence), 12, img.height - 14)
        } else {
            g.color = Color(0xFF, 0x17, 0x44)
            g.drawString("$name  NO FIT", 12, img.height - 14)
        }
        g.dispose()
        return out
    }
}
