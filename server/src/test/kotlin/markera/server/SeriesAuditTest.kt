package markera.server

import java.io.File
import java.sql.DriverManager
import kotlin.test.*

class SeriesAuditTest {
    private fun fixture(block: (Db, File, File, Long, Long) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("markera-audit-").toFile()
        val path = File(dir, "db.sqlite")
        val images = File(dir, "images").also { it.mkdir() }
        try {
            Db(path.path).use { db ->
                val owner = db.upsertUser("dev", "owner", "<Owner>")
                val id = db.insertSeries(owner, request.timestamp, request.caliber, request.holes, clientId = "first")
                block(db, path, images, owner, id)
            }
        } finally { dir.deleteRecursively() }
    }

    private val detected = Hole(1.0, 2.0, 9, false, 3.0, 9, false, 1.0, 2.0, 4.0, 4.0, .9)
    private val request = SeriesRequest("2026-10-20T00:00:00Z", "9mm", listOf(detected, detected.copy(x = 4.0, detectedX = 4.0)))
    private fun corrections(db: Db, id: Long) = db.auditEvents(id).filterNot { it.baseline }

    @Test fun `creation retry normalized noop and hole order do not become edits`() = fixture { db, _, _, owner, id ->
        assertTrue(corrections(db, id).isEmpty())
        assertEquals(id, db.insertSeries(owner, "other", "22lr", emptyList(), clientId = "first"))
        db.replaceSeries(id, request.copy(caliber = " 9mm ", tag = "  ", holes = request.holes.reversed()), AuditActor.app(owner))
        assertTrue(corrections(db, id).isEmpty())
        assertEquals(1, db.auditEvents(id).size)
    }

    @Test fun `every persisted metadata geometry and hole category records before after with owner and actor`() = fixture { db, _, _, owner, id ->
        val geometry = Geometry(1.0, 2.0, 3.0, 4.0, 10.0, 9.0, .1)
        val changes = listOf(
            request.copy(timestamp = "2026-10-21T00:00:00Z"), request.copy(caliber = "22lr"), request.copy(tag = "training"),
            request.copy(geometry = geometry), request.copy(geometry = geometry.copy(centreX = 2.0)),
            request.copy(geometry = geometry.copy(centreY = 3.0)), request.copy(geometry = geometry.copy(ringCx = 4.0)),
            request.copy(geometry = geometry.copy(ringCy = 5.0)), request.copy(geometry = geometry.copy(ringSemiMajor = 11.0)),
            request.copy(geometry = geometry.copy(ringSemiMinor = 8.0)), request.copy(geometry = geometry.copy(ringRotationRad = .2)),
            request,
        ) + listOf(
            detected.copy(ring = 10, innerTen = true), detected.copy(x = 2.0), detected.copy(y = 3.0),
            detected.copy(distanceMm = 4.0), detected.copy(detectedRing = 8), detected.copy(detectedInnerTen = null),
            detected.copy(detectedX = 2.0), detected.copy(detectedY = 3.0), detected.copy(detectedW = 5.0),
            detected.copy(detectedH = 5.0), detected.copy(detectedConf = .8), detected.copy(deleted = true),
            detected.copy(x = null, y = null, distanceMm = null, detectedRing = null, detectedX = null, detectedY = null),
        ).map { request.copy(holes = listOf(it, request.holes.last())) }
        changes.forEachIndexed { n, req ->
            val actor = if (n % 2 == 0) AuditActor.app(owner) else AuditActor.Admin
            db.replaceSeries(id, req, actor)
            val e = corrections(db, id).first()
            assertEquals(actor, e.actor)
            assertEquals(owner, e.ownerId)
            assertNotEquals(e.before, e.after)
            assertTrue(e.reasons.isNotEmpty())
        }
        assertEquals(changes.size, corrections(db, id).size)
        assertTrue(db.auditEvents(id).all { java.time.Instant.parse(it.at) != null })
    }

    @Test fun `duplicates preserved deleted holes retained and reversal retains history`() = fixture { db, _, _, owner, id ->
        db.replaceSeries(id, request.copy(holes = listOf(detected, detected)), AuditActor.app(owner))
        val firstCount = corrections(db, id).size
        db.replaceSeries(id, request.copy(holes = listOf(detected, detected)), AuditActor.app(owner))
        assertEquals(firstCount, corrections(db, id).size)
        db.replaceSeries(id, request.copy(holes = listOf(detected.copy(deleted = true), request.holes.last())))
        val removedCount = corrections(db, id).size
        db.replaceSeries(id, request.copy(holes = listOf(request.holes.last())))
        assertEquals(removedCount, corrections(db, id).size)
        db.replaceSeries(id, request)
        assertTrue(corrections(db, id).size > removedCount)
    }

    @Test fun `delete restore repeated operations and failed persistence are atomic`() = fixture { db, path, _, owner, id ->
        db.deleteSeries(id, AuditActor.app(owner)); db.deleteSeries(id, AuditActor.app(owner))
        assertEquals(1, corrections(db, id).size)
        db.restoreSeries(id); db.restoreSeries(id)
        assertEquals(2, corrections(db, id).size)
        DriverManager.getConnection("jdbc:sqlite:${path.path}").use { c -> c.createStatement().use {
            it.executeUpdate("CREATE TRIGGER fail_audit BEFORE INSERT ON series_audit BEGIN SELECT RAISE(FAIL, 'injected'); END")
        } }
        assertFails { db.replaceSeries(id, request.copy(tag = "lost")) }
        assertNull(db.getSeries(id)!!.tag)
        assertEquals(2, corrections(db, id).size)
    }

