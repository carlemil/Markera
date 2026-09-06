package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.kjellstrand.markera.vision.HitScore

class SeriesDtosTest {

    @Test
    fun nowIsoIsAnIsoInstantWithZSuffix() {
        val stamp = nowIso()
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""").matches(stamp),
            "not an ISO-8601 UTC instant: $stamp",
        )
    }

    @Test
    fun seriesRequestMapsScoresAndCaliberLabel() {
        val hit = HitScore(
            centerXpx = 12f,
            centerYpx = 34f,
            topYpx = 30f,
            distanceMm = 42.5,
            ring = 9,
            isInnerTen = false,
        )
        val req = seriesRequest(listOf(hit), Caliber.MM9, "2026-09-06T10:00:00Z")
        assertEquals("9mm", req.caliber)
        assertEquals("2026-09-06T10:00:00Z", req.timestamp)
        assertEquals(listOf(HoleDto(12.0, 34.0, 9, false, 42.5)), req.holes)
    }
}
