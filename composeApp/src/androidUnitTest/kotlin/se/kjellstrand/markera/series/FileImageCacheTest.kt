package se.kjellstrand.markera.series

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun clearRemovesThumbnailsToo() {
        cache.write(1, byteArrayOf(1))
        cache.writeThumb(1, byteArrayOf(2))
        cache.writeThumb(2, byteArrayOf(3))

        cache.clear()

        assertEquals(0, dir.list()!!.size)
    }
}
