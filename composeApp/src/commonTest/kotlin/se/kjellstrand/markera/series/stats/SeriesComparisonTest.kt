package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(ExperimentalTime::class)
class SeriesComparisonTest {

    private val geometry = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)
    private var nextId = 1L

    /** Five holes whose rings add up to [total] (at most 50), all at the centre. */
    private fun series(
        total: Int,
        day: Int,
        hour: Int = 10,
        caliber: String = "22lr",
        holes: Int = 5,
    ): SeriesDto {
        val base = total / holes
        val extra = total % holes
        val rings = List(holes) { if (it < extra) base + 1 else base }
        return SeriesDto(
            id = nextId++,
            timestamp = "2026-10-${day.toString().padStart(2, '0')}T${hour.toString().padStart(2, '0')}:00:00Z",
            caliber = caliber,
            holes = rings.map { HoleDto(500.0, 500.0, it, false, null) },
            geometry = geometry,
        )
    }

    /** Ten earlier series averaging 44 (43 and 45 alternating), one a day, days 1..10, the best 45. */
    private fun history(): List<SeriesDto> = List(10) { series(if (it % 2 == 0) 43 else 45, day = it + 1) }

    @Test
    fun `a low series says how far under the average it is`() {
        val s = series(38, day = 15)
        assertEquals(SeriesComparison.Under(6, 44), compareWithAverage(s, history() + s))
    }

    @Test
    fun `a series above the average but under the best says over`() {
        val s = series(46, day = 15)
        // A 48 earlier on: the average stays 44 and 46 is not the best.
        val all = history() + series(48, day = 3, hour = 9) + s
        assertEquals(SeriesComparison.Over(2, 44), compareWithAverage(s, all))
    }

    @Test
    fun `a series on the average says so`() {
        val s = series(44, day = 15)
        assertEquals(SeriesComparison.OnAverage(44), compareWithAverage(s, history() + s))
    }

    @Test
    fun `the best total of the last 30 days is the best`() {
        val s = series(49, day = 15)
        assertEquals(SeriesComparison.Best(30), compareWithAverage(s, history() + s))
    }

    @Test
    fun `an old best does not count as the best of the last 30 days`() {
        val old = series(50, day = 1, hour = 9).copy(timestamp = "2026-08-01T10:00:00Z")
        val s = series(49, day = 15)
        // The August 50 still feeds the average (rounded 45) but not the 30 day window.
        assertEquals(SeriesComparison.Best(30), compareWithAverage(s, history() + old + s))
    }

    @Test
    fun `fewer than ten earlier series of the caliber gives no line`() {
        val s = series(38, day = 15)
        assertNull(compareWithAverage(s, history().drop(1) + s))
    }

    @Test
    fun `no history gives no line`() {
        val s = series(38, day = 15)
        assertNull(compareWithAverage(s, listOf(s)))
    }

    @Test
    fun `other calibers and hole counts and later series do not count`() {
        val s = series(38, day = 15)
        val others = history().map { it.copy(caliber = "9mm") } + series(40, day = 12, holes = 10) + series(50, day = 20)
        assertNull(compareWithAverage(s, others + s))
    }

    @Test
    fun `caliber dash is never compared`() {
        val s = series(38, day = 15, caliber = "-")
        assertNull(compareWithAverage(s, history().map { it.copy(caliber = "-") } + s))
    }

    @Test
    fun `a day with fewer than three series has no day finding`() {
        val a = series(30, day = 15)
        val b = series(30, day = 15, hour = 11)
        assertNull(dayFinding(a, history() + a + b, TimeZone.UTC))
    }

    @Test
    fun `a clearly low day of three series is the day finding`() {
        val a = series(36, day = 15)
        val b = series(36, day = 15, hour = 11)
        val c = series(36, day = 15, hour = 12)
        val (date, finding) = assertNotNull(dayFinding(b, history() + a + b + c, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 15), date)
        assertTrue(finding is Finding.LowDay)
    }

    @Test
    fun `an ordinary day of three series has no day finding`() {
        val a = series(44, day = 15)
        val b = series(43, day = 15, hour = 11)
        val c = series(45, day = 15, hour = 12)
        assertNull(dayFinding(b, history() + a + b + c, TimeZone.UTC))
    }

    /** Five 9s in a small plus 30 mm right of centre (1 px = 1 mm), on 2026-10-15 at [hour]. */
    private fun offsetSeries(hour: Int): SeriesDto {
        val spots = listOf(530.0 to 500.0, 533.0 to 500.0, 527.0 to 500.0, 530.0 to 503.0, 530.0 to 497.0)
        return series(45, day = 15, hour = hour).copy(holes = spots.map { (x, y) -> HoleDto(x, y, 9, false, null) })
    }

    private fun offsetDay(): List<SeriesDto> = listOf(10, 11, 12, 13).map { offsetSeries(it) }

    private fun at(text: String): Long = kotlin.time.Instant.parse(text).toEpochMilliseconds()

    @Test
    fun `a day of offset series has the offset as its day finding`() {
        val day = offsetDay()
        val (_, finding) = assertNotNull(dayFinding(day.last(), day, TimeZone.UTC))
        assertTrue(finding is Finding.OffsetGroup)
    }

    @Test
    fun `sights adjusted mid-session judge the offset only on the later series`() {
        val day = offsetDay()
        // Two series (10 holes) after 11:30: under the offset finding's 15.
        val sights = mapOf("22lr" to SightSetup(adjustedAtMs = at("2026-10-15T11:30:00Z")))
        assertNull(dayFinding(day.last(), day, TimeZone.UTC, sights = sights))
    }

    @Test
    fun `sights adjusted on a later day leave a past day's offset alone`() {
        val day = offsetDay()
        val sights = mapOf("22lr" to SightSetup(adjustedAtMs = at("2026-10-16T09:00:00Z")))
        val (_, finding) = assertNotNull(dayFinding(day.last(), day, TimeZone.UTC, sights = sights))
        assertTrue(finding is Finding.OffsetGroup)
    }

    @Test
    fun `another caliber's sight adjustment changes nothing`() {
        val day = offsetDay()
        val sights = mapOf("9mm" to SightSetup(adjustedAtMs = at("2026-10-15T11:30:00Z")))
        val (_, finding) = assertNotNull(dayFinding(day.last(), day, TimeZone.UTC, sights = sights))
        assertTrue(finding is Finding.OffsetGroup)
    }
}
