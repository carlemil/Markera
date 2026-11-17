package se.kjellstrand.markera.series.stats

import kotlin.math.PI
import kotlin.math.cos
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
class InsightsTest {
    private val zone = TimeZone.UTC
    private val day0 = Instant.parse("2026-09-01T10:00:00Z").toEpochMilliseconds()
    private var nextId = 1L

    private fun Random.gauss(): Double {
        val u = 1.0 - nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * nextDouble())
    }

    private fun series(day: Int, n: Int, hits: List<Pair<Double, Double>>, caliber: String = "9mm"): PlottedSeries {
        val at = Instant.fromEpochMilliseconds(day0 + day * DAY_MS + n * 10 * 60_000L)
        val dto = SeriesDto(
            id = nextId++,
            timestamp = at.toString(),
            caliber = caliber,
            holes = hits.map { HoleDto(null, null, 8, false, null) },
        )
        return PlottedSeries(dto, hits.map { PlottedHit(it.first, it.second, 8, false) }, 0f, at)
    }

    /** [count] five-hole series, four a day, round normal groups of [sd] mm about ([ox], [oy]). */
    private fun group(seed: Int, count: Int, ox: Double = 0.0, oy: Double = 0.0, sd: Double = 30.0, caliber: String = "9mm") =
        Random(seed).let { rnd ->
            (0 until count).map { i -> series(i / 4, i % 4, List(5) { Pair(ox + rnd.gauss() * sd, oy + rnd.gauss() * sd) }, caliber) }
        }

    @Test
    fun underFifteenShotsSaysHowManyAreNeeded() {
        val insights = group(1, 2, ox = 80.0).insights(zone)
        val section = insights.sections.single()
        assertEquals(15, insights.neededHits)
        assertEquals(10, section.hitCount)
        assertFalse(section.enoughData)
        assertTrue(section.findings.isEmpty())
        // The picture still has its numbers.
        assertNotNull(section.ellipse)
        assertNotNull(section.stats)
    }

    @Test
    fun aRoundCentredGroupHasNothingToSay() {
        // Seed chosen so this round group trips none of the findings' chance rates.
        val section = (1..40).map { group(it, 8) }.first { it.insights(zone).sections.single().findings.isEmpty() }
            .insights(zone).sections.single()
        assertTrue(section.enoughData)
        assertEquals(40, section.hitCount)
        assertEquals(8, section.stats?.seriesCount)
    }

    @Test
    fun anOffsetGroupPointsTheArrowAndTheEllipseAtTheMeanPoint() {
        val section = group(2, 12, ox = 40.0, sd = 15.0).insights(zone).sections.single()
        val offset = assertNotNull(section.findings.filterIsInstance<Finding.OffsetGroup>().singleOrNull())
        assertEquals(InsightMark.ARROW, offset.mark())
        val e = assertNotNull(section.ellipse)
        val g = assertNotNull(section.shape)
        assertEquals(g.mpiXMm, e.cxMm, 1e-9)
        assertEquals(g.mpiYMm, e.cyMm, 1e-9)
        assertEquals(ELLIPSE_SDS * g.majorSdMm, e.semiMajorMm, 1e-9)
        assertTrue(section.fliers.isEmpty())
    }

    @Test
    fun aStableOffsetIsJudgedOnlyOnSeriesAfterTheSightsWereAdjusted() {
        // Three days 40 mm right, then the sights are turned and three centred days follow.
        val before = group(2, 12, ox = 40.0, sd = 15.0)
        val after = group(7, 12, sd = 15.0).map { s ->
            val at = Instant.fromEpochMilliseconds(s.at.toEpochMilliseconds() + 5 * DAY_MS)
            s.copy(at = at, series = s.series.copy(timestamp = at.toString()))
        }
        val list = before + after
        val turnedAt = Instant.fromEpochMilliseconds(day0 + 4 * DAY_MS)

        val stable = assertNotNull(before.insights(zone).sections.single().findings.filterIsInstance<Finding.OffsetGroup>().singleOrNull())
        assertTrue(stable.stable)

        val sights = mapOf("9mm" to SightSetup(mmPerClick = 6.0, adjustedAtMs = turnedAt.toEpochMilliseconds()))
        val section = list.insights(zone, sights = sights).sections.single()
        assertEquals(turnedAt, section.adjustedAt)
        assertEquals(sights["9mm"], section.sight)
        assertEquals(60, section.offsetHitCount)
        assertEquals(120, section.hitCount)
        // Exactly the offset the later series alone give, and the arrow is theirs.
        assertEquals(
            after.findings(zone).filterIsInstance<Finding.OffsetGroup>(),
            section.findings.filterIsInstance<Finding.OffsetGroup>(),
        )
        assertTrue(section.findings.filterIsInstance<Finding.OffsetGroup>().none { it.stable })
        val arrow = assertNotNull(section.arrow)
        val afterShape = assertNotNull(after.flatMap { it.hits }.groupShape())
        assertEquals(afterShape.mpiXMm, arrow.xMm, 1e-9)
        assertEquals(afterShape.mpiYMm, arrow.yMm, 1e-9)
        // The rest still sees every series: the ellipse is the whole group's.
        assertEquals(assertNotNull(list.flatMap { it.hits }.groupShape()).mpiXMm, assertNotNull(section.ellipse).cxMm, 1e-9)
    }

    @Test
    fun anAdjustmentAfterTheLastSeriesLeavesNoOffsetAndNoArrow() {
        val list = group(2, 12, ox = 40.0, sd = 15.0)
        val late = list.maxOf { it.at.toEpochMilliseconds() } + 1
        val section = list.insights(zone, sights = mapOf("9mm" to SightSetup(adjustedAtMs = late))).sections.single()
        assertTrue(section.enoughData)
        assertTrue(section.findings.none { it is Finding.OffsetGroup })
        assertEquals(0, section.offsetHitCount)
        assertNull(section.arrow)
        // Another caliber's adjustment changes nothing here.
        val other = list.insights(zone, sights = mapOf("22lr" to SightSetup(adjustedAtMs = late))).sections.single()
        assertTrue(other.findings.any { it is Finding.OffsetGroup })
        assertNull(other.adjustedAt)
        assertEquals(60, other.offsetHitCount)
    }

    @Test
    fun aStretchedGroupDrawsTheFindingsOwnEllipse() {
        val rnd = Random(3)
        val list = (0 until 12).map { i -> series(i / 4, i % 4, List(5) { Pair(rnd.gauss() * 8, rnd.gauss() * 40) }) }
        val section = list.insights(zone).sections.single()
        val s = assertNotNull(section.findings.filterIsInstance<Finding.StretchedGroup>().singleOrNull())
        assertEquals(InsightMark.ELLIPSE, s.mark())
        val e = assertNotNull(section.ellipse)
        assertEquals(ELLIPSE_SDS * s.majorSdMm, e.semiMajorMm, 1e-9)
        assertEquals(ELLIPSE_SDS * s.minorSdMm, e.semiMinorMm, 1e-9)
        assertEquals(s.axisDeg, e.axisDeg, 1e-9)
    }

    @Test
    fun severalCalibersAreSplitInCaliberOrderAndUncaliberedLeftOut() {
        val list = group(4, 4, caliber = "9mm") + group(5, 4, caliber = "22lr") + group(6, 2, caliber = "")
        val insights = list.insights(zone)
        assertEquals(listOf(Caliber.LR22, Caliber.MM9), insights.sections.map { it.caliber })
        assertEquals(2, insights.uncaliberedSeries)
        assertTrue(insights.sections.all { it.hitCount == 20 })
    }

    @Test
    fun flierHitsAreThePlantedOnes() {
        val rnd = Random(10)
        val planted = mutableListOf<Pair<Double, Double>>()
        val list = (0 until 20).map { i ->
            val hits = MutableList(5) { Pair(rnd.gauss() * 8, rnd.gauss() * 8) }
            if (i % 2 == 0) {
                val a = rnd.nextDouble() * 2 * PI
                hits[0] = Pair(70 * cos(a), 70 * sin(a)).also { planted += it }
            }
            series(i / 4, i % 4, hits)
        }
        val section = list.insights(zone).sections.single()
        val f = assertNotNull(section.findings.filterIsInstance<Finding.Fliers>().singleOrNull())
        assertEquals(InsightMark.FLIERS, f.mark())
        assertEquals(f.flierCount, section.fliers.size)
        // The very hits of the series, every planted flier among them.
        val all = list.flatMap { it.hits }
        assertTrue(section.fliers.all { h -> all.any { it === h } })
        assertTrue(planted.all { p -> section.fliers.any { it.xMm == p.first && it.yMm == p.second } })
    }

    @Test
    fun noFliersFindingRingsNoHoles() {
        // Seven series with a hole far out: under the eight the finding needs, so nothing is ringed.
        val list = (0 until 7).map { i -> series(0, i, listOf(70.0 to 0.0, 1.0 to 1.0, -1.0 to 2.0, 2.0 to -1.0, 0.0 to 0.0)) }
        assertTrue(list.flierHits().isEmpty())
        assertTrue(list.insights(zone).sections.single().fliers.isEmpty())
        assertNull(emptyList<PlottedSeries>().insights(zone).sections.firstOrNull())
    }
}
