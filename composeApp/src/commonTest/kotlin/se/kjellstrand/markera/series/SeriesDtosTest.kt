package se.kjellstrand.markera.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // The detected position is kept beside the confirmed one.
        assertEquals(listOf(HoleDto(12.0, 34.0, 9, false, 42.5, 9, false, 12.0, 34.0)), req.holes)
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
    fun kindTextNamesEditedManualTypedAndMovedHoles() {
        fun kind(hole: HoleDto) = hole.kindText("manuell", "inskriven", "flyttad", "detekterad")

        // Untouched detection: the detector's own.
        assertEquals("detekterad", kind(HoleDto(1.0, 1.0, 9, false, 30.0, 9, false, 1.0, 1.0)))
        // Score edited down from the detected 9.
        assertEquals("9 → 8", kind(HoleDto(1.0, 1.0, 8, false, 30.0, 9, false, 1.0, 1.0)))
        // Inner ten either way is an "X".
        assertEquals("10 → X", kind(HoleDto(1.0, 1.0, 10, true, 5.0, 10, false, 1.0, 1.0)))
        // Positioned by hand, so no detection at all.
        assertEquals("manuell", kind(HoleDto(1.0, 1.0, 7, false, 80.0)))
        // Typed into a picker slot: no position either.
        assertEquals("inskriven", kind(HoleDto(null, null, 7, false, null)))
        // Dragged off the detected spot, score untouched.
        assertEquals("flyttad", kind(HoleDto(2.0, 1.0, 9, false, null, 9, false, 1.0, 1.0)))
        // Both at once.
        assertEquals("9 → 8, flyttad", kind(HoleDto(2.0, 1.0, 8, false, null, 9, false, 1.0, 1.0)))
    }

    @Test
    fun detailCellsSplitTheDetectedScoreFromTheUsersOwn() {
        val untouched = HoleDto(1.0, 1.0, 9, false, 30.0, 9, false, 1.0, 1.0)
        val edited = untouched.copy(ring = 8)
        val manual = HoleDto(1.0, 1.0, 7, false, 80.0)
        val typed = HoleDto(null, null, 7, false, null)

        // Detected cell: what the detector said, nothing for a hole it never saw.
        assertEquals("9", untouched.detectedLabel())
        assertEquals("9", edited.detectedLabel())
        assertNull(manual.detectedLabel())
        assertNull(typed.detectedLabel())

        // Manual cell: only the user's own value, so an unedited detection is blank.
        assertNull(untouched.manualLabel())
        assertEquals("8", edited.manualLabel())
        assertEquals("7", manual.manualLabel())
        assertEquals("7", typed.manualLabel())

        assertTrue(edited.isEdited())
        assertFalse(untouched.isEdited())
        assertFalse(manual.isEdited())

        // Reverting restores the detected pair (and only that).
        assertEquals(untouched, edited.withDetectedScore())
        assertEquals(untouched, untouched.copy(innerTen = true).withDetectedScore())
        // Nothing to revert to leaves the hole alone.
        assertEquals(manual, manual.withDetectedScore())
    }

    @Test
    fun nearestHoleIndexPicksTheClosestInReachAndSkipsPositionless() {
        val holes = listOf(
            HoleDto(null, null, 7, false, null),
            HoleDto(100.0, 100.0, 9, false, 30.0),
            HoleDto(120.0, 100.0, 8, false, 40.0),
        )

        assertEquals(1, holes.nearestHoleIndex(104.0, 100.0, 20.0))
        assertEquals(2, holes.nearestHoleIndex(115.0, 100.0, 20.0))
        // Nothing within reach, and a positionless hole is never the answer.
        assertEquals(-1, holes.nearestHoleIndex(0.0, 0.0, 20.0))
    }

    @Test
    fun withNewHoleScoresFromTheStoredGeometryOrLeavesItUnscored() {
        // Centre (100,100), a circular 6/7 ring of 100 px = 100 mm, so 1 px = 1 mm.
        val geometry = GeometryDto(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 0.0)
        val existing = listOf(HoleDto(1.0, 1.0, 9, false, 30.0, 9, false, 1.0, 1.0))

        val innerTen = existing.withNewHole(110.0, 100.0, geometry)
        assertEquals(existing, innerTen.dropLast(1))
        assertEquals(HoleDto(110.0, 100.0, 10, true, 10.0), innerTen.last())

        // 60 mm out lands in ring 8, and no edge gauge is applied.
        assertEquals(
            HoleDto(100.0, 160.0, 8, false, 60.0),
            existing.withNewHole(100.0, 160.0, geometry).last(),
        )

        // No geometry: nothing to measure or score against.
        assertEquals(HoleDto(5.0, 6.0, 0, false, null), existing.withNewHole(5.0, 6.0, null).last())
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
