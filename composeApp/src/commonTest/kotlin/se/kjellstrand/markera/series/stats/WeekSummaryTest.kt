package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(ExperimentalTime::class)
class WeekSummaryTest {

    private val now = Instant.parse("2026-09-15T10:00:00Z")

    private fun series(id: Long, timestamp: String, vararg rings: Int) = SeriesDto(
        id = id,
        timestamp = timestamp,
        caliber = "9mm",
        holes = rings.map { HoleDto(x = null, y = null, ring = it, innerTen = false, distanceMm = null) },
    )

    @Test
    fun `counts and averages and finds the best of the last seven days only`() {
        val summary = listOf(
            series(1, "2026-09-15T09:00:00Z", 10, 10, 9, 9, 8), // 46
            series(2, "2026-09-10T09:00:00Z", 8, 8, 8, 8, 8), // 40
            series(3, "2026-09-08T10:00:00Z", 9, 9, 9, 9, 9), // 45, exactly seven days back
            series(4, "2026-09-08T09:59:59Z", 10, 10, 10, 10, 10), // a second too old
            series(5, "not a time", 10, 10, 10, 10, 10),
        ).weekSummary(now)!!

        assertEquals(3, summary.count)
        assertEquals(131.0 / 3, summary.average, 1e-9)
        assertEquals(46, summary.best)
    }

    @Test
    fun `nothing recent means no summary`() {
        assertNull(listOf(series(1, "2026-08-01T09:00:00Z", 10)).weekSummary(now))
        assertNull(emptyList<SeriesDto>().weekSummary(now))
    }

    @Test
    fun `a day's average is per series`() {
        val day = listOf(series(1, "2026-09-15T09:00:00Z", 10, 9), series(2, "2026-09-15T09:10:00Z", 8, 8))
        assertEquals(17.5, day.averageTotal(), 1e-9)
        assertEquals(0.0, emptyList<SeriesDto>().averageTotal(), 1e-9)
    }
}
