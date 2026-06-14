package eval

import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.vision.EdgeFitResult
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.calibrateBlackRing
import se.kjellstrand.markera.vision.calibrateBlackRingEdge
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
 * Compares the moment-based black-ring fit (tight, guide-circle prior) against
 * the new edge-based fit (radial rim sampling + direct ellipse fit) over the
 * dataset, and writes annotated 3x3 mosaics for each. The edge mosaic also
 * draws the sampled rim points so it can be checked that the ellipse sits on
 * the actual black/white boundary. Mirrors the app's square capture.
 *
 *   ./gradlew :eval:test --tests "eval.BlackRingMosaicTest" \
 *       -Dmosaic.images="D:/ml/holes/newDataset"
 */
class BlackRingMosaicTest {

    private val imagesDir = System.getProperty("mosaic.images", "D:/ml/holes/dataset/images")
    private val workSize = 1024
    private val guideRadiusPx = 0.35f * workSize
    private val cols = 3
    private val rows = 3

    @Test
    fun `compares moment fit vs edge fit`() {
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
        writeMosaics(outDir, "black_ring_tight_mosaic", results.map { it.tightTile })
        writeMosaics(outDir, "black_ring_edge_mosaic", results.map { it.edgeTile })

        val tightFit = results.count { it.tight != null }
        val edgeFit = results.count { it.edge.calib != null }
        val rmsValues = results.mapNotNull { if (it.edge.calib != null) it.edge.rms else null }.sorted()
        val medianRms = if (rmsValues.isEmpty()) 0.0 else rmsValues[rmsValues.size / 2]

        println("[blackring] === moment (tight) vs edge ===")
        println("[blackring] tight fits: $tightFit/${results.size}")
        println("[blackring] edge fits : $edgeFit/${results.size}  median rim RMS=%.2f px".format(medianRms))
        results.forEach { r ->
            val e = r.edge.calib
            if (e != null) {
                println("[blackring]   ${r.name}: a=%.0f b=%.0f rms=%.2f pts=%d".format(e.semiMajorPx, e.semiMinorPx, r.edge.rms, r.edge.points.size))
            } else {
                println("[blackring]   ${r.name}: edge NO FIT (pts=${r.edge.points.size})")
            }
        }
        assertTrue("no mosaic written", File(outDir, "black_ring_edge_mosaic_0.png").isFile)
    }

    private class TileResult(
        val name: String,
        val tight: TargetCalibration?,
        val edge: EdgeFitResult,
        val tightTile: BufferedImage,
        val edgeTile: BufferedImage,
    )

    private fun processOne(file: File): TileResult {
        val src = ImageIO.read(file) ?: error("could not decode ${file.name}")
        val work = squareDownscale(src, workSize)
        val gray = toGray(work)
        val tight = calibrateBlackRing(gray, work.width, work.height, guideRadiusPx)
        val edge = calibrateBlackRingEdge(gray, work.width, work.height, guideRadiusPx)
        return TileResult(
            name = file.name,
            tight = tight,
            edge = edge,
            tightTile = annotate(work, tight, file.name, drawGuide = true),
            edgeTile = annotateEdge(work, edge, file.name),
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

    private fun drawEllipse(g: java.awt.Graphics2D, c: TargetCalibration, stroke: Float) {
        g.color = Color(0x00, 0xE6, 0x76)
        g.stroke = BasicStroke(stroke)
        val tx = AffineTransform()
        tx.translate(c.centerX.toDouble(), c.centerY.toDouble())
        tx.rotate(c.rotationRad.toDouble())
        g.draw(
            tx.createTransformedShape(
                Ellipse2D.Double(
                    -c.semiMajorPx.toDouble(),
                    -c.semiMinorPx.toDouble(),
                    2.0 * c.semiMajorPx,
                    2.0 * c.semiMinorPx,
                ),
            ),
        )
        g.color = Color(0xFF, 0x17, 0x44)
        val arm = 18f * stroke
        g.drawLine((c.centerX - arm).toInt(), c.centerY.toInt(), (c.centerX + arm).toInt(), c.centerY.toInt())
        g.drawLine(c.centerX.toInt(), (c.centerY - arm).toInt(), c.centerX.toInt(), (c.centerY + arm).toInt())
    }

    private fun annotate(img: BufferedImage, calib: TargetCalibration?, name: String, drawGuide: Boolean): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, null)
        val stroke = maxOf(2f, img.width / 320f)
        if (drawGuide) {
            g.color = Color(0x55, 0xFF, 0xFF, 0x80)
            g.stroke = BasicStroke(maxOf(1f, stroke / 2f))
            val r = guideRadiusPx
            g.draw(Ellipse2D.Double((img.width / 2f - r).toDouble(), (img.height / 2f - r).toDouble(), (2 * r).toDouble(), (2 * r).toDouble()))
        }
        if (calib != null) {
            drawEllipse(g, calib, stroke)
            g.color = Color.WHITE
            g.drawString("%s  conf=%.2f".format(name, calib.confidence), 12, img.height - 14)
        } else {
            g.color = Color(0xFF, 0x17, 0x44)
            g.drawString("$name  NO FIT", 12, img.height - 14)
        }
        g.dispose()
        return out
    }

    private fun annotateEdge(img: BufferedImage, edge: EdgeFitResult, name: String): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, null)
        val stroke = maxOf(2f, img.width / 320f)
        // Seed centre + seed-radius circle and the scan window (diagnostic).
        g.stroke = BasicStroke(maxOf(1f, stroke / 2f))
        g.color = Color(0x55, 0x99, 0xFF, 0x70)
        for (rf in doubleArrayOf(0.78, 1.0, 1.22)) {
            val rr = edge.seedR * rf
            g.draw(Ellipse2D.Double(edge.seedCx - rr, edge.seedCy - rr, 2 * rr, 2 * rr))
        }
        // Sampled rim points (orange) — should sit on the black/white edge.
        g.color = Color(0xFF, 0xA0, 0x00)
        val d = maxOf(3f, img.width / 220f)
        for (p in edge.points) {
            g.fillOval((p[0] - d / 2).toInt(), (p[1] - d / 2).toInt(), d.toInt(), d.toInt())
        }
        if (edge.calib != null) {
            drawEllipse(g, edge.calib, stroke)
            g.color = Color.WHITE
            g.drawString("%s  rms=%.1fpx".format(name, edge.rms), 12, img.height - 14)
        } else {
            g.color = Color(0xFF, 0x17, 0x44)
            g.drawString("$name  edge NO FIT", 12, img.height - 14)
        }
        g.dispose()
        return out
    }
}
