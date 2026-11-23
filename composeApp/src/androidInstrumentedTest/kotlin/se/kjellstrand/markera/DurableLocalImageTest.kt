package se.kjellstrand.markera

import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import se.kjellstrand.markera.series.LocalImageStore

/**
 * #29: the durable photo store (a `FileImageCache(durable = true)` under filesDir/local-series/)
 * through the real Android PlatformFileSync: the temp file is fsynced, renamed and the directory
 * fsynced. On the JVM `android.system.Os` is a stub, so only a device runs that path. A folder of
 * its own under filesDir, never the app's real local-series.
 */
class DurableLocalImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val filesDir = File(context.filesDir, "durable-test-${System.nanoTime()}")

    @After fun cleanUp() { filesDir.deleteRecursively() }

    @Test
    fun aDurableWriteLandsWholeAndLeavesNoTempFile() {
        val store = LocalImageStore(Path(filesDir.path))
        val photo = ByteArray(300_000) { (it % 251).toByte() }
        val small = ByteArray(2_000) { (it % 13).toByte() }
        store.write(7, photo)
        store.writeThumb(7, small)
        assertArrayEquals(photo, store.read(7))
        assertArrayEquals(small, store.readThumb(7))
        val folder = File(filesDir, "local-series")
        assertEquals(setOf("7.jpg", "7.thumb.jpg"), folder.list()!!.toSet())

        // syncDirectory swallows its failures; the same open + fsync of this folder must work here.
        val fd = Os.open(folder.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    @Test
    fun aDurableWriteThatCannotLandThrows() {
        // A file where the folder goes: the write must fail loudly, not quietly drop the photo.
        filesDir.mkdirs()
        File(filesDir, "local-series").writeText("not a folder")
        val store = LocalImageStore(Path(filesDir.path))
        assertNotNull(runCatching { store.write(7, ByteArray(10)) }.exceptionOrNull())
    }
}
