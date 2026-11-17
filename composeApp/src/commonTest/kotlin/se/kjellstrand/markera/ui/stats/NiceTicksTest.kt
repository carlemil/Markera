package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NiceTicksTest {

    @Test
    fun `close scores are widened to the minimum span`() {
        val ticks = niceTicks(44.0, 46.0, minSpan = 10.0, whole = true)
        assertTrue(ticks.first() <= 40.0 && ticks.last() >= 50.0, ticks.toString())
    }

    @Test
    fun `whole ticks never step by a fraction`() {
        val ticks = niceTicks(40.0, 50.0, whole = true)
        assertTrue(ticks.all { it == kotlin.math.floor(it) }, ticks.toString())
    }

    @Test
    fun `without options the range is the data`() {
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), niceTicks(0.0, 10.0))
    }
}
