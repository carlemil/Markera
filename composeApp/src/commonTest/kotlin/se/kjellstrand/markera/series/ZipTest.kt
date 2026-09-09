package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals

class ZipTest {

    /** The standard CRC-32 check value for "123456789". */
    @Test
    fun `crc32 matches the reference check value`() {
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
    }
}
