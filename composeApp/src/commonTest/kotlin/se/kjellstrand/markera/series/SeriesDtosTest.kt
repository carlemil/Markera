package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import se.kjellstrand.markera.ui.markera.SCORE_PICKER_INNER_TEN
import se.kjellstrand.markera.vision.HitScore
import se.kjellstrand.markera.webshooter.api.webshooterJson

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
        assertEquals(listOf(HoleDto(12.0, 34.0, 9, false, 42.5, 9, false)), req.holes)
    }

    @Test
    fun aManualHoleCarriesNoDetectedValues() {
        val hit = HitScore(1f, 2f, 0f, 5.0, 10, true, manual = true)
        assertEquals(HoleDto(1.0, 2.0, 10, true, 5.0, null, null), hit.toHoleDto())
    }

    @Test
    fun picksBecomeTheConfirmedValuesAndKeepWhatWasDetected() {
        val req = request(
            HoleDto(1.0, 1.0, 9, false, 30.0, 9, false),
            HoleDto(2.0, 2.0, 10, false, 20.0, 10, false),
            HoleDto(3.0, 3.0, 8, false, 60.0, 8, false),
        ).withPicks(listOf(8, SCORE_PICKER_INNER_TEN, 0, 7, 0))

        assertEquals(
            listOf(
                // Edited down; the detection is kept for training.
                HoleDto(1.0, 1.0, 8, false, 30.0, 9, false),
                // Inner ten -> ring 10 + innerTen.
                HoleDto(2.0, 2.0, 10, true, 20.0, 10, false),
                // A detected hole zeroed = a false positive, kept as a 0.
                HoleDto(3.0, 3.0, 0, false, 60.0, 8, false),
                // Beyond the holes: typed by hand, no position, no detection.
                HoleDto(null, null, 7, false, null, null, null),
                // A 0 beyond the holes is just an empty picker slot.
            ),
            req.holes,
        )
    }

    @Test
    fun holesBeyondThePickersAreUntouched() {
        val holes = (1..7).map { HoleDto(it.toDouble(), 0.0, 9, false, 30.0, 9, false) }
        val req = request(*holes.toTypedArray()).withPicks(List(5) { 0 })

        assertEquals(holes.drop(5), req.holes.drop(5))
        assertTrue(req.holes.take(5).all { it.ring == 0 && it.detectedRing == 9 })
    }

    @Test
    fun holeDtoRoundTripsThroughJson() {
        val holes = listOf(
            HoleDto(1.0, 2.0, 9, false, 30.0, 10, true),
            HoleDto(null, null, 7, false, null, null, null),
        )
        val req = request(*holes.toTypedArray())
        assertEquals(req, webshooterJson.decodeFromString(webshooterJson.encodeToString(req)))
    }

    private fun request(vararg holes: HoleDto) =
        SeriesRequest("2026-09-06T10:00:00Z", "9mm", holes.toList())

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
