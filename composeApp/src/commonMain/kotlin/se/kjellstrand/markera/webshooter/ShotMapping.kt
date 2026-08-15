package se.kjellstrand.markera.webshooter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN

/**
 * Maps between Markera's picker values (0..10 = ring, 11 = inner X) and
 * webshooter's shot values (0..10 as numbers, "X" for the inner ten which
 * scores 10 points and increments x_count).
 */
object ShotMapping {

    /** Picker value → the shot value webshooter expects ("X" or "0".."10"). */
    fun pickerToShot(value: Int): String =
        if (value == SCORE_PICKER_INNER_TEN) "X" else value.toString()

    fun pickerPoints(value: Int): Int =
        if (value == SCORE_PICKER_INNER_TEN) 10 else value

    fun points(picks: List<Int>): Int = picks.sumOf(::pickerPoints)

    fun xCount(picks: List<Int>): Int = picks.count { it == SCORE_PICKER_INNER_TEN }

    /**
     * webshooter's station_figure_hits entry → picker value, or null when the
     * slot is empty/unparsable. Accepts both the numeric (10) and string
     * ("10", "X"/"x") encodings seen in the API.
     */
    fun shotToPicker(element: JsonElement): Int? {
        val prim = try {
            element.jsonPrimitive
        } catch (_: IllegalArgumentException) {
            return null
        }
        prim.intOrNull?.let { return it.coerceIn(0, 10) }
        val text = prim.contentOrNull?.trim() ?: return null
        if (text.equals("X", ignoreCase = true)) return SCORE_PICKER_INNER_TEN
        return text.toIntOrNull()?.coerceIn(0, 10)
    }

    /** Full station_figure_hits array → picker values (unparsable slots dropped). */
    fun shotsToPickers(elements: List<JsonElement>?): List<Int> =
        elements.orEmpty().mapNotNull(::shotToPicker)
}
