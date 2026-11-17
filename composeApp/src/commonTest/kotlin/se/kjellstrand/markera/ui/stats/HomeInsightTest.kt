package se.kjellstrand.markera.ui.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import se.kjellstrand.markera.res.Res
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.series.Caliber
import se.kjellstrand.markera.series.GeometryDto
import se.kjellstrand.markera.series.HoleDto
import se.kjellstrand.markera.series.SeriesDto
import se.kjellstrand.markera.series.stats.Finding
import se.kjellstrand.markera.series.stats.SightSetup
import se.kjellstrand.markera.series.stats.StatsFilter
import se.kjellstrand.markera.series.stats.insights
import se.kjellstrand.markera.series.stats.plotSeries
import se.kjellstrand.markera.ui.settings.ShootingHand

@OptIn(ExperimentalTime::class)
class HomeInsightTest {
    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private val today = LocalDate(2026, 11, 17)

    /** Unrotated and square: centre (500, 500), the 6/7 edge 100 px out, so 1 px = 1 mm. */
    private val geometry = GeometryDto(500.0, 500.0, 500.0, 500.0, 100.0, 100.0, 0.0)

    /** A round five-hole pattern, 20 mm across. */
    private val round = listOf(0.0 to 0.0, 12.0 to -8.0, -10.0 to 10.0, 8.0 to 14.0, -12.0 to -6.0)

    private var nextId = 1L

    private fun series(
        ago: Duration,
        caliber: String = "22lr",
        dx: Double = 0.0,
        rings: List<Int> = List(5) { 8 },
    ) = SeriesDto(
        id = nextId++,
        timestamp = (now - ago).toString(),
        caliber = caliber,
        holes = round.mapIndexed { i, (x, y) -> HoleDto(500.0 + dx + x, 500.0 + y, rings[i], false, null) },
        geometry = geometry,
    )

    /** Four series a day apart, grouped [dx] mm right of the centre: 20 shots with an offset. */
    private fun offsetWeek(caliber: String = "22lr", dx: Double = 45.0, count: Int = 4) =
        (0 until count).map { series((it * 24 + 1).hours, caliber, dx + it) }

    @Test
    fun anOffsetThisWeekIsTheCardAndOpensThatWeek() {
        val shown = offsetWeek().homeInsights(now, zone).firstShown(emptyList())!!
        assertTrue(shown.finding is Finding.OffsetGroup)
        assertEquals(Caliber.LR22, shown.caliber)
        assertEquals(InsightsDay(today, Caliber.LR22, from = LocalDate(2026, 11, 11)), shown.day)
        assertTrue(shown.key.startsWith("offset|22lr|"))
    }

    @Test
    fun theOpenedPeriodShowsTheSameFinding() {
        val all = offsetWeek()
        val shown = all.homeInsights(now, zone).first()
        val (from, to) = shown.day.window(zone)
        val tab = all.plotSeries(StatsFilter(calibers = setOf(shown.caliber), from = from, to = to)).insights(zone)
        assertTrue(shown.finding in tab.sections.single().findings)
    }

    @Test
    fun anOffsetTheSightsWereAdjustedForSinceGivesNoCard() {
        val week = offsetWeek()
        val sights = mapOf("22lr" to SightSetup(adjustedAtMs = (now - 30.minutes).toEpochMilliseconds()))
        assertTrue(week.homeInsights(now, zone, sights).none { it.finding is Finding.OffsetGroup })
        // Another caliber's adjustment leaves it.
        val other = mapOf("9mm" to SightSetup(adjustedAtMs = (now - 30.minutes).toEpochMilliseconds()))
        assertTrue(week.homeInsights(now, zone, other).first().finding is Finding.OffsetGroup)
    }

    @Test
    fun nothingShotThisWeekGivesNoCard() {
        val old = offsetWeek().map { it.copy(timestamp = (Instant.parse(it.timestamp) - 10.days).toString()) }
        assertTrue(old.homeInsights(now, zone).isEmpty())
        assertTrue(emptyList<SeriesDto>().homeInsights(now, zone).isEmpty())
    }

    @Test
    fun aRoundCentredWeekGivesNoCard() {
        assertTrue(offsetWeek(dx = 0.0).homeInsights(now, zone).isEmpty())
    }

    @Test
    fun aLowDayIsJudgedOnTheHistoryAndOpensFromTheCalibersFirstDay() {
        val history = (1..10).map { series((20 + it).days, rings = listOf(9, 9, 9, 9, 8)) }
        val low = (1..3).map { series(it.hours, rings = listOf(7, 6, 6, 6, 6)) }
        val all = history + low
        val shown = all.homeInsights(now, zone).first { it.finding is Finding.LowDay }
        assertEquals(LocalDate(2026, 10, 18), shown.day.from)
        assertEquals(today, shown.day.date)
        val (from, to) = shown.day.window(zone)
        val tab = all.plotSeries(StatsFilter(calibers = setOf(Caliber.LR22), from = from, to = to)).insights(zone)
        assertTrue(shown.finding in tab.sections.single().findings)
    }

