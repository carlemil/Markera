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
import kotlin.math.min

/**
 * Compares the recovered generic black-7-ring selector against the tightened
 * one (guide-circle radius prior + image-centre prior) over the dataset, and
 * writes one annotated 3x3 mosaic set per selector so we can see how the false
 * positives and NO-FITs improve. Mirrors the app's square capture (centre-crop
 * + downscale).
 *
 *   ./gradlew :eval:test --tests "eval.BlackRingMosaicTest" \
 *       -Dmosaic.images="D:/ml/holes/dataset/images"
 */
class BlackRingMosaicTest {

    private val imagesDir = System.getProperty("mosaic.images", "D:/ml/holes/dataset/images")
    private val workSize = 1024

    // The app frames the target inside the viewfinder circle (radius 0.35 of
    // the viewport); in the square work image that is this many pixels.
    private val guideRadiusPx = 0.35f * workSize
    private val cols = 3
    private val rows = 3

    @Test
    fun `compares baseline vs tightened black-ring fit`() {
        val dir = File(imagesDir)
        assertTrue("images dir not found: $imagesDir", dir.isDirectory)
        val all = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp") }
            .sortedBy { it.name }
            .toList()
        assertTrue("no images in $imagesDir", all.isNotEmpty())
        println("[blackring] ${all.size} images; guide radius ${guideRadiusPx.toInt()}px")

        val results = all.map { processOne(it) }
        val outDir = File("build/mosaic").apply { mkdirs() }
        writeMosaics(outDir, "black_ring_base_mosaic", results.map { it.baseTile })
        writeMosaics(outDir, "black_ring_tight_mosaic", results.map { it.tightTile })

        val baseFit = results.count { it.base != null }
        val tightFit = results.count { it.tight != null }
        val bothFit = results.count { it.base != null && it.tight != null }
        val droppedByTight = results.filter { it.base != null && it.tight == null }
        val gainedByTight = results.filter { it.base == null && it.tight != null }

        println("[blackring] === comparison ===")
        println("[blackring] baseline fits : $baseFit/${results.size}")
        println("[blackring] tightened fits: $tightFit/${results.size}")
        println("[blackring] both fit      : $bothFit")
        println("[blackring] dropped by tightening (likely false positives): ${droppedByTight.size}")
        droppedByTight.forEach { println("[blackring]    - ${it.name}  base a=${it.base!!.semiMajorPx.toInt()}") }
        println("[blackring] gained by tightening: ${gainedByTight.size}")
        gainedByTight.forEach { println("[blackring]    + ${it.name}  tight a=${it.tight!!.semiMajorPx.toInt()}") }

        assertTrue("no mosaic written", File(outDir, "black_ring_tight_mosaic_0.png").isFile)
    }

    private class TileResult(
        val name: String,
        val base: TargetCalibration?,
        val tight: TargetCalibration?,
        val baseTile: BufferedImage,
        val tightTile: BufferedImage,
    )

    private fun processOne(file: File): TileResult {
        val src = ImageIO.read(file) ?: error("could not decode ${file.name}")
        val work = squareDownscale(src, workSize)
        val gray = toGray(work)
        val base = calibrateFromGrayscale(gray, work.width, work.height)
        val tight = calibrateBlackRing(gray, work.width, work.height, guideRadiusPx)
        return TileResult(
            name = file.name,
            base = base,
            tight = tight,
            baseTile = annotate(work, base, file.name, drawGuide = false),
            tightTile = annotate(work, tight, file.name, drawGuide = true),
        )
    }

    private fun writeMosaics(outDir: File, prefix: String, tiles: List<BufferedImage>) {
        tiles.chunked(cols * rows).forEachIndexed { i, chunk ->
            val grid = mosaic(chunk, cols, rows)
            val f = File(outDir, "${prefix}_$i.png")
            ImageIO.write(grid, "png", f)
            println("[blackring] wrote ${f.absolutePath}")
        }
    }

    /** Centre-crop to a square, then scale to [size]x[size]. */
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
        drawGuide: Boolean,
    ): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, null)
        val stroke = maxOf(2f, img.width / 320f)
        if (drawGuide) {
            // Faint guide-circle prior used by the tightened selector.
            g.color = Color(0x55, 0xFF, 0xFF, 0x80)
            g.stroke = BasicStroke(maxOf(1f, stroke / 2f))
            val r = guideRadiusPx
            g.draw(Ellipse2D.Double((img.width / 2f - r).toDouble(), (img.height / 2f - r).toDouble(), (2 * r).toDouble(), (2 * r).toDouble()))
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
            val cx = calib.centerX
            val cy = calib.centerY
            g.drawLine((cx - arm).toInt(), cy.toInt(), (cx + arm).toInt(), cy.toInt())
            g.drawLine(cx.toInt(), (cy - arm).toInt(), cx.toInt(), (cy + arm).toInt())
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
