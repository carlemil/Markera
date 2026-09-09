package se.kjellstrand.markera.series

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The zip we hand-roll in common code has to open in a real zip reader. */
class ZipRoundTripTest {

    @Test
    fun `entries survive java's ZipInputStream`() {
        val jpeg = ByteArray(300) { (it * 7).toByte() }
        val buffer = Buffer()
        ZipWriter(buffer).apply {
            entry("images/å.jpg", jpeg)
            entry("empty.csv", ByteArray(0))
            finish()
        }

        // ZipInputStream verifies each entry's CRC and sizes as it reads.
        ZipInputStream(ByteArrayInputStream(buffer.readByteArray())).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("images/å.jpg", first.name)
            assertArrayEquals(jpeg, zip.readBytes())
            assertEquals(jpeg.size.toLong(), first.size)

            val second = zip.nextEntry!!
            assertEquals("empty.csv", second.name)
            assertArrayEquals(ByteArray(0), zip.readBytes())
            assertEquals(0L, second.size)

            assertNull(zip.nextEntry)
        }
    }
}
