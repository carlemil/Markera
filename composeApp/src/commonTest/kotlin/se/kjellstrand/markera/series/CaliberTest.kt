package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals

class CaliberTest {

    @Test
    fun labelRoundTripsForEveryValue() {
        Caliber.entries.forEach { assertEquals(it, Caliber.fromLabel(it.label)) }
    }

    @Test
    fun unknownLabelFallsBackToNone() {
        assertEquals(Caliber.NONE, Caliber.fromLabel("9x19"))
        assertEquals(Caliber.NONE, Caliber.fromLabel(""))
        assertEquals(Caliber.NONE, Caliber.fromLabel("22LR")) // Wire values are case-sensitive.
    }

    @Test
    fun noneIsFirstSoItIsTheDefaultChoice() {
        assertEquals(Caliber.NONE, Caliber.entries.first())
    }
}
