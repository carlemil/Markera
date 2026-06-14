package eval

import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.vision.RansacRingResult
import se.kjellstrand.markera.vision.detectBlackRingRansac
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
 * Runs the RANSAC black-ring detector over the dataset and writes ONE full-size
 * annotated overlay per image (not a mosaic), so the fit can be judged at real
 * resolution. Each overlay shows the gradient rim candidates (faint), the RANSAC
 * inliers (orange) and the fitted ellipse (green) + centre.
 *
 *   ./gradlew :eval:test --tests "eval.BlackRingRansacTest" \
 *       -Dmosaic.images="D:/ml/holes/newDataset"
 */
class BlackRingRansacTest {

    private val imagesDir = System.getProperty("mosaic.images", "D:/ml/holes/dataset/images")
    private val workSize = 1536

    @Test
    fun `detect black ring via ransac and write full-res overlays`() {
        val dir = File(imagesDir)
        assertTrue("images dir not found: $imagesDir", dir.isDirectory)
        val all = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp") }
            .sortedBy { it.name }
            .toList()
        assertTrue("no images in $imagesDir", all.isNotEmpty())

        val outDir = File("build/blackring_fullres").apply { deleteRecursively(); mkdirs() }
        var fits = 0
        for (file in all) {
            val src = ImageIO.read(file) ?: continue
            val work = squareDownscale(src, workSize)
            val gray = toGray(work)
            val r = detectBlackRingRansac(gray, workSize, workSize, 0.35f * workSize)
            ImageIO.write(annotate(work, r, file.name), "png", File(outDir, "${file.nameWithoutExtension}.png"))
            if (r.ellipse != null) {
                fits++
                println("[ransac] ${file.name}: fit a=%.0f b=%.0f cov=%.2f inliers=%d".format(r.ellipse!!.semiMajor, r.ellipse!!.semiMinor, r.coverage, r.inliers.size))
            } else {
                println("[ransac] ${file.name}: NO FIT cov=%.2f candidates=%d".format(r.coverage, r.candidates.size))
            }
        }
        println("[ransac] fits $fits/${all.size}; overlays in ${outDir.absolutePath}")
        assertTrue("no overlays written", (outDir.listFiles()?.size ?: 0) > 0)
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

    private fun annotate(img: BufferedImage, r: RansacRingResult, name: String): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(img, 0, 0, null)
        // faint seed circle
        g.stroke = BasicStroke(1.5f)
        g.color = Color(0x55, 0x99, 0xFF, 0x60)
        g.draw(Ellipse2D.Double(r.seedCx - r.seedR, r.seedCy - r.seedR, 2 * r.seedR, 2 * r.seedR))
        // RANSAC inliers (orange)
        g.color = Color(0xFF, 0xA0, 0x00)
        for (p in r.inliers) g.fillRect(p.x.toInt() - 1, p.y.toInt() - 1, 3, 3)
        val e = r.ellipse
        if (e != null) {
            g.color = Color(0x00, 0xE6, 0x76)
            g.stroke = BasicStroke(4f)
            val tx = AffineTransform()
            tx.translate(e.cx.toDouble(), e.cy.toDouble())
            tx.rotate(e.rotationRad.toDouble())
            g.draw(tx.createTransformedShape(Ellipse2D.Double(-e.semiMajor.toDouble(), -e.semiMinor.toDouble(), 2.0 * e.semiMajor, 2.0 * e.semiMinor)))
            g.color = Color(0xFF, 0x17, 0x44)
            val arm = 26f
            g.drawLine((e.cx - arm).toInt(), e.cy.toInt(), (e.cx + arm).toInt(), e.cy.toInt())
            g.drawLine(e.cx.toInt(), (e.cy - arm).toInt(), e.cx.toInt(), (e.cy + arm).toInt())
            g.color = Color.WHITE
            g.drawString("%s  cov=%.2f".format(name, r.coverage), 16, img.height - 18)
        } else {
            g.color = Color(0xFF, 0x17, 0x44)
            g.drawString("$name  NO FIT  cov=%.2f".format(r.coverage), 16, img.height - 18)
        }
        g.dispose()
        return out
    }
}
