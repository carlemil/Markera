package markera.server

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Photo files on the server: the startup sweep (#23), the DB lock during uploads (#24) and recovery (#40). */
class PhotoStorageTest {
    private val request = SeriesRequest("2026-10-20T00:00:00Z", "9mm", listOf(Hole(1.0, 2.0, 9, false)))

    private fun fixture(block: (Db, File, File, Long, Long) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("markera-photos-").toFile()
        val path = File(dir, "db.sqlite")
        val images = File(dir, "images").also { it.mkdir() }
        try {
            Db(path.path).use { db ->
                val owner = db.upsertUser("dev", "owner", "Owner")
                val id = db.insertSeries(owner, request.timestamp, request.caliber, request.holes)
                block(db, path, images, owner, id)
            }
        } finally { dir.deleteRecursively() }
    }

    private fun jpeg(n: Int) = byteArrayOf(-1, -40, n.toByte())

    @Test fun `startup sweeps photos of erased series and stray recovery files but keeps live and soft-deleted photos`() =
        fixture { db, _, images, owner, live ->
            val softDeleted = db.insertSeries(owner, request.timestamp, request.caliber, request.holes)
            db.deleteSeries(softDeleted, AuditActor.app(owner))
            imageFile(images, live).writeBytes(jpeg(1))
            imageFile(images, softDeleted).writeBytes(jpeg(2))
            // Left behind by an account erasure whose file delete failed: no series row any more.
            val gone = 9_999L
            imageFile(images, gone).writeBytes(jpeg(3))
            File(images, "$gone.previous").writeBytes(jpeg(4))
            // A backup whose journal is already gone (crash after the journal delete): never needed again.
            File(images, "$live.previous").writeBytes(jpeg(5))
            // An upload's temp file from a crash mid-write.
            File(images, "upload-123.tmp").writeBytes(jpeg(6))
            // Not ours: left alone.
            File(images, "notes.txt").writeText("keep")
            File(images, "123456789012345678901234567890.jpg").writeBytes(jpeg(7))

            db.recoverPhotos(images)

            assertContentEquals(jpeg(1), imageFile(images, live).readBytes())
            assertContentEquals(jpeg(2), imageFile(images, softDeleted).readBytes())
            assertFalse(imageFile(images, gone).exists())
            assertFalse(File(images, "$gone.previous").exists())
            assertFalse(File(images, "$live.previous").exists())
            assertFalse(File(images, "upload-123.tmp").exists())
            assertTrue(File(images, "notes.txt").isFile)
            assertTrue(File(images, "123456789012345678901234567890.jpg").isFile)
        }

    @Test fun `account erasure removes the photos and a later start sweeps any that survived`() =
        fixture { db, _, images, owner, id ->
            db.storePhoto(images, id, jpeg(1), 10, 10, AuditActor.app(owner))
            val other = db.upsertUser("dev", "other", "Other")
            val kept = db.insertSeries(other, request.timestamp, request.caliber, request.holes)
            db.storePhoto(images, kept, jpeg(2), 10, 10, AuditActor.app(other))
            // Pretend the erasure's file delete failed: the row goes, the JPEG stays.
            db.deleteAccount(owner)
            assertTrue(imageFile(images, id).isFile)
            db.recoverPhotos(images)
            assertFalse(imageFile(images, id).exists())
            assertContentEquals(jpeg(2), imageFile(images, kept).readBytes())
        }

    @Test fun `recovery interrupted after the backup was restored does not stop the next start`() =
        fixture { db, _, images, _, id ->
            // A photo with no fingerprint (its upload never committed); the backup was already moved back over
            // the target, then the process died before the journal was deleted.
            imageFile(images, id).writeBytes(jpeg(1))
            File(images, "$id.journal").writeText("present")
            db.recoverPhotos(images)
            db.recoverPhotos(images)
            assertContentEquals(jpeg(1), imageFile(images, id).readBytes())
            assertFalse(File(images, "$id.journal").exists())
            assertFalse(File(images, "$id.previous").exists())
        }

    @Test fun `a journal with an unparseable id does not stop the start`() = fixture { db, _, images, _, _ ->
        File(images, "123456789012345678901234567890.journal").writeText("present")
        db.recoverPhotos(images)
    }

    @Test fun `other DB calls complete while an upload is still writing its file`() = fixture { db, _, images, owner, id ->
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val upload = pool.submit {
                db.storePhoto(images, id, jpeg(1), 10, 10, AuditActor.app(owner)) { path, data ->
                    writing.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    java.nio.file.Files.write(path, data)
                }
            }
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the upload never started writing")
            val read = pool.submit<Series?> { db.getSeries(id) }
            val series = runCatching { read.get(2, TimeUnit.SECONDS) }
            release.countDown()
            upload.get(10, TimeUnit.SECONDS)
            assertTrue(series.isSuccess, "getSeries waited for the upload's file write: ${series.exceptionOrNull()}")
            assertContentEquals(jpeg(1), imageFile(images, id).readBytes())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `concurrent uploads to one series leave one consistent photo and no recovery files`() =
        fixture { db, _, images, owner, id ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                (1..20).map { n -> pool.submit { db.storePhoto(images, id, jpeg(n), 10, 10, AuditActor.app(owner)) } }
                    .forEach { it.get(20, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            val stored = imageFile(images, id).readBytes()
            assertTrue((1..20).any { stored.contentEquals(jpeg(it)) })
            assertEquals(listOf("$id.jpg"), images.list()!!.sorted())
            // The committed fingerprint is the stored file's: a restart keeps it as it is.
            db.recoverPhotos(images)
            assertContentEquals(stored, imageFile(images, id).readBytes())
        }
}
