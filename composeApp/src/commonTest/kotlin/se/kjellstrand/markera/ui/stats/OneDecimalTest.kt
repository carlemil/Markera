package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class OneDecimalTest {
    @Test
    fun roundsToOneDecimalWithTheGivenMark() {
        assertEquals("9,3", oneDecimal(9.26, ","))
        assertEquals("9.0", oneDecimal(9.0, "."))
    }
}
