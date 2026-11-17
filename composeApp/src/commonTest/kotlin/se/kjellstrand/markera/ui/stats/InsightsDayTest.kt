package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
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
}
