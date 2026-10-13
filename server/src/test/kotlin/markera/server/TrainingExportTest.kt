package markera.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingExportTest {

    // A 1000 x 500 frame; the 6/7 ring is 200 px across its semi-major axis, so 1 mm = 2 px and a 9 mm hole is 18 px.
    private val geometry = Geometry(500.0, 250.0, 500.0, 250.0, 200.0, 190.0, 0.0)

    private fun series(vararg holes: Hole, geometry: Geometry? = this.geometry) = Series(
        id = 7, timestamp = "2026-09-06T12:34:56Z", caliber = "9mm", holes = holes.toList(),
        imageWidth = 1000, imageHeight = 500, geometry = geometry,
    )

    private fun detected(
        x: Double, y: Double, ring: Int = 9, atX: Double = x, atY: Double = y,
        w: Double? = 20.0, h: Double? = 20.0, conf: Double? = 0.8, deleted: Boolean = false,
    ) = Hole(x, y, ring, false, 10.0, ring, false, atX, atY, w, h, conf, deleted)

    @Test
    fun anUntouchedDetectionIsALabelBoxedByTheDetectorsOwnBox() {
        val s = classifySeries(series(detected(100.0, 50.0, ring = 9)))!!
        assertEquals(listOf(PositiveLabel(9, ExportBox(0.1, 0.1, 0.02, 0.04))), s.labels)
        assertTrue(s.negatives.isEmpty() && s.nudges.isEmpty())
    }

    @Test
    fun anInnerTenIsTheLastClassAndAHandPlacedHoleFallsBackToACaliberSizedBox() {
        val inner = Hole(500.0, 250.0, 10, true, 1.0)
        val s = classifySeries(series(inner, Hole(null, null, 8, false, null)))!!
        // 9.02 mm * 200 px / 100 mm = 18.04 px; a typed hole has no position and so no label.
        assertEquals(1, s.labels.size)
        assertEquals(11, s.labels.single().classIndex)
        assertEquals(0.01804, s.labels.single().box.w, 1e-9)
        assertEquals("Hole X", TRAINING_CLASSES[11])
    }

    @Test
    fun aDeletedDetectionIsAFalsePositiveAtTheDetectorsPositionNotALabel() {
        val s = classifySeries(series(detected(100.0, 50.0, deleted = true, conf = 0.3)))!!
        assertTrue(s.labels.isEmpty())
        assertEquals(listOf(NegativeBox(ExportBox(0.1, 0.1, 0.02, 0.04), 0.3, "deleted")), s.negatives)
    }

    @Test
    fun aDetectionDraggedMoreThanADiameterIsBothAFalsePositiveAndALabel() {
        // 20 px box dragged 100 px: the old spot is a false positive, the new one a hole the model missed.
        val s = classifySeries(series(detected(300.0, 50.0, atX = 200.0, atY = 50.0)))!!
        assertEquals(listOf(ExportBox(0.3, 0.1, 0.02, 0.04)), s.labels.map { it.box })
        assertEquals(listOf(NegativeBox(ExportBox(0.2, 0.1, 0.02, 0.04), 0.8, "moved")), s.negatives)
        assertTrue(s.nudges.isEmpty())
    }

    @Test
    fun aSmallDragIsANudgeNotAFalsePositive() {
        val s = classifySeries(series(detected(205.0, 50.0, atX = 200.0, atY = 50.0)))!!
        assertTrue(s.negatives.isEmpty())
        assertEquals(1, s.labels.size)
        assertEquals(listOf(NudgedBox(ExportBox(0.2, 0.1, 0.02, 0.04), s.labels.single().box, 0.8)), s.nudges)
    }

    @Test
    fun aDetectionScoredZeroByHandIsAFalsePositive() {
        val s = classifySeries(series(detected(100.0, 50.0).copy(ring = 0)))!!
        assertTrue(s.labels.isEmpty())
        assertEquals(listOf("zeroed"), s.negatives.map { it.reason })
    }

    @Test
    fun anOldSeriesWithoutABoxUsesTheCaliberSizeAndNoConfidence() {
        val old = Hole(100.0, 50.0, 9, false, 10.0, 9, false, 100.0, 50.0, deleted = true)
        val s = classifySeries(series(old))!!
        assertNull(s.negatives.single().conf)
        assertEquals(0.01804, s.negatives.single().box.w, 1e-9)
        // No geometry either: 3% of the frame width.
        val bare = classifySeries(series(old, geometry = null))!!
        assertEquals(0.03, bare.negatives.single().box.w, 1e-9)
    }

    @Test
    fun aBoxAtTheEdgeIsClippedToThePhoto() {
        val s = classifySeries(series(detected(5.0, 250.0, w = 20.0, h = 20.0)))!!
        assertEquals(ExportBox(0.0075, 0.5, 0.015, 0.04), s.labels.single().box)
    }

    @Test
    fun aSeriesWithoutAFrameSizeCannotBePlaced() {
        assertNull(classifySeries(series(detected(1.0, 1.0)).copy(imageWidth = null)))
    }

    @Test
    fun theZipHoldsImagesLabelsAndTheSidecarsAndSkipsWhatHasNoPhoto() {
        val photo = File.createTempFile("markera-photo", ".jpg").also { it.deleteOnExit() }
        photo.writeBytes(byteArrayOf(-1, -40, 1, 2, 3))
        val withPhoto = series(
            detected(100.0, 50.0, ring = 9),
            detected(300.0, 50.0, atX = 200.0, atY = 50.0),
            detected(400.0, 100.0, deleted = true),
        )
        val without = withPhoto.copy(id = 8)

        val out = ByteArrayOutputStream()
        writeTrainingZip(out, sequenceOf(withPhoto, without)) { id -> photo.takeIf { id == 7L } }

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(
            setOf("images/7.jpg", "labels/7.txt", "negatives.csv", "nudges.csv", "data.yaml"),
            entries.keys,
        )
        assertEquals(listOf<Byte>(-1, -40, 1, 2, 3), entries.getValue("images/7.jpg").toList())
        assertEquals(
            "9 0.100000 0.100000 0.020000 0.040000\n9 0.300000 0.100000 0.020000 0.040000\n",
            String(entries.getValue("labels/7.txt")),
        )
        assertEquals(
            "image;cx;cy;w;h;conf;reason\n" +
                "images/7.jpg;0.200000;0.100000;0.020000;0.040000;0.800000;moved\n" +
                "images/7.jpg;0.400000;0.200000;0.020000;0.040000;0.800000;deleted\n",
            String(entries.getValue("negatives.csv")),
        )
        assertEquals(NUDGES_HEADER + "\n", String(entries.getValue("nudges.csv")))
        val yaml = String(entries.getValue("data.yaml"))
        assertTrue("nc: 12" in yaml && "  0: Hole 0\n" in yaml && "  11: Hole X\n" in yaml, yaml)
    }
}
