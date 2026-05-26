package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ConnectedComponentsTest {

    private fun mask(w: Int, h: Int, init: (Int, Int) -> Boolean): ByteArray {
        val buf = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            buf[y * w + x] = if (init(x, y)) 0.toByte() else 255.toByte()
        }
        return buf
    }

    @Test
    fun `single rectangular blob is found with correct centroid and bbox`() {
        val w = 10; val h = 10
        val buf = mask(w, h) { x, y -> x in 3..6 && y in 2..5 }   // 4 wide, 4 tall
        val r = findDarkBlobs(buf, w, h, threshold = 128, minArea = 1)
        assertEquals(1, r.blobs.size)
        val b = r.blobs.single()
        assertEquals(16, b.pixelCount)
        assertEquals(3, b.minX); assertEquals(6, b.maxX)
        assertEquals(2, b.minY); assertEquals(5, b.maxY)
        assertEquals(4.5f, b.centroidX, 1e-3f)
        assertEquals(3.5f, b.centroidY, 1e-3f)
    }

    @Test
    fun `two non-touching blobs are separate components`() {
        val w = 20; val h = 10
        val buf = mask(w, h) { x, y ->
            (x in 1..3 && y in 1..3) || (x in 10..13 && y in 5..7)
        }
        val r = findDarkBlobs(buf, w, h, threshold = 128, minArea = 1)
        assertEquals(2, r.blobs.size)
    }

    @Test
    fun `diagonal-only neighbours are separate under 4-connectivity`() {
        // Two single pixels touching only at a corner.
        val w = 5; val h = 5
        val buf = mask(w, h) { x, y -> (x == 1 && y == 1) || (x == 2 && y == 2) }
        val r = findDarkBlobs(buf, w, h, threshold = 128, minArea = 1)
        assertEquals(2, r.blobs.size)
    }

    @Test
    fun `minArea filter drops a single-pixel speck`() {
        val w = 10; val h = 10
        val buf = mask(w, h) { x, y -> (x in 2..5 && y in 2..5) || (x == 9 && y == 9) }
        val r = findDarkBlobs(buf, w, h, threshold = 128, minArea = 4)
        assertEquals(1, r.blobs.size)
        assertEquals(16, r.blobs.single().pixelCount)
    }

    @Test
    fun `empty buffer returns no blobs`() {
        val r = findDarkBlobs(ByteArray(0), 0, 0, 128, 1)
        assertTrue(r.blobs.isEmpty())
    }

    @Test
    fun `label grid agrees with blob root labels`() {
        val w = 10; val h = 10
        val buf = mask(w, h) { x, y -> x in 3..6 && y in 2..5 }
        val r = findDarkBlobs(buf, w, h, threshold = 128, minArea = 1)
        val expectedLabel = r.blobs.single().label
        // every pixel in the blob's bbox interior should carry the same label
        for (y in 2..5) for (x in 3..6) {
            assertEquals(expectedLabel, r.labels[y * w + x])
        }
    }
}
