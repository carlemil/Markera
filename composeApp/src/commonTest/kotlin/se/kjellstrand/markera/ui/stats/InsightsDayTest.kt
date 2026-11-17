package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.stats.DatePreset

@OptIn(ExperimentalTime::class)
class InsightsDayTest {
    private val day = InsightsDay(LocalDate(2026, 11, 3), Caliber.LR22)

    @Test
    fun theWindowIsThePhonesOwnDay() {
        val (from, to) = day.window(TimeZone.of("Europe/Stockholm"))
        // Stockholm is UTC+1 in November.
        assertEquals(Instant.parse("2026-11-02T23:00:00Z"), from)
        assertEquals(Instant.parse("2026-11-03T22:59:59.999Z"), to)
    }

    @Test
    fun theFilterRowShowsTheCaliberAndTheDay() {
        val s = day.selection()
        assertEquals(setOf(Caliber.LR22), s.calibers)
        assertEquals(DatePreset.CUSTOM, s.preset)
        val utcMidnight = Instant.parse("2026-11-03T00:00:00Z").toEpochMilliseconds()
        assertEquals(utcMidnight to utcMidnight, s.customRange)
    }

    private val geometry = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)
    private fun series(id: Long, day: Int, ring: Int, dx: Double = 0.0) = SeriesDto(
        id = id,
        timestamp = "2026-10-${(day + 1).toString().padStart(2, '0')}T10:${id.toString().padStart(2, '0')}:00Z",
        caliber = "22lr",
        holes = List(5) { HoleDto(500.0 + dx + it, 500.0 + it * it, ring, false, null) },
        geometry = geometry,
    )

    @Test
    fun aLowDayOpensFromTheCalibersFirstSeries() {
        val history = (0 until 10).map { series(it.toLong(), it, 9) }
        val low = (10 until 13).map { series(it.toLong(), 12, 6) }
        val day = insightsDayFor(low.last(), LocalDate(2026, 10, 13), history + low, TimeZone.UTC)
        assertEquals(LocalDate(2026, 10, 1), day.from)
        assertEquals(LocalDate(2026, 10, 13), day.date)
        assertEquals(Caliber.LR22, day.caliber)
    }

    @Test
    fun anyOtherDayOpensOnThatDayAlone() {
        val history = (0 until 10).map { series(it.toLong(), it, 9) }
        val day = insightsDayFor(history.last(), LocalDate(2026, 10, 10), history, TimeZone.UTC)
        assertEquals(day.date, day.from)
    }
}
