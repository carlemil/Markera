package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(ExperimentalTime::class)
class SeriesStatsTest {

    /** Unrotated, unforeshortened: centre (500,500), semiMajor 100 px = 100 mm ⇒ 1 px = 1 mm. */
    private val geometry = GeometryDto(
        centreX = 500.0,
        centreY = 500.0,
        ringCx = 500.0,
        ringCy = 500.0,
        ringSemiMajor = 100.0,
        ringSemiMinor = 100.0,
        ringRotationRad = 0.0,
    )

    private fun hole(x: Double?, y: Double?, ring: Int, innerTen: Boolean = false) =
        HoleDto(x = x, y = y, ring = ring, innerTen = innerTen, distanceMm = null)

    private fun series(
        id: Long,
        timestamp: String = "2026-09-01T10:00:00Z",
        caliber: String = "9mm",
        holes: List<HoleDto>,
        geometry: GeometryDto? = this.geometry,
    ) = SeriesDto(id = id, timestamp = timestamp, caliber = caliber, holes = holes, geometry = geometry)

    /** (500,500),(530,500): a centred X and a 9 thirty mm to the right. Total 19. */
    private val seriesA = series(
        id = 1,
        timestamp = "2026-09-01T10:00:00Z",
        holes = listOf(hole(500.0, 500.0, 10, innerTen = true), hole(530.0, 500.0, 9)),
    )

    /** (500,540),(500,500),(500,470): 40 mm down, centred, 30 mm up. Total 27. */
    private val seriesB = series(
        id = 2,
        timestamp = "2026-09-02T10:00:00Z",
        holes = listOf(hole(500.0, 540.0, 8), hole(500.0, 500.0, 10), hole(500.0, 470.0, 9)),
    )

    private fun fiveHoles(vararg rings: Int) = rings.map { hole(500.0, 500.0, it) }

    @Test
    fun `hole offset keeps the photo orientation and its distance`() {
        val s = series(id = 1, holes = listOf(hole(530.0, 460.0, 9)))
        val hit = listOf(s).plotSeries(StatsFilter(hits = 1)).single().hits.single()
        assertEquals(30.0, hit.xMm, 1e-9)
        assertEquals(-40.0, hit.yMm, 1e-9)
        assertEquals(9, hit.ring)
    }

    @Test
    fun `only series with exactly the wanted hit count are kept`() {
        val plotted = listOf(seriesA, seriesB).plotSeries(StatsFilter(hits = 3))
        assertEquals(listOf(2L), plotted.map { it.series.id })
    }

    @Test
    fun `caliber null keeps everything, a caliber keeps only its own`() {
        val other = series(id = 3, caliber = "22lr", holes = seriesA.holes)
        val all = listOf(seriesA, other).plotSeries(StatsFilter(hits = 2))
        assertEquals(listOf(1L, 3L), all.map { it.series.id })
        val nine = listOf(seriesA, other).plotSeries(StatsFilter(caliber = Caliber.MM9, hits = 2))
        assertEquals(listOf(1L), nine.map { it.series.id })
    }

    @Test
    fun `date window is inclusive at both ends`() {
        val filter = StatsFilter(
            from = Instant.parse("2026-09-01T10:00:00Z"),
            to = Instant.parse("2026-09-02T10:00:00Z"),
            hits = 1,
        )
        val early = series(id = 1, timestamp = "2026-09-01T09:59:59Z", holes = listOf(hole(500.0, 500.0, 10)))
        val onFrom = series(id = 2, timestamp = "2026-09-01T10:00:00Z", holes = listOf(hole(500.0, 500.0, 10)))
        val onTo = series(id = 3, timestamp = "2026-09-02T10:00:00Z", holes = listOf(hole(500.0, 500.0, 10)))
        val late = series(id = 4, timestamp = "2026-09-02T10:00:01Z", holes = listOf(hole(500.0, 500.0, 10)))
        val plotted = listOf(early, onFrom, onTo, late).plotSeries(filter)
        assertEquals(listOf(2L, 3L), plotted.map { it.series.id })
    }

    @Test
    fun `series without geometry, without positions or with a bad timestamp are dropped`() {
        val noGeometry = series(id = 1, holes = listOf(hole(500.0, 500.0, 10)), geometry = null)
        val typedHole = series(id = 2, holes = listOf(hole(null, null, 10)))
        val badStamp = series(id = 3, timestamp = "igår", holes = listOf(hole(500.0, 500.0, 10)))
        assertTrue(listOf(noGeometry, typedHole, badStamp).plotSeries(StatsFilter(hits = 1)).isEmpty())
    }

