package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineKnobTest {
    @Test
    fun snapsToTheNearestSeriesAndClampsAtTheEnds() {
        // Five series on a 100 px bar sit at 0, 25, 50, 75, 100.
        assertEquals(0, knobIndex(12f, 100f, 5))
        assertEquals(1, knobIndex(13f, 100f, 5))
        assertEquals(4, knobIndex(99f, 100f, 5))
        assertEquals(0, knobIndex(-30f, 100f, 5))
        assertEquals(4, knobIndex(130f, 100f, 5))
        // One series (or no width yet) has only index 0.
        assertEquals(0, knobIndex(80f, 100f, 1))
        assertEquals(0, knobIndex(80f, 0f, 5))
    }

    @Test
    fun offShowsEverySeriesOnShowsOnlyTheKnobs() {
        val plotted = listOf("a", "b", "c")
        assertEquals(plotted, knobSelection(plotted, 1, on = false))
        assertEquals(listOf("b"), knobSelection(plotted, 1, on = true))
        assertEquals(emptyList(), knobSelection(emptyList<String>(), 0, on = true))
    }
}
