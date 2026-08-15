package se.kjellstrand.markera.webshooter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN

class ShotMappingTest {

    @Test
    fun pickerToShotPassesRingsThroughAndMapsInnerTenToX() {
        assertEquals("0", ShotMapping.pickerToShot(0))
        assertEquals("10", ShotMapping.pickerToShot(10))
        assertEquals("X", ShotMapping.pickerToShot(SCORE_PICKER_INNER_TEN))
    }

    @Test
    fun pointsCountXAsTen() {
        // X, 10, 9, 8, 0 → 37 points, 1 X
        val picks = listOf(SCORE_PICKER_INNER_TEN, 10, 9, 8, 0)
        assertEquals(37, ShotMapping.points(picks))
        assertEquals(1, ShotMapping.xCount(picks))
    }

    @Test
    fun allXIsFiftyPointsFiveX() {
        val picks = List(5) { SCORE_PICKER_INNER_TEN }
        assertEquals(50, ShotMapping.points(picks))
        assertEquals(5, ShotMapping.xCount(picks))
    }

    @Test
    fun shotToPickerHandlesNumbersStringsAndX() {
        assertEquals(10, ShotMapping.shotToPicker(JsonPrimitive(10)))
        assertEquals(9, ShotMapping.shotToPicker(JsonPrimitive("9")))
        assertEquals(SCORE_PICKER_INNER_TEN, ShotMapping.shotToPicker(JsonPrimitive("X")))
        assertEquals(SCORE_PICKER_INNER_TEN, ShotMapping.shotToPicker(JsonPrimitive("x")))
        assertEquals(null, ShotMapping.shotToPicker(JsonPrimitive("")))
    }

    @Test
    fun roundTripSurvives() {
        val picks = listOf(SCORE_PICKER_INNER_TEN, 10, 7, 0, 3)
        val wire = picks.map { JsonPrimitive(ShotMapping.pickerToShot(it)) }
        assertEquals(picks, ShotMapping.shotsToPickers(wire))
    }
}
