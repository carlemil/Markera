package se.kjellstrand.markera.series

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * PR #71 (#29, #30, #31): the durable local photo store on iOS, through the real `FileSync.ios.kt`
 * (`F_FULLFSYNC`/`fsync` on the temp file, then on the directory) rather than a fake.
 */
class DurableImageStoreIosTest {

    private val root = Path(NSTemporaryDirectory(), "durable-store-${NSUUID().UUIDString}")
    private val dir = Path(root, "local-series")

    @AfterTest
    fun cleanUp() {
        // kotlinx-io has no recursive delete; the store's dir only holds flat files.
        runCatching { SystemFileSystem.list(dir) }.getOrNull()?.forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(dir, mustExist = false)
        SystemFileSystem.delete(root, mustExist = false)
    }

    @Test
    fun aDurableWriteReadsBackWithNoTempFileLeft() {
        val store = LocalImageStore(root)
        val photo = ByteArray(64 * 1024) { (it % 251).toByte() }
        val thumb = byteArrayOf(-1, -40, 7, 8, 9)

        store.write(7, photo)
        store.writeThumb(7, thumb)

        assertContentEquals(photo, store.read(7))
        assertContentEquals(thumb, store.readThumb(7))
        assertEquals(setOf("7.jpg", "7.thumb.jpg"), SystemFileSystem.list(dir).map { it.name }.toSet())
    }

    @Test
    fun aSecondWriteReplacesTheFirst() {
        val store = LocalImageStore(root)
        store.write(3, byteArrayOf(1, 2, 3))
        store.write(3, byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(4, 5), store.read(3))
    }

    @Test
    fun deleteRemovesThePhotoAndItsThumbnail() {
        val store = LocalImageStore(root)
        store.write(5, byteArrayOf(1))
        store.writeThumb(5, byteArrayOf(2))
        store.delete(5)
        assertNull(store.read(5))
        assertNull(store.readThumb(5))
    }

    @Test
    fun syncingAMissingFileThrowsButAMissingDirectoryDoesNot() {
        assertFailsWith<IOException> { PlatformFileSync.syncFile(Path(root, "missing.jpg")) }
        PlatformFileSync.syncDirectory(Path(root, "missing-dir"))
    }
}