    @Test
    fun anImprovementCountsToo() {
        // Twelve centred series over 33 days, each a point or two better than the last.
        val rising = (0 until 12).map { i ->
            val total = 28 + i * 2
            val rings = List(5) { h -> total / 5 + if (h < total % 5) 1 else 0 }
            series((33 - 3 * i).days - 1.hours, rings = rings)
        }
        val shown = rising.homeInsights(now, zone).first { it.finding is Finding.Trend }
        val trend = shown.finding as Finding.Trend
        assertTrue(trend.improving)
        assertEquals(Res.string.home_insight_trend_up, shown.headline(ShootingHand.RIGHT).res)
    }

    @Test
    fun aSessionTrendThisWeekIsJudgedOnTheWeek() {
        // Three days this week, four series 30 minutes apart, the later two about four points lower.
        val totals = listOf(listOf(48, 47, 43, 44), listOf(47, 48, 44, 42), listOf(48, 46, 43, 43))
        val days = totals.flatMapIndexed { d, day ->
            day.mapIndexed { n, total ->
                val rings = List(5) { total / 5 + if (it < total % 5) 1 else 0 }
                series((d + 1).days + 3.hours - (n * 30).minutes, rings = rings)
            }
        }
        val shown = days.homeInsights(now, zone).first { it.finding is Finding.SessionTrend }
        assertEquals(InsightsDay(today, Caliber.LR22, from = LocalDate(2026, 11, 11)), shown.day)
        assertTrue(shown.key.startsWith("session|22lr|"))
        assertEquals(Res.string.home_insight_session_down, shown.headline(ShootingHand.RIGHT).res)
    }

    @Test
    fun theStrongestFindingComesFirst() {
        // 9mm: 30 shots twice as far out as the 20 22lr ones, so its offset is the stronger.
        val all = offsetWeek() + offsetWeek("9mm", dx = 80.0, count = 6)
        val candidates = all.homeInsights(now, zone)
        assertEquals(Caliber.MM9, candidates.first().caliber)
        assertTrue(candidates.zipWithNext().all { (a, b) -> a.finding.confidence >= b.finding.confidence })
        assertTrue(candidates.any { it.caliber == Caliber.LR22 })
    }

    @Test
    fun aDismissedFindingStaysAwayUntilANewSeriesInThatCaliber() {
        val week = offsetWeek()
        val first = week.homeInsights(now, zone).first()
        val dismissed = listOf(first.key)
        // The same data: still the same key, so still hidden.
        assertNull(week.homeInsights(now, zone).firstShown(dismissed))
        // A new 22lr series changes the key and the finding is back.
        val again = (week + series(10.minutes, dx = 44.0)).homeInsights(now, zone).firstShown(dismissed)!!
        assertTrue(again.finding is Finding.OffsetGroup)
        assertNotEquals(first.key, again.key)
    }

    @Test
    fun dismissingOneLetsTheNextShow() {
        val candidates = (offsetWeek() + offsetWeek("9mm", dx = 80.0, count = 6)).homeInsights(now, zone)
        val next = candidates.firstShown(listOf(candidates.first().key))
        assertEquals(candidates[1], next)
    }

    @Test
    fun aNewSeriesInAnotherCaliberKeepsTheKey() {
        val week = offsetWeek()
        val key = week.homeInsights(now, zone).first().key
        val withOther = week + series(10.minutes, caliber = "9mm")
        assertEquals(key, withOther.homeInsights(now, zone).first { it.caliber == Caliber.LR22 }.key)
    }

    @Test
    fun dismissedKeysAreCappedAndRoundTrip() {
        var keys = emptyList<String>()
        repeat(MAX_DISMISSED_INSIGHTS + 5) { keys = dismissedWith(keys, "k$it") }
        assertEquals(MAX_DISMISSED_INSIGHTS, keys.size)
        assertEquals("k${MAX_DISMISSED_INSIGHTS + 4}", keys.last())
        keys = dismissedWith(keys, "k10")
        assertEquals("k10", keys.last())
        assertEquals(MAX_DISMISSED_INSIGHTS, keys.size)
        assertEquals(keys, decodeDismissedInsights(encodeDismissedInsights(keys)))
        assertTrue(decodeDismissedInsights(null).isEmpty())
        assertTrue(decodeDismissedInsights("").isEmpty())
    }

    @Test
    fun theOffsetLineNamesCaliberDistanceAndDirection() {
        val shown = offsetWeek().homeInsights(now, zone).first()
        val line = shown.headline(ShootingHand.RIGHT)
        assertEquals(Res.string.home_insight_offset, line.res)
        assertEquals("22lr", line.args[0])
        assertEquals(Res.string.advice_dir_right, line.args[2])
    }
}
