package se.kjellstrand.markera.vision

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class OtsuTest {

    private fun gray(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `bimodal dark and light split at a threshold between the two modes`() {
        val pixels = IntArray(100)
        for (i in 0 until 50) pixels[i] = 30          // dark group
        for (i in 50 until 100) pixels[i] = 200       // light group
        val t = otsuThreshold(ByteArray(100) { pixels[it].toByte() }, 10, 10)
        assertTrue(t in 30..200, "threshold $t should sit between the two modes")
        // For an exactly bimodal histogram Otsu picks the lower mode (foreground edge).
        assertTrue(t < 100, "threshold should be near the lower mode for tight bimodal")
    }

    @Test
    fun `single-valued buffer returns 0`() {
        val t = otsuThreshold(ByteArray(100) { 128.toByte() }, 10, 10)
        // No between-class variance is positive, so bestT stays at initial 0.
        assertEquals(0, t)
    }

    @Test
    fun `empty buffer returns 0`() {
        assertEquals(0, otsuThreshold(ByteArray(0), 0, 0))
    }

    @Test
    fun `dark disc on light background yields threshold separating them`() {
        // 20x20 with a 6x6 dark square in the middle (intensity 40) on a light field (220)
        val w = 20
        val h = 20
        val buf = ByteArray(w * h) { 220.toByte() }
        for (y in 7 until 13) for (x in 7 until 13) buf[y * w + x] = 40.toByte()
        val t = otsuThreshold(buf, w, h)
        assertTrue(t in 40..220, "threshold $t should land between 40 and 220")
    }
}
