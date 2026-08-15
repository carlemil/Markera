package se.kjellstrand.markera.webshooter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import se.kjellstrand.markera.webshooter.api.laravelFormEncode

class LaravelFormTest {

    @Test
    fun flattensScalars() {
        val p = laravelFormEncode(mapOf("patrol" to 1, "patrol_type" to "", "active" to true))
        assertEquals("1", p["patrol"])
        assertEquals("", p["patrol_type"])
        assertEquals("true", p["active"])
    }

    @Test
    fun nullBecomesEmptyString() {
        val p = laravelFormEncode(mapOf("comment" to null))
        assertEquals("", p["comment"])
    }

    @Test
    fun flattensNestedAuditMapWithShotsList() {
        val p = laravelFormEncode(
            mapOf(
                "audit" to mapOf(
                    "signup_id" to 19248L,
                    "shots" to listOf("X", "10", "9", "0", "7"),
                    "points" to 36,
                )
            )
        )
        assertEquals("19248", p["audit[signup_id]"])
        assertEquals("X", p["audit[shots][0]"])
        assertEquals("10", p["audit[shots][1]"])
        assertEquals("7", p["audit[shots][4]"])
        assertEquals("36", p["audit[points]"])
        assertNull(p["audit[shots]"])
    }

    @Test
    fun flattensResultsListOfMaps() {
        val p = laravelFormEncode(
            mapOf(
                "results" to listOf(
                    mapOf("stations_id" to 1, "station_figure_hits" to listOf("10", "X")),
                    mapOf("stations_id" to 2, "scored_at" to "2026-08-15T10:00:00.000Z"),
                )
            )
        )
        assertEquals("1", p["results[0][stations_id]"])
        assertEquals("10", p["results[0][station_figure_hits][0]"])
        assertEquals("X", p["results[0][station_figure_hits][1]"])
        assertEquals("2", p["results[1][stations_id]"])
        assertEquals("2026-08-15T10:00:00.000Z", p["results[1][scored_at]"])
    }
}
