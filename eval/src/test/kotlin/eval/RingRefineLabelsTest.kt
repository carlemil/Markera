package eval

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import se.kjellstrand.markera.vision.CentreEstimate
import se.kjellstrand.markera.vision.CentreMethod
import se.kjellstrand.markera.vision.FittedEllipse
import se.kjellstrand.markera.vision.TargetPlane
import se.kjellstrand.markera.vision.lumaFromArgb
import se.kjellstrand.markera.vision.refineGeometry
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The refinement on the hand-labelled ring/centre photos (`/admin/ring-labels`): each row of
 * `labels.tsv` holds the app's stored geometry and the corrected one, photos in `img/<id>.jpg`.
 * Skipped when the folder is absent (it holds user photos, so it stays outside the repo).
 *
 *   ./gradlew :eval:test --tests eval.RingRefineLabelsTest -Dringlabels.dir=D:/ml/ringexp/data
 */
class RingRefineLabelsTest {

    private val dir = File(System.getProperty("ringlabels.dir", "D:/ml/ringexp/data"))

    private class Geometry(val centre: CentreEstimate, val ring: FittedEllipse)

    private fun geometry(v: List<Double>) = Geometry(
        CentreEstimate(v[0].toFloat(), v[1].toFloat(), CentreMethod.LINE_INTERSECTION),
        FittedEllipse(v[2].toFloat(), v[3].toFloat(), v[4].toFloat(), v[5].toFloat(), v[6].toFloat()),
    )

    /** Centre distance and the largest 6/7-ring gap over 360 rays, both in the truth's mm. */
    private fun error(est: Geometry, truth: Geometry): Pair<Double, Double> {
        val plane = TargetPlane.of(truth.centre, truth.ring)!!
        val (cu, cv) = plane.toMm(est.centre.x.toDouble(), est.centre.y.toDouble())
        val estPlane = TargetPlane.of(est.centre, est.ring)!!
        var worst = 0.0
        for (k in 0 until 360) {
            val t = k * PI / 180
            val (x, y) = plane.toImage(100 * cos(t), 100 * sin(t))
            val (u, v) = estPlane.toMm(x, y)
            worst = max(worst, kotlin.math.abs(hypot(u, v) - 100))
        }
        return hypot(cu, cv) to worst
    }

    @Test
    fun `refined geometry matches the hand labels`() {
        val tsv = File(dir, "labels.tsv")
        assumeTrue("no ring labels at $dir", tsv.isFile)
        val before = mutableListOf<Double>()
        val after = mutableListOf<Double>()
        val ringAfter = mutableListOf<Double>()
        for (line in tsv.readLines().filter { it.isNotBlank() }) {
            val f = line.split('\t')
            val id = f[0]
            val nums = f.drop(3).map { it.toDouble() }
            val detected = geometry(nums.take(7))
            val truth = geometry(nums.drop(7))
            val img = ImageIO.read(File(dir, "img/$id.jpg")) ?: continue
            assertTrue("$id: photo ${img.width} vs label ${f[1]}", img.width == f[1].toInt())
            val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
            val started = System.nanoTime()
            val refined = refineGeometry(lumaFromArgb(argb), img.width, img.height, detected.centre, detected.ring)
            val ms = (System.nanoTime() - started) / 1_000_000
            val (c0, r0) = error(detected, truth)
            val out = refined?.let { Geometry(it.centre, it.ring) } ?: detected
            val (c1, r1) = error(out, truth)
            println(
                "[ringlabels] $id: centre %.2f -> %.2f mm, ring max %.2f -> %.2f mm, %s, %d ms".format(
                    c0, c1, r0, r1, refined?.let { "points ${it.points} inliers %.2f".format(it.inlierShare) } ?: "kept",
                    ms,
                ),
            )
            before += c0
            after += c1
            ringAfter += r1
            assertTrue("$id: refinement refused", refined != null)
            assertTrue("$id: centre still %.2f mm off".format(c1), c1 < 0.6)
            assertTrue("$id: ring still %.2f mm off".format(r1), r1 < 2.0)
        }
        assumeTrue("no labelled photos", after.isNotEmpty())
        println("[ringlabels] mean centre error %.2f -> %.2f mm over %d photos".format(before.average(), after.average(), after.size))
        println("[ringlabels] mean ring max %.2f mm".format(ringAfter.average()))
        assertTrue("mean centre error ${after.average()}", after.average() < 0.35)
        // With the edge-only ring refit 0.66 mm on the 27 labels, 0.82 mm without it.
        assertTrue("mean ring max ${ringAfter.average()}", ringAfter.average() < 0.75)
    }
}
