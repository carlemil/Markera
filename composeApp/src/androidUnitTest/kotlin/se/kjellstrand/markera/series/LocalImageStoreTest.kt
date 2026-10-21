package se.kjellstrand.markera.series

import java.nio.file.Files
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalImageStoreTest {
    @Test fun localPhotosLiveInDurableFilesAndSurviveRecreatingTheStore() {
        val root = Files.createTempDirectory("markera-local-images-")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val store = LocalImageStore(Path(root.toString()))
            store.write(7, bytes)
            store.writeThumb(7, byteArrayOf(4))
            assertTrue(Files.exists(root.resolve("local-series/7.jpg")))
            val reopened = LocalImageStore(Path(root.toString()))
            assertContentEquals(bytes, reopened.read(7))
            assertContentEquals(byteArrayOf(4), reopened.readThumb(7))
            reopened.delete(7)
            assertNull(reopened.read(7))
            assertNull(reopened.readThumb(7))
        } finally { root.toFile().deleteRecursively() }
    }
}
