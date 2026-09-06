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

    @Test
    fun totalSumsRingsAndScoreLineSortsHighestFirst() {
        val series = SeriesDto(
            id = 1,
            timestamp = "2026-09-06T10:00:00Z",
            caliber = "9mm",
            holes = listOf(
                HoleDto(0.0, 0.0, 9, false, 30.0),
                HoleDto(0.0, 0.0, 8, false, 50.0),
                HoleDto(0.0, 0.0, 10, true, 5.0),
                HoleDto(0.0, 0.0, 10, false, 20.0),
                HoleDto(0.0, 0.0, 9, false, 35.0),
            ),
        )
        assertEquals(46, series.total())
        assertEquals("X 10 9 9 8", series.scoreLine())
    }
}
