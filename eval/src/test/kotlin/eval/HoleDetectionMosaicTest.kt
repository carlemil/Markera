package eval

import org.junit.Assert.assertTrue
import org.junit.Test
import se.kjellstrand.markera.vision.Detection
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.vision.TARGET_CARD_WIDTH_MM
import se.kjellstrand.markera.vision.TargetCalibration
import se.kjellstrand.markera.vision.computeHitScores
import se.kjellstrand.markera.vision.filterByConfidence
import se.kjellstrand.markera.vision.mapToImageSpace
import se.kjellstrand.markera.vision.nonMaxSuppression
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Runs the real hole-detection pipeline (preprocess -> ONNX -> filter -> NMS
 * -> map-to-image -> ring calibration -> hit scoring) over a random 3x3
 * sample of training images and writes an annotated mosaic for eyeballing how
 * well holes, ring positions, and per-hole scores are recovered.
 *
 * Override defaults with -D flags, e.g.:
 *   ./gradlew :eval:test -Dmosaic.seed=7 -Dmosaic.images="D:/ml/holes/dataset/images/val"
 */
class HoleDetectionMosaicTest {

    private val imagesDir = System.getProperty(
        "mosaic.images", "D:/ml/holes/dataset/images/train",
    )
    private val modelPath = System.getProperty(
        "mosaic.model",
        "D:/source/Markera/composeApp/src/androidMain/assets/best.onnx",
    )
    private val seed = System.getProperty("mosaic.seed")?.toLong() ?: System.nanoTime()

    // Mirror the device call-site constants from MarkeraScreen.kt.
    private val inputSize = 1280
    private val confidenceThreshold = 0.35f
    private val iouThreshold = 0.45f
    private val cols = 3
    private val rows = 3

    @Test
    fun `marks holes on a random 3x3 sample and writes a mosaic`() {
        val dir = File(imagesDir)
        assertTrue("images dir not found: $imagesDir", dir.isDirectory)
        val model = File(modelPath)
        assertTrue("model not found: $modelPath", model.isFile)

        val all = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "bmp")
        }?.sortedBy { it.name } ?: emptyList()
        assertTrue("no images in $imagesDir", all.isNotEmpty())

        val chosen = all.shuffled(Random(seed)).take(cols * rows)
        println("[mosaic] seed=$seed  picked ${chosen.size} of ${all.size} images")

        val tiles = OnnxHoleDetector(model.absolutePath, inputSize).use { detector ->
            chosen.map { file -> processOne(file, detector) }
        }

        val grid = mosaic(tiles, cols, rows)
        val outDir = File("build/mosaic").apply { mkdirs() }
        val outFile = File(outDir, "hole_detection_mosaic.png")
        ImageIO.write(grid, "png", outFile)

        println("[mosaic] wrote ${outFile.absolutePath} (${grid.width}x${grid.height})")
        assertTrue("mosaic was not written", outFile.isFile && outFile.length() > 0)
    }

    private fun processOne(file: File, detector: OnnxHoleDetector): java.awt.image.BufferedImage {
        val img = ImageIO.read(file)
            ?: throw IllegalStateException("ImageIO could not decode ${file.name}")

        val input = toModelInput(img, inputSize)
        val raws = detector.detect(input)
        val kept = nonMaxSuppression(filterByConfidence(raws, confidenceThreshold), iouThreshold)
        val detections = mapToImageSpace(kept, inputSize, img.width, img.height)

        val calibration = calibrate(img)
        val perHole = detections.map { d -> scoreOne(d, img.width, img.height, calibration) }

        val total = perHole.sumOf { if (it.isInnerTen) 10 else it.ring }
        println(
            "[mosaic]   ${file.name}: ${img.width}x${img.height}  raw=${raws.size} " +
                "kept=${detections.size}  total=$total  " +
                (calibration?.let { "ringConf=%.2f".format(it.confidence) } ?: "no-ring-fit"),
        )

        return annotate(img, detections, perHole, calibration, file.name)
    }

    /** Per-detection ring, using the ellipse calibration when available. */
    private fun scoreOne(
        d: Detection,
        w: Int,
        h: Int,
        calibration: TargetCalibration?,
    ): HitScore = if (calibration != null) {
        computeHitScores(listOf(d), calibration).single()
    } else {
        computeHitScores(
            listOf(d),
            centerX = w / 2f,
            centerY = h / 2f,
            mmPerPx = TARGET_CARD_WIDTH_MM / w.toDouble(),
        ).single()
    }
}