    @Test
    fun `plotting sorts oldest first and spreads age from 0 to 1`() {
        val holes = listOf(hole(500.0, 500.0, 10))
        val newest = series(id = 1, timestamp = "2026-09-03T10:00:00Z", holes = holes)
        val oldest = series(id = 2, timestamp = "2026-09-01T10:00:00Z", holes = holes)
        val middle = series(id = 3, timestamp = "2026-09-02T10:00:00Z", holes = holes)
        val plotted = listOf(newest, oldest, middle).plotSeries(StatsFilter(hits = 1))
        assertEquals(listOf(2L, 3L, 1L), plotted.map { it.series.id })
        assertEquals(listOf(0f, 0.5f, 1f), plotted.map { it.age })
    }

    @Test
    fun `a lone series has age 0 and an empty selection has no statistics`() {
        assertEquals(0f, listOf(seriesA).plotSeries(StatsFilter(hits = 2)).single().age)
        assertNull(emptyList<PlottedSeries>().statistics())
    }

    @Test
    fun `every statistic over the two hand-placed series`() {
        // A: (0,0) X and (30,0) 9 → total 19. B: (0,40) 8, (0,0) 10, (0,-30) 9 → total 27.
        val plotted = listOf(seriesA).plotSeries(StatsFilter(hits = 2)) +
            listOf(seriesB).plotSeries(StatsFilter(hits = 3))
        val stats = plotted.statistics()!!
        assertEquals(2, stats.seriesCount)
        assertEquals(5, stats.hitCount)
        // distances 0, 30, 40, 0, 30 → 100 / 5
        assertEquals(20.0, stats.meanDistanceMm, 1e-9)
        // A: one pair, 30. B: 40, 70, 30 → 140/3. (30 + 140/3) / 2
        assertEquals((30.0 + 140.0 / 3.0) / 2.0, stats.meanPairwiseMm, 1e-9)
        // largest pair per series: 30 and 70
        assertEquals(50.0, stats.meanGroupSizeMm, 1e-9)
        assertEquals(23.0, stats.meanScore, 1e-9)
        // x: 0+30+0+0+0 = 30; y: 0+0+40+0-30 = 10
        assertEquals(6.0, stats.impactXMm, 1e-9)
        assertEquals(2.0, stats.impactYMm, 1e-9)
        // ring 10 or X: A's centred X and B's centred 10 → 2 of 5
        assertEquals(0.4, stats.tensShare, 1e-9)
        assertEquals(2L to 27, stats.best!!.let { it.first.id to it.second })
        assertEquals(1L to 19, stats.worst!!.let { it.first.id to it.second })
    }

    @Test
    fun `median impact takes the middle hit, or the mean of the middle two`() {
        // Odd: x 0,10,40 → 10; y 0,-30,-10 → -10.
        val odd = series(
            id = 1,
            holes = listOf(hole(500.0, 500.0, 10), hole(510.0, 470.0, 9), hole(540.0, 490.0, 8)),
        )
        val oddStats = listOf(odd).plotSeries(StatsFilter(hits = 3)).statistics()!!
        assertEquals(10.0, oddStats.medianXMm, 1e-9)
        assertEquals(-10.0, oddStats.medianYMm, 1e-9)

        // Even: x 0,10,30,40 → 20; y 0,-10,-30,-40 → -20.
        val even = series(
            id = 2,
            holes = listOf(
                hole(500.0, 500.0, 10),
                hole(510.0, 490.0, 9),
                hole(530.0, 470.0, 8),
                hole(540.0, 460.0, 7),
            ),
        )
        val evenStats = listOf(even).plotSeries(StatsFilter(hits = 4)).statistics()!!
        assertEquals(20.0, evenStats.medianXMm, 1e-9)
        assertEquals(-20.0, evenStats.medianYMm, 1e-9)
    }

    @Test
    fun `equal totals hand best and worst to the earliest series`() {
        val holes = fiveHoles(10, 9, 9, 8, 8)
        val early = series(id = 1, timestamp = "2026-09-01T10:00:00Z", holes = holes)
        val late = series(id = 2, timestamp = "2026-09-02T10:00:00Z", holes = holes)
        val stats = listOf(late, early).plotSeries(StatsFilter(hits = 5)).statistics()!!
        assertEquals(1L, stats.best!!.first.id)
        assertEquals(1L, stats.worst!!.first.id)
    }

    @Test
    fun `date presets give an open-ended window back from now`() {
        val now = Instant.parse("2026-09-07T12:00:00Z")
        assertEquals(now - 7.days to null, DatePreset.WEEK.range(now))
        assertEquals(now - 30.days to null, DatePreset.MONTH.range(now))
        assertEquals(now - 365.days to null, DatePreset.YEAR.range(now))
        assertEquals(null to null, DatePreset.ALL.range(now))
        assertEquals(null to null, DatePreset.CUSTOM.range(now))
    }
}
