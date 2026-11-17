package se.kjellstrand.markera.series.stats

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto

@OptIn(ExperimentalTime::class)
class FindingsTest {

    private val zone = TimeZone.UTC
    private val day0 = Instant.parse("2026-09-01T10:00:00Z").toEpochMilliseconds()
    private var nextId = 1L

    private fun Random.gauss(): Double {
        val u = 1.0 - nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * nextDouble())
    }

    private fun ringOf(x: Double, y: Double) = maxOf(0, 10 - (hypot(x, y) / 25).toInt())

    /** One series on [day] (index [n] within it) from these hits, rings from the distance. */
    private fun series(
        day: Int,
        n: Int,
        hits: List<Pair<Double, Double>>,
        caliber: String = "9mm",
        rings: List<Int>? = null,
    ): PlottedSeries {
        val at = Instant.fromEpochMilliseconds(day0 + day * DAY_MS + n * 10 * 60_000L)
        val r = rings ?: hits.map { (x, y) -> ringOf(x, y) }
        val dto = SeriesDto(
            id = nextId++,
            timestamp = at.toString(),
            caliber = caliber,
            holes = r.map { HoleDto(null, null, it, false, null) },
        )
        return PlottedSeries(dto, hits.zip(r).map { (h, ring) -> PlottedHit(h.first, h.second, ring, false) }, 0f, at)
    }

    /**
     * [days] days of [perDay] five-hole series, normal per axis around ([ox], [oy]) (y down),
     * the major axis along [axisDeg] (counter-clockwise from horizontal, y up).
     */
    private fun history(
        seed: Int,
        days: Int = 10,
        perDay: Int = 4,
        sdMajor: Double = 30.0,
        sdMinor: Double = 30.0,
        axisDeg: Double = 0.0,
        ox: (Int) -> Double = { 0.0 },
        oy: (Int) -> Double = { 0.0 },
        firstDay: Int = 0,
    ): List<PlottedSeries> {
        val rnd = Random(seed)
        val a = axisDeg * PI / 180
        return (0 until days).flatMap { d ->
            (0 until perDay).map { n ->
                series(firstDay + d, n, List(5) {
                    val u = rnd.gauss() * sdMajor
                    val v = rnd.gauss() * sdMinor
                    // y up for the axis, flipped to y down for the target.
                    Pair(ox(d) + u * cos(a) - v * sin(a), oy(d) - (u * sin(a) + v * cos(a)))
                })
            }
        }
    }

    private inline fun <reified T : Finding> List<Finding>.one(): T? = filterIsInstance<T>().singleOrNull()

    @Test
    fun noDataGivesNoFinding() {
        assertTrue(emptyList<PlottedSeries>().findings(zone).isEmpty())
        assertTrue(listOf(series(0, 0, listOf(50.0 to 0.0, 60.0 to 0.0, 55.0 to 5.0, 52.0 to -3.0, 58.0 to 2.0)))
            .findings(zone).isEmpty())
        // Two series far off centre: 10 hits are under every group finding's minimum.
        assertTrue(history(1, days = 2, perDay = 1, ox = { 60.0 }).findings(zone).isEmpty())
        // Series without positioned holes.
        assertTrue(List(12) { series(it, 0, emptyList(), rings = emptyList()) }.findings(zone).isEmpty())
    }

    @Test
    fun aRoundCentredShooterStaysQuiet() {
        var noisy = 0
        for (seed in 1..200) {
            val found = history(seed).findings(zone)
            val group = found.filter { it !is Finding.LowSeries && it !is Finding.LowDay }
            if (group.isNotEmpty()) noisy++
        }
        // 7 of 200 measured (see the Findings KDoc); a looser bar soon pushes it past 10.
        assertTrue(noisy <= 10, "group findings on $noisy of 200 round histories")
    }

    @Test
    fun aSmallOffsetUnderTenMmIsNoFinding() {
        // 400 hits make the SE tiny; 6 mm is still under the 10 mm floor.
        assertNull(history(3, days = 20, perDay = 4, ox = { 6.0 }).findings(zone).one<Finding.OffsetGroup>())
    }

    @Test
    fun theSameOffsetEveryDayIsStable() {
        val found = history(4, days = 5, perDay = 3, sdMajor = 20.0, sdMinor = 20.0, ox = { 30.0 }).findings(zone)
        val offset = assertNotNull(found.one<Finding.OffsetGroup>())
        assertTrue(abs(offset.bearingDeg - 90) < 20, "bearing ${offset.bearingDeg}")
        assertTrue(offset.distanceMm in 20.0..40.0)
        assertEquals(5, offset.daysChecked)
        assertEquals(5, offset.daysAgreeing)
        assertTrue(offset.stable)
    }

    @Test
    fun anOffsetThatChangesByDayIsNotStable() {
        // Right on days 0-1, low on days 2-4: overall low-right, but only the low days agree with it.
        val found = history(
            5, days = 5, perDay = 3, sdMajor = 10.0, sdMinor = 10.0,
            ox = { if (it < 2) 60.0 else 0.0 },
            oy = { if (it < 2) 0.0 else 60.0 },
        ).findings(zone)
        val offset = assertNotNull(found.one<Finding.OffsetGroup>())
        assertEquals(5, offset.daysChecked)
        assertEquals(3, offset.daysAgreeing)
        assertFalse(offset.stable)
        // Round groups whose mean point moves are not a stretched group.
        assertNull(found.one<Finding.StretchedGroup>())
    }

    @Test
    fun aMeanPointWanderingByDayIsNoOffset() {
        // Tight days whose mean point jumps 70 mm from day to day: net 12 mm right, over 3 per-hit
        // SEs (300 hits) but well inside 3 day-level SEs, so it must not read as an offset.
        val list = history(22, days = 20, perDay = 3, sdMajor = 10.0, sdMinor = 10.0,
            ox = { if (it % 2 == 0) 47.0 else -23.0 })
        assertNull(list.findings(zone).one<Finding.OffsetGroup>())
    }

    @Test
    fun anOffsetOnOneDayIsNotStable() {
        val offset = assertNotNull(history(6, days = 1, perDay = 4, sdMajor = 15.0, sdMinor = 15.0, oy = { 40.0 })
            .findings(zone).one<Finding.OffsetGroup>())
        assertTrue(abs(offset.bearingDeg - 180) < 20)
        assertFalse(offset.stable)
    }

    @Test
    fun aVerticalStringIsStretched() {
        val s = assertNotNull(history(7, days = 3, perDay = 4, sdMajor = 40.0, sdMinor = 12.0, axisDeg = 90.0)
            .findings(zone).one<Finding.StretchedGroup>())
        assertEquals(Finding.Orientation.VERTICAL, s.orientation)
        assertTrue(s.elongation > 2.5)
    }

    @Test
    fun aDiagonalAndAHorizontalStretch() {
        val diag = assertNotNull(history(8, days = 3, perDay = 4, sdMajor = 40.0, sdMinor = 12.0, axisDeg = 135.0)
            .findings(zone).one<Finding.StretchedGroup>())
        assertEquals(Finding.Orientation.DIAGONAL, diag.orientation)
        assertTrue(abs(diag.axisDeg - 135) < 15, "axis ${diag.axisDeg}")
        val flat = assertNotNull(history(9, days = 3, perDay = 4, sdMajor = 40.0, sdMinor = 12.0, axisDeg = 0.0)
            .findings(zone).one<Finding.StretchedGroup>())
        assertEquals(Finding.Orientation.HORIZONTAL, flat.orientation)
    }

    @Test
    fun theStretchBarShrinksWithTheHitCount() {
        val t = FindingThresholds()
        assertEquals(1.5, t.stretchBar(81), 1e-9)
        assertTrue(abs(t.stretchBar(20) - (1 + 4.5 / sqrt(20.0))) < 1e-9)
        assertEquals(1.5, t.stretchBar(400), 1e-9)
    }

    @Test
    fun aStretchUnderTheBarAtTwentyHitsIsNoFinding() {
        // A 5 x 4 grid, elongation about 1.75: common by chance at 20 hits, so it says nothing.
        val grid = (0 until 4).map { j ->
            (0 until 5).map { c -> Pair((c - 2) * 17.0, (((c + j) % 4) - 1.5) * 12.3) }
        }
        val s = grid.flatten().map { PlottedHit(it.first, it.second, 9, false) }.groupShape()!!
        assertTrue(s.elongation in 1.5..2.0, "elongation ${s.elongation}")
        val list = grid.mapIndexed { i, h -> series(i, 0, h) }
        assertNull(list.findings(zone).one<Finding.StretchedGroup>())
    }

    @Test
    fun oneHoleGettingAwayInManySeriesIsFliers() {
        val rnd = Random(10)
        val list = (0 until 20).map { i ->
            val hits = MutableList(5) { Pair(rnd.gauss() * 8, rnd.gauss() * 8) }
            if (i % 2 == 0) {
                val a = rnd.nextDouble() * 2 * PI
                hits[0] = Pair(70 * cos(a), 70 * sin(a))
            }
            series(i / 4, i % 4, hits)
        }
        val f = assertNotNull(list.findings(zone).one<Finding.Fliers>())
        assertEquals(20, f.seriesCount)
        assertTrue(f.seriesWithFliers >= 9, "with fliers ${f.seriesWithFliers}")
        assertTrue(f.flierMeanDistanceMm > 5 * f.coreRadialSdMm)
    }

    @Test
    fun fewSeriesWithFliersIsNoFinding() {
        val rnd = Random(11)
        val list = (0 until 20).map { i ->
            val hits = MutableList(5) { Pair(rnd.gauss() * 8, rnd.gauss() * 8) }
            if (i < 2) hits[0] = Pair(70.0, 0.0)
            series(i / 4, i % 4, hits)
        }
        assertNull(list.findings(zone).one<Finding.Fliers>())
        // Under eight series nothing is judged at all.
        assertNull(list.take(7).findings(zone).one<Finding.Fliers>())
    }

    @Test
    fun aWideDayAfterTightOnesIsAWideRoundGroup() {
        val earlier = history(12, days = 6, perDay = 3, sdMajor = 12.0, sdMinor = 12.0)
        val wide = history(13, days = 1, perDay = 4, sdMajor = 35.0, sdMinor = 35.0, firstDay = 6)
        val w = assertNotNull((earlier + wide).findings(zone).one<Finding.WideRoundGroup>())
        assertEquals(4, w.recentSeriesCount)
        assertEquals(18, w.earlierSeriesCount)
        assertTrue(w.ratio > 2)
        // The same day with too little earlier history says nothing.
        assertNull((earlier.take(5) + wide).findings(zone).one<Finding.WideRoundGroup>())
        // A day as wide as before is no finding.
        val same = history(14, days = 1, perDay = 4, sdMajor = 12.0, sdMinor = 12.0, firstDay = 6)
        assertNull((earlier + same).findings(zone).one<Finding.WideRoundGroup>())
    }

    private fun totals(day: Int, n: Int, total: Int): PlottedSeries {
        val rings = List(5) { total / 5 + if (it < total % 5) 1 else 0 }
        return series(day, n, List(5) { Pair(0.0, 0.0) }, rings = rings)
    }

    @Test
    fun aLowNewestSeriesIsLowSeries() {
        val hist = List(20) { totals(it, 0, if (it % 2 == 0) 44 else 46) }
        val low = assertNotNull((hist + totals(20, 0, 38)).findings(zone).one<Finding.LowSeries>())
        assertEquals(38, low.total)
        assertEquals(45.0, low.historyMeanTotal, 1e-9)
        assertEquals(20, low.historyCount)
        assertTrue(low.z < -1.5)
        assertNull((hist + totals(20, 0, 44)).findings(zone).one<Finding.LowSeries>())
    }

    @Test
    fun lowSeriesUsesTheLastThirtyAndNeedsTen() {
        val old = List(30) { totals(it, 0, 30) }
        val recent = List(30) { totals(30 + it, 0, if (it % 2 == 0) 44 else 46) }
        val low = assertNotNull((old + recent + totals(60, 0, 41)).findings(zone).one<Finding.LowSeries>())
        assertEquals(30, low.historyCount)
        assertEquals(45.0, low.historyMeanTotal, 1e-9)
        val nine = List(9) { totals(it, 0, if (it % 2 == 0) 44 else 46) }
        assertTrue((nine + totals(9, 0, 20)).findings(zone).none { it is Finding.LowSeries || it is Finding.LowDay })
    }

    @Test
    fun aLowDayReplacesItsLowSeries() {
        val hist = List(20) { totals(it, 0, if (it % 2 == 0) 44 else 46) }
        val day = listOf(totals(20, 0, 40), totals(20, 1, 41), totals(20, 2, 39))
        val found = (hist + day).findings(zone)
        val low = assertNotNull(found.one<Finding.LowDay>())
        assertEquals(3, low.seriesCount)
        assertEquals(40.0, low.meanTotal, 1e-9)
        assertNull(found.one<Finding.LowSeries>())
    }

    @Test
    fun scoresRisingOverWeeksAreAnImprovingTrend() {
        val rnd = Random(15)
        val list = List(24) { totals(it * 2, 0, (36 + it / 2 + rnd.nextInt(-1, 2)).coerceAtMost(50)) }
        val t = assertNotNull(list.findings(zone).one<Finding.Trend>())
        assertTrue(t.improving)
        assertTrue(t.pointsPerWeek in 1.0..2.5, "per week ${t.pointsPerWeek}")
        assertEquals(46.0, t.spanDays, 1e-9)
        val down = List(24) { totals(it * 2, 0, 48 - it / 2 + rnd.nextInt(-1, 2)) }
        assertFalse(assertNotNull(down.findings(zone).one<Finding.Trend>()).improving)
    }

    @Test
    fun aFlatOrShortHistoryHasNoTrend() {
        val rnd = Random(16)
        assertNull(List(24) { totals(it * 2, 0, 45 + rnd.nextInt(-3, 4)) }.findings(zone).one<Finding.Trend>())
        // A clear rise over 9 days is too short to call a trend over weeks.
        assertNull(List(20) { totals(it / 2, it % 2, 30 + it) }.findings(zone).one<Finding.Trend>())
    }

    @Test
    fun findingsComeStrongestFirstAndSplitByCaliber() {
        val nine = history(17, days = 5, perDay = 4, sdMajor = 40.0, sdMinor = 12.0, axisDeg = 90.0, ox = { 25.0 })
        val found = nine.findings(zone)
        assertTrue(found.size >= 2, "found $found")
        assertEquals(found.sortedByDescending { it.confidence }, found)
        assertTrue(found.all { it.confidence > 0 && it.confidence <= 1 })

        val lr = history(18, days = 2, perDay = 1).map { it.copy(series = it.series.copy(caliber = "22lr")) }
        val none = history(19, days = 3, perDay = 4, ox = { 40.0 }).map { it.copy(series = it.series.copy(caliber = "-")) }
        val split = (nine + lr + none).findingsByCaliber(zone)
        assertEquals(found, split.getValue(Caliber.fromLabel("9mm")))
        assertEquals(setOf(Caliber.fromLabel("22lr"), Caliber.fromLabel("9mm")), split.keys)
    }

    /** One day of series whose totals are [totals], 10 minutes apart (or [gapMin] apart), in order. */
    private fun day(day: Int, totals: List<Int>, gapMin: Int = 10): List<PlottedSeries> =
        totals.mapIndexed { n, total ->
            val rings = List(5) { total / 5 + if (it < total % 5) 1 else 0 }
            val at = kotlin.time.Instant.fromEpochMilliseconds(day0 + day * DAY_MS + n * gapMin * 60_000L)
            val dto = SeriesDto(
                id = nextId++,
                timestamp = at.toString(),
                caliber = "9mm",
                holes = rings.map { HoleDto(null, null, it, false, null) },
            )
            PlottedSeries(dto, rings.map { PlottedHit(0.0, 0.0, it, false) }, 0f, at)
        }

    private fun session(vararg days: List<Int>, gapMin: Int = 10) =
        days.flatMapIndexed { i, t -> day(i * 2, t, gapMin) }.findings(zone).one<Finding.SessionTrend>()

    @Test
    fun scoresFallingThroughSeveralDaysAreFatigue() {
        val f = assertNotNull(session(listOf(47, 46, 43, 42), listOf(46, 47, 42, 44), listOf(48, 45, 44, 41)))
        assertTrue(f.falling)
        assertEquals(3, f.daysCounted)
        assertEquals(3, f.daysChecked)
        assertEquals(46.5, f.firstHalfMean, 0.6)
        assertEquals(43.0, f.laterHalfMean, 0.6)
    }

    @Test
    fun aSlowStartThatImprovesIsAWarmUp() {
        val f = assertNotNull(session(listOf(41, 43, 46, 47), listOf(42, 42, 47, 46), listOf(40, 44, 46, 48), listOf(44, 43, 45, 47)))
        assertFalse(f.falling)
        assertEquals(4, f.daysCounted)
    }

    @Test
    fun flatDaysHaveNoSessionTrend() {
        assertNull(session(listOf(45, 46, 45, 46), listOf(46, 45, 46, 45), listOf(45, 45, 46, 46)))
        // A one point difference between the halves is under the 2 point bar.
        assertNull(session(listOf(46, 46, 45, 45), listOf(47, 46, 45, 46), listOf(46, 47, 46, 45)))
    }

    @Test
    fun daysWithTooFewSeriesDoNotCount() {
        // Three series a day, however clear the fall.
        assertNull(session(listOf(48, 45, 40), listOf(47, 44, 41), listOf(48, 46, 40), listOf(47, 45, 39)))
        // Only two long days.
        assertNull(session(listOf(47, 46, 43, 42), listOf(46, 47, 42, 44), listOf(46, 46)))
    }

    @Test
    fun aDropThatDoesNotRepeatIsNotAFinding() {
        // Two falling days, one flat, one rising: 2 of 4 is not two thirds and not three days.
        assertNull(session(listOf(48, 47, 43, 42), listOf(47, 46, 42, 43), listOf(45, 46, 45, 46), listOf(41, 42, 47, 48)))
    }

    @Test
    fun aMiddleSeriesOfAnOddDayIsLeftOut() {
        // First two against last two of five: the 30 in the middle does not matter.
        val f = assertNotNull(session(listOf(48, 47, 30, 43, 42), listOf(47, 48, 30, 42, 43), listOf(48, 46, 30, 42, 41)))
        assertTrue(f.falling)
        assertEquals(47.5, f.firstHalfMean, 0.6)
    }

    @Test
    fun seriesStampedWithinSecondsAreNoSession() {
        // A batch import stamped "now": the order is the import order, not a session.
        assertNull(session(listOf(47, 46, 43, 42), listOf(46, 47, 42, 44), listOf(48, 45, 44, 41), gapMin = 1))
    }

    @Test
    fun aMorningAndAnEveningBlockAreNotOneSession() {
        // 3 hours between the second and third series of every day.
        val days = (0 until 3).flatMap { d ->
            day(d * 2, listOf(47, 47)) + day(d * 2, listOf(42, 42)).map {
                val at = kotlin.time.Instant.fromEpochMilliseconds(it.at.toEpochMilliseconds() + 3 * 3_600_000L)
                it.copy(at = at)
            }
        }
        assertNull(days.findings(zone).one<Finding.SessionTrend>())
    }

    @Test
    fun theSessionTrendHasNoMarkOnTheTargetAndSortsLikeTheRest() {
        val list = (0 until 3).flatMap { day(it * 2, listOf(47, 47, 42, 42)) }
        val found = list.findings(zone)
        val f = assertNotNull(found.one<Finding.SessionTrend>())
        assertEquals(InsightMark.NONE, f.mark())
        assertEquals(found.sortedByDescending { it.confidence }, found)
    }

    @Test
    fun roundShootersGetNoSessionTrendByChance() {
        var hits = 0
        for (seed in 1..300) {
            val rnd = Random(seed)
            val days = List(5) { d -> day(d * 2, List(4) { (45 + rnd.gauss() * 2.5).toInt().coerceAtMost(50) }) }
            if (days.flatten().findings(zone).one<Finding.SessionTrend>() != null) hits++
        }
        assertTrue(hits <= 12, "chance findings $hits of 300")
    }
}
