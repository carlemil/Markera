package se.kjellstrand.markera.vision

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageOpsTest {

    @Test
    fun `luma uses Rec 601 weights`() {
        val luma = lumaFromArgb(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt()),
        )
        assertEquals(255, luma[0].toInt() and 0xFF)
        assertEquals(0, luma[1].toInt() and 0xFF)
        // Red only: 255 * 299 / 1000 = 76.
        assertEquals(76, luma[2].toInt() and 0xFF)
    }

    @Test
    fun `letterbox dims fit the long edge`() {
        assertEquals(1536 to 1024, letterboxDims(3000, 2000, 1536))
    }

    @Test
    fun `letterbox centres the scaled block and zero-pads`() {
        val chw = letterboxChw(intArrayOf(0xFFFF0000.toInt()), 1, 1, 3)
        val plane = 3 * 3
        assertEquals(3 * plane, chw.size)
        for (i in 0 until plane) {
            assertEquals(if (i == 1 * 3 + 1) 1f else 0f, chw[i], "R plane at $i")
            assertEquals(0f, chw[i + plane], "G plane at $i")
            assertEquals(0f, chw[i + 2 * plane], "B plane at $i")
        }
    }
}
