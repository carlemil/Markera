package se.kjellstrand.markera.series

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.io.files.Path

class FileImageCacheTest {

    private val dir = Files.createTempDirectory("series").toFile().apply { deleteOnExit() }
    private val cache = FileImageCache(Path(dir.path))

    @Test
    fun theThumbnailLivesBesideTheFrameAndGoesWithIt() {
        cache.write(4, byteArrayOf(1))
        cache.writeThumb(4, byteArrayOf(2))

        assertEquals(listOf<Byte>(1), cache.read(4)?.toList())
        assertEquals(listOf<Byte>(2), cache.readThumb(4)?.toList())
        assertEquals(setOf("4.jpg", "4.thumb.jpg"), dir.list()!!.toSet())

        cache.delete(4)

        assertNull(cache.read(4))
        assertNull(cache.readThumb(4))
    }

    @Test
    fun aRewriteReplacesTheFileAndLeavesNoTempBehind() {
        cache.write(4, byteArrayOf(1, 2, 3))
        cache.write(4, byteArrayOf(9))

        assertEquals(listOf<Byte>(9), cache.read(4)?.toList())
        assertEquals(setOf("4.jpg"), dir.list()!!.toSet())
    }

    /** #29: a durable write syncs the complete temp file before the rename, then the directory. */
    @Test
    fun aDurableWriteSyncsTheFileBeforeTheRenameAndTheDirectoryAfter() {
        val calls = mutableListOf<String>()
        val target = java.io.File(dir, "7.jpg")
        val sync = object : FileSync {
            override fun syncFile(path: Path) {
                calls += "file ${path.name}"
                assertEquals(listOf<Byte>(1, 2, 3), java.io.File(path.toString()).readBytes().toList())
                assertFalse(target.exists(), "synced before the rename")
            }
            override fun syncDirectory(path: Path) {
                calls += "dir ${path.name}"
                assertEquals(dir.name, path.name)
                assertTrue(target.exists(), "the directory is synced after the rename")
            }
        }
        FileImageCache(Path(dir.path), durable = true, sync = sync).write(7, byteArrayOf(1, 2, 3))

        assertEquals(listOf("file 7.jpg.tmp", "dir ${dir.name}"), calls)
        assertEquals(setOf("7.jpg"), dir.list()!!.toSet())
    }

    @Test
    fun aFailedFileSyncFailsTheDurableWriteAndLeavesNothing() {
        val sync = object : FileSync {
            override fun syncFile(path: Path) = throw IOException("fsync failed")
            override fun syncDirectory(path: Path) = fail("never reached")
        }
        val durable = FileImageCache(Path(dir.path), durable = true, sync = sync)

        assertFailsWith<IOException> { durable.write(7, byteArrayOf(1)) }
        assertEquals(0, dir.list()!!.size, "neither the photo nor its temp file")
    }

    @Test
    fun theEvictableCacheIsNotSynced() {
        val sync = object : FileSync {
            override fun syncFile(path: Path) = fail("cache writes are not synced")
            override fun syncDirectory(path: Path) = fail("cache writes are not synced")
        }
        FileImageCache(Path(dir.path), sync = sync).write(7, byteArrayOf(1))
        assertEquals(listOf<Byte>(1), cache.read(7)?.toList())
    }

    /** The real platform sync on the JVM: the file sync works, the directory sync is best effort. */
    @Test
    fun thePlatformSyncKeepsDurableWritesWorking() {
        val durable = FileImageCache(Path(dir.path), durable = true)
        durable.write(8, byteArrayOf(4, 5))
        assertEquals(listOf<Byte>(4, 5), durable.read(8)?.toList())
        assertEquals(setOf("8.jpg"), dir.list()!!.toSet())
    }

    @Test
    fun clearRemovesThumbnailsToo() {
        cache.write(1, byteArrayOf(1))
        cache.writeThumb(1, byteArrayOf(2))
        cache.writeThumb(2, byteArrayOf(3))

        cache.clear()

        assertEquals(0, dir.list()!!.size)
    }
}
