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
        assertEquals(listOf(PositiveLabel(0, ExportBox(0.1, 0.1, 0.02, 0.04))), s.labels)
    }

    @Test
    fun anInnerTenIsAHoleLikeAnyOtherAndAHandPlacedHoleFallsBackToACaliberSizedBox() {
        val inner = Hole(500.0, 250.0, 10, true, 1.0)
        val s = classifySeries(series(inner, Hole(null, null, 8, false, null)))!!
        // 9.02 mm * 200 px / 100 mm = 18.04 px; a typed hole has no position and so no label.
        assertEquals(1, s.labels.size)
        assertEquals(0, s.labels.single().classIndex)
        assertEquals(0.01804, s.labels.single().box.w, 1e-9)
        assertEquals(listOf("hole"), TRAINING_CLASSES)
    }

    @Test
    fun aDeletedDetectionIsNotExported() {
        val s = classifySeries(series(detected(100.0, 50.0, deleted = true, conf = 0.3), detected(300.0, 50.0)))!!
        assertEquals(listOf(ExportBox(0.3, 0.1, 0.02, 0.04)), s.labels.map { it.box })
    }

    @Test
    fun aDraggedDetectionIsOnlyALabelWhereItNowSits() {
        // Far (100 px with a 20 px box) or near (5 px): only the last position, never the detector's.
        val far = classifySeries(series(detected(300.0, 50.0, atX = 200.0, atY = 50.0)))!!
        assertEquals(listOf(ExportBox(0.3, 0.1, 0.02, 0.04)), far.labels.map { it.box })
        val near = classifySeries(series(detected(205.0, 50.0, atX = 200.0, atY = 50.0)))!!
        assertEquals(listOf(ExportBox(0.205, 0.1, 0.02, 0.04)), near.labels.map { it.box })
    }

    @Test
    fun aDetectionScoredZeroByHandIsNotAHole() {
        val s = classifySeries(series(detected(100.0, 50.0).copy(ring = 0)))!!
        assertTrue(s.labels.isEmpty())
    }

    @Test
    fun anOldHoleWithoutABoxUsesTheCaliberSize() {
        val old = Hole(100.0, 50.0, 9, false, 10.0, 9, false, 100.0, 50.0)
        assertEquals(0.01804, classifySeries(series(old))!!.labels.single().box.w, 1e-9)
        // No geometry either: 3% of the frame width.
        assertEquals(0.03, classifySeries(series(old, geometry = null))!!.labels.single().box.w, 1e-9)
    }

    @Test
    fun aBoxAtTheEdgeIsClippedToThePhoto() {
        val s = classifySeries(series(detected(5.0, 250.0, w = 20.0, h = 20.0)))!!
        assertEquals(ExportBox(0.0075, 0.5, 0.015, 0.04), s.labels.single().box)
    }

    @Test
    fun aHoleWhollyOffTheFrameHasNoBoxRatherThanANegativeOne() {
        val s = classifySeries(
            series(detected(-50.0, 250.0), detected(1100.0, 250.0, deleted = true), detected(100.0, 600.0).copy(ring = 0)),
        )!!
        assertTrue(s.labels.isEmpty(), s.labels.toString())
    }

    /** Issue #41: rows stored before validation may still hold such boxes; they are reported, never clipped. */
    @Test
    fun aStoredBoxThatCannotBeOneFallsBackToTheCaliberBoxAndIsReported() {
        val s = classifySeries(
            series(
                detected(100.0, 50.0, w = 5000.0, h = 20.0),               // wider than the 1000 px frame
                detected(300.0, 50.0, w = -20.0, h = 20.0, conf = 3.0, deleted = true), // negative, conf past 1
                detected(500.0, 50.0, w = 20.0, h = 600.0),                 // taller than the 500 px frame
                detected(700.0, 50.0, conf = -0.5),                        // only the confidence is bad
            ),
        )!!
        // 9 mm at 2 px/mm = 18.04 px: the caliber box, not the full frame.
        assertEquals(3, s.labels.size)
        assertEquals(0.01804, s.labels[0].box.w, 1e-9)
        assertEquals(0.03608, s.labels[1].box.h, 1e-9)
        assertEquals(ExportBox(0.7, 0.1, 0.02, 0.04), s.labels[2].box)
        assertEquals(4, s.rejected.size, s.rejected.toString())
        assertTrue(s.rejected.all { it.startsWith("hole ") }, s.rejected.toString())
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
            setOf("images/7.jpg", "labels/7.txt", "data.yaml"),
            entries.keys,
        )
        assertEquals(listOf<Byte>(-1, -40, 1, 2, 3), entries.getValue("images/7.jpg").toList())
        assertEquals(
            "0 0.100000 0.100000 0.020000 0.040000\n0 0.300000 0.100000 0.020000 0.040000\n",
            String(entries.getValue("labels/7.txt")),
        )
        val yaml = String(entries.getValue("data.yaml"))
        assertTrue("nc: 1\n" in yaml && "  0: hole\n" in yaml, yaml)
    }

    @Test
    fun theCvatZipHoldsThePhotosAndEllipsesInPixelsAndSkipsWhatHasNoPhoto() {
        val photo = File.createTempFile("markera-photo", ".jpg").also { it.deleteOnExit() }
        photo.writeBytes(byteArrayOf(-1, -40, 1, 2, 3))
        val withPhoto = series(
            detected(100.0, 50.0, ring = 9),
            detected(300.0, 50.0, atX = 200.0, atY = 50.0),
            detected(400.0, 100.0, deleted = true),
        )
        val without = withPhoto.copy(id = 8)
        val noFrame = withPhoto.copy(id = 9, imageWidth = null)

        val out = ByteArrayOutputStream()
        writeCvatZip(out, sequenceOf(withPhoto, without, noFrame)) { id -> photo.takeIf { id != 8L } }

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        assertEquals(setOf("images/7.jpg", "annotations.xml"), entries.keys)
        val xml = String(entries.getValue("annotations.xml"))
        assertTrue("<version>1.1</version>" in xml, xml)
        assertTrue("<name>hole</name>" in xml && "false_positive" !in xml, xml)
        assertTrue("<image id=\"0\" name=\"7.jpg\" width=\"1000\" height=\"500\">" in xml, xml)
        val ellipses = Regex("<ellipse label=\"([a-z_]+)\"[^>]* cx=\"([0-9.]+)\" cy=\"([0-9.]+)\" rx=\"([0-9.]+)\" ry=\"([0-9.]+)\"")
            .findAll(xml).map { it.groupValues.drop(1) }.toList()
        assertEquals(
            listOf(
                listOf("hole", "100.00", "50.00", "10.00", "10.00"),
                listOf("hole", "300.00", "50.00", "10.00", "10.00"),
            ),
            ellipses,
        )
        // Well-formed: a real XML parser reads it back.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(entries.getValue("annotations.xml")))
        assertEquals(2, doc.getElementsByTagName("ellipse").length)
    }

    @Test
    fun anEmptyCvatExportIsStillAValidFile() {
        val xml = cvatXml(emptyList())
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(0, doc.getElementsByTagName("image").length)
    }
}
