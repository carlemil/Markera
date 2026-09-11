package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(kotlin.time.ExperimentalTime::class)
class TrendTest {

    // Centre at (500, 500), the 6/7 ring 100 px = 100 mm: one px is one mm.
    private val geometry = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)

    private fun series(id: Long, timestamp: String, caliber: String, vararg rings: Int) = SeriesDto(
        id = id,
        timestamp = timestamp,
        caliber = caliber,
        holes = rings.map { HoleDto(500.0, 500.0, it, false, 0.0) },
        geometry = geometry,
    )

    @Test
    fun weeklyBucketsAverageTheScoreAndSplitPerCaliber() {
        val all = listOf(
            series(1, "2026-08-31T10:00:00Z", "9mm", 10, 10, 10, 10, 10), // Mon, week 1
            series(2, "2026-09-02T10:00:00Z", "9mm", 8, 8, 8, 8, 8), // Wed, week 1
            series(3, "2026-09-08T10:00:00Z", "9mm", 9, 9, 9, 9, 9), // Mon, week 2
            series(4, "2026-09-03T10:00:00Z", "22lr", 7, 7, 7, 7, 7), // Thu, week 1
        ).plotSeries(StatsFilter())

        val weekly = all.trend(Metric.SCORE, Bucket.WEEK, TimeZone.UTC)
        assertEquals(listOf(3, 1), weekly.map { it.seriesCount })
        assertEquals(listOf((50.0 + 40 + 35) / 3, 45.0), weekly.map { it.value })
        assertEquals("2026-08-31T00:00:00Z", weekly.first().at.toString())

        val perCaliber = all.trendByCaliber(Metric.SCORE, Bucket.WEEK, TimeZone.UTC)
        assertEquals(listOf(Caliber.LR22, Caliber.MM9), perCaliber.keys.toList())
        assertEquals(listOf(45.0, 45.0), perCaliber.getValue(Caliber.MM9).map { it.value })
        assertEquals(listOf(35.0), perCaliber.getValue(Caliber.LR22).map { it.value })

        // Per series: one point each, oldest first, tens share as a percentage.
        val each = all.trend(Metric.TENS_SHARE, Bucket.SERIES)
        assertEquals(listOf(100.0, 0.0, 0.0, 0.0), each.map { it.value })
    }
}