    @Test fun `photos baseline repeat replacement dimensions and failed DB retain consistent bytes`() = fixture { db, path, images, owner, id ->
        val actor = AuditActor.app(owner)
        val a = byteArrayOf(-1, -40, 1)
        val b = byteArrayOf(-1, -40, 2)
        db.storePhoto(images, id, a, 100, 200, actor)
        db.storePhoto(images, id, a, 100, 200, actor)
        assertTrue(corrections(db, id).isEmpty())
        db.storePhoto(images, id, b, 100, 200, actor)
        assertEquals(listOf("photo"), corrections(db, id).first().reasons)
        db.storePhoto(images, id, b, 300, 400, actor)
        assertEquals(listOf("dimensions"), corrections(db, id).first().reasons)
        db.storePhoto(images, id, b, null, 999, actor)
        assertEquals(2, corrections(db, id).size)
        DriverManager.getConnection("jdbc:sqlite:${path.path}").use { c -> c.createStatement().use {
            it.executeUpdate("CREATE TRIGGER fail_audit BEFORE INSERT ON series_audit BEGIN SELECT RAISE(FAIL, 'injected'); END")
        } }
        assertFails { db.storePhoto(images, id, a, 500, 600, actor) }
        assertContentEquals(b, imageFile(images, id).readBytes())
        assertEquals(300, db.getSeries(id)!!.imageWidth)
        assertEquals(2, corrections(db, id).size)
        assertFalse(File(images, "$id.journal").exists())
    }

    @Test fun `historical JPEG baseline is honest and interrupted file replacement recovers on reopen`() = fixture { db, path, images, owner, id ->
        val a = byteArrayOf(-1, -40, 1)
        imageFile(images, id).writeBytes(a)
        db.storePhoto(images, id, a, null, null, AuditActor.app(owner))
        assertTrue(corrections(db, id).isEmpty())
        assertTrue(db.auditEvents(id).any { it.operation == "historical photo baseline" })
        File(images, "$id.previous").writeBytes(a)
        File(images, "$id.journal").writeText("present")
        imageFile(images, id).writeBytes(byteArrayOf(-1, -40, 9))
        Db(path.path).use { reopened ->
            reopened.recoverPhotos(images)
            assertContentEquals(a, imageFile(images, id).readBytes())
            assertEquals(db.auditEvents(id), reopened.auditEvents(id))
        }
    }

    @Test fun `legacy migration does not invent audit events and account erase removes private audit values`() = fixture { db, path, images, owner, id ->
        DriverManager.getConnection("jdbc:sqlite:${path.path}").use { c -> c.createStatement().use {
            it.executeUpdate("DROP TABLE series_audit"); it.executeUpdate("DROP TABLE series_photo_baseline")
        } }
        Db(path.path).use { reopened ->
            assertTrue(reopened.auditEvents(id).isEmpty())
            reopened.replaceSeries(id, request.copy(tag = "private"), AuditActor.app(owner))
            reopened.storePhoto(images, id, byteArrayOf(-1, -40, 1), 10, 10, AuditActor.app(owner))
            reopened.deleteAccount(owner)
            assertTrue(reopened.auditEvents(id).isEmpty())
            DriverManager.getConnection("jdbc:sqlite:${path.path}").use { c -> c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM series_photo_baseline").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
            } }
        }
    }

    @Test fun `committed photo recovery keeps new bytes and abandoned first attachment disappears`() = fixture { db, _, images, owner, id ->
        val bytes = byteArrayOf(-1, -40, 1)
        db.storePhoto(images, id, bytes, 10, 20, AuditActor.app(owner))
        File(images, "$id.previous").writeBytes(byteArrayOf(-1, -40, 0))
        File(images, "$id.journal").writeText("present")
        db.recoverPhotos(images)
        assertContentEquals(bytes, imageFile(images, id).readBytes())
        assertFalse(File(images, "$id.previous").exists())
        val next = db.insertSeries(owner, request.timestamp, request.caliber, request.holes)
        imageFile(images, next).writeBytes(bytes)
        File(images, "$next.journal").writeText("absent")
        db.recoverPhotos(images)
        assertFalse(imageFile(images, next).exists())
        assertTrue(corrections(db, next).isEmpty())
    }

    @Test fun `photo filesystem failure has no successful event or metadata mutation`() = fixture { db, _, images, owner, id ->
        imageFile(images, id).mkdir()
        File(imageFile(images, id), "blocked").writeText("not a photo")
        assertFails { db.storePhoto(images, id, byteArrayOf(-1, -40, 1), 10, 20, AuditActor.app(owner)) }
        assertNull(db.getSeries(id)!!.imageWidth)
        assertTrue(corrections(db, id).isEmpty())
        assertEquals(1, db.auditEvents(id).size)
    }

    @Test fun `SQL review classifier agrees with shared signal classifier for nulls and deleted data`() = fixture { db, _, _, owner, _ ->
        val holes = listOf(detected, detected.copy(detectedInnerTen = null), detected.copy(x = null),
            detected.copy(detectedX = null, detectedY = null), detected.copy(y = null),
            detected.copy(ring = 10, innerTen = true), detected.copy(deleted = true),
            Hole(ring = 9, innerTen = false), Hole(x = 1.0, y = 2.0, ring = 8, innerTen = false))
        val cases = holes.map { h -> db.insertSeries(owner, request.timestamp, request.caliber, listOf(h)) to h }
        val matches = db.reviewPage(true, 0).map { it.series.id }.toSet()
        cases.forEach { (id, h) -> assertEquals(holeSignals(h).isNotEmpty(), id in matches, h.toString()) }
    }
}
