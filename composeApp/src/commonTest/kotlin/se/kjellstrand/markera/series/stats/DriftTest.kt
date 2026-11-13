package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(ExperimentalTime::class)
class DriftTest {

    /** 1 px = 1 mm around (500,500), as in SeriesStatsTest. */
    private val geometry = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)

    private fun hole(x: Double?, y: Double?) = HoleDto(x = x, y = y, ring = 9, innerTen = false, distanceMm = null)

    private fun series(id: Long, timestamp: String, vararg holes: HoleDto) =
        SeriesDto(id = id, timestamp = timestamp, caliber = "9mm", holes = holes.toList(), geometry = geometry)

    @Test
    fun `one point per day over every hit of that day's series together, oldest first`() {
        val days = listOf(
            // Day two, one series: mean (20, 0), median (20, 0).
            series(3, "2026-09-02T09:00:00Z", hole(520.0, 500.0)),
            // Day one, two series merged: x 0, 0, 30, 90 -> mean 30, median 15; y 10, 0, 0, 0 -> mean 2.5, median 0.
            series(1, "2026-09-01T08:00:00Z", hole(500.0, 510.0), hole(500.0, 500.0)),
            series(2, "2026-09-01T18:00:00Z", hole(530.0, 500.0), hole(590.0, 500.0)),
        ).plotSeries(StatsFilter()).dailyImpact(TimeZone.UTC)

        assertEquals(listOf(LocalDate(2026, 9, 1), LocalDate(2026, 9, 2)), days.map { it.date })
        val first = days[0]
        assertEquals(30.0, first.meanXMm, 1e-6)
        assertEquals(2.5, first.meanYMm, 1e-6)
        assertEquals(15.0, first.medianXMm, 1e-6)
        assertEquals(0.0, first.medianYMm, 1e-6)
        assertEquals(2, first.seriesCount)
        assertEquals(4, first.hitCount)
        assertEquals(20.0, days[1].meanXMm, 1e-6)
        assertEquals(listOf(0f, 1f), days.map { it.age })
    }

    @Test
    fun `the day is the local calendar day`() {
        val s = listOf(series(1, "2026-09-01T23:30:00Z", hole(500.0, 500.0))).plotSeries(StatsFilter())
        assertEquals(LocalDate(2026, 9, 1), s.dailyImpact(TimeZone.UTC).single().date)
        assertEquals(LocalDate(2026, 9, 2), s.dailyImpact(TimeZone.of("Europe/Stockholm")).single().date)
    }

    @Test
    fun `a typed hole is skipped and its series kept, a day with no positioned hit has no point`() {
        val mixed = series(1, "2026-09-01T10:00:00Z", hole(540.0, 500.0), hole(null, null))
        val typedOnly = series(2, "2026-09-02T10:00:00Z", hole(null, null))
        // The other tabs still drop the whole series.
        assertTrue(listOf(mixed).plotSeries(StatsFilter()).isEmpty())

        val days = listOf(mixed, typedOnly).plotSeries(StatsFilter(), skipUnpositioned = true).dailyImpact(TimeZone.UTC)
        assertEquals(1, days.single().hitCount)
        assertEquals(40.0, days.single().meanXMm, 1e-6)
        assertEquals(0f, days.single().age)
    }
}
