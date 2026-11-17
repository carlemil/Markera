package se.kjellstrand.markera.series.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.settings.ShootingHand

class AdviceTest {

    private val right = ShootingHand.RIGHT
    private val left = ShootingHand.LEFT

    private fun offset(bearing: Double, stable: Boolean = false, mm: Double = 28.0, days: Int = 5) = Finding.OffsetGroup(
        hitCount = 45, mpiXMm = 0.0, mpiYMm = 0.0, distanceMm = mm, bearingDeg = bearing,
        standardErrorMm = 4.0, daysChecked = days, daysAgreeing = if (stable) 4 else 1, stable = stable,
        confidence = 0.8,
    )

    private fun stretched(o: Finding.Orientation) = Finding.StretchedGroup(
        hitCount = 40, elongation = 1.8, majorSdMm = 40.0, minorSdMm = 22.0, axisDeg = 90.0,
        orientation = o, confidence = 0.7,
    )

    private val fliers = Finding.Fliers(10, 4, 5, 50, 20.0, 61.4, 0.6)
    private val wide = Finding.WideRoundGroup(LocalDate(2026, 11, 3), 4, 8, 39.0, 28.0, 1.39, 0.6)
    private val lowSeries = Finding.LowSeries(7, 41, 46.2, 2.5, 20, -2.1, 0.5)
    private val lowDay = Finding.LowDay(LocalDate(2026, 11, 3), 4, 40.5, 46.2, 2.5, 20, -2.3, 0.5)
    private fun trend(up: Boolean) = Finding.Trend(up, if (up) 0.8 else -0.8, if (up) 5.4 else -5.4, 56.0, 20, 4.0, 0.7)

    @Test
    fun sectorsRunClockwiseFromTwelve() {
        assertEquals(0, sector(0.0))
        assertEquals(0, sector(359.0))
        assertEquals(2, sector(90.0))
        assertEquals(4, sector(180.0))
        assertEquals(5, sector(225.0))
        assertEquals(7, sector(315.0))
    }

    @Test
    fun aStableOffsetSaysMoveTheSightsTheOtherWayForBothHands() {
        // Low and left (225 degrees): the impact has to move up and right.
        for (hand in listOf(right, left)) {
            val a = advice(offset(225.0, stable = true), hand)
            assertEquals(Res.string.advice_offset_stable_summary, a.summary.res)
            assertEquals(listOf(28, Res.string.advice_dir_low_left, 45, 4, 5), a.summary.args)
            assertEquals(Res.string.advice_try_sights, a.tryThis.res)
            assertEquals(listOf(28, Res.string.advice_move_up_right), a.tryThis.args)
            assertEquals(listOf(Res.string.advice_cause_sights_1, Res.string.advice_cause_sights_2), a.causes.map { it.res })
        }
        assertEquals(Res.string.advice_move_left, advice(offset(90.0, stable = true), right).tryThis.args[1])
        assertEquals(Res.string.advice_move_down, advice(offset(0.0, stable = true), left).tryThis.args[1])
    }

    @Test
    fun lowOnTheSideAwayFromTheTriggerHandIsJerkingForBothHands() {
        // Right-hander: low-left (225). Left-hander: low-right (135). The numbers say what they are.
        val r = advice(offset(225.0), right)
        val l = advice(offset(135.0), left)
        assertEquals(Res.string.advice_offset_varies_summary, r.summary.res)
        assertEquals(Res.string.advice_dir_low_left, r.summary.args[1])
        assertEquals(Res.string.advice_dir_low_right, l.summary.args[1])
        for (a in listOf(r, l)) {
            assertEquals(Res.string.advice_try_jerk, a.tryThis.res)
            assertEquals(
                listOf(Res.string.advice_cause_jerk_1, Res.string.advice_cause_jerk_2, Res.string.advice_cause_jerk_3),
                a.causes.map { it.res },
            )
        }
    }

    @Test
    fun lowOnTheTriggerHandSideIsNotJerking() {
        val r = advice(offset(135.0), right)
        val l = advice(offset(225.0), left)
        for (a in listOf(r, l)) {
            assertEquals(Res.string.advice_try_push, a.tryThis.res)
            assertNotEquals(Res.string.advice_cause_jerk_1, a.causes.first().res)
        }
    }

    @Test
    fun highAndStraightLowAndSidewaysFollowTheirBearingsForBothHands() {
        for (bearing in listOf(0.0, 45.0, 315.0)) {
            assertEquals(Res.string.advice_try_high, advice(offset(bearing), right).tryThis.res)
        }
        // Mirrored bearings give the same advice for the left hand.
        for (bearing in listOf(0.0, 315.0, 45.0)) {
            assertEquals(Res.string.advice_try_high, advice(offset(bearing), left).tryThis.res)
        }
        for (hand in listOf(right, left)) {
            assertEquals(Res.string.advice_try_low, advice(offset(180.0), hand).tryThis.res)
            assertEquals(Res.string.advice_try_side, advice(offset(90.0), hand).tryThis.res)
            assertEquals(Res.string.advice_try_side, advice(offset(270.0), hand).tryThis.res)
        }
    }

    @Test
    fun everyBearingGetsAtMostThreeCausesAndOneTry() {
        for (hand in listOf(right, left)) {
            for (deg in 0 until 360 step 15) {
                for (stable in listOf(true, false)) {
                    val a = advice(offset(deg.toDouble(), stable), hand)
                    assertTrue(a.causes.size in 1..3, "bearing $deg")
                    assertEquals(null, a.note)
                }
            }
        }
    }

    @Test
    fun stretchedGroupsGetTheirOrientationText() {
        val v = advice(stretched(Finding.Orientation.VERTICAL), right)
        assertEquals(Res.string.advice_stretch_vertical_summary, v.summary.res)
        assertEquals(listOf(80, 40), v.summary.args)
        assertEquals(Res.string.advice_cause_vertical_1, v.causes.first().res)
        assertEquals(Res.string.advice_stretch_horizontal_summary, advice(stretched(Finding.Orientation.HORIZONTAL), right).summary.res)
        assertEquals(Res.string.advice_stretch_diagonal_summary, advice(stretched(Finding.Orientation.DIAGONAL), right).summary.res)
        for (o in Finding.Orientation.entries) {
            assertEquals(advice(stretched(o), right), advice(stretched(o), left))
        }
    }

    @Test
    fun fliersAndWideGroupsKeepTheirNumbers() {
        val f = advice(fliers, right)
        assertEquals(Res.string.advice_fliers_summary, f.summary.res)
        assertEquals(listOf(4, 10, 61), f.summary.args)
        assertEquals(f, advice(fliers, left))
        val w = advice(wide, right)
        assertEquals(Res.string.advice_wide_summary, w.summary.res)
        assertEquals(listOf(LocalDate(2026, 11, 3), 39, 39, 28), w.summary.args)
        assertEquals(w, advice(wide, left))
    }

    @Test
    fun lowResultsPointAtAFindingThatMayExplainThem() {
        val plain = advice(lowSeries, right)
        assertEquals(Res.string.advice_low_series_summary, plain.summary.res)
        assertEquals(listOf(41, 46, 20), plain.summary.args)
        assertEquals(Res.string.advice_try_low_plain, plain.tryThis.res)
        assertNull(plain.note)
        assertTrue(plain.causes.isEmpty())

        val withOffset = advice(lowSeries, left, listOf(offset(225.0), lowSeries))
        assertEquals(Res.string.advice_low_note_offset, withOffset.note?.res)
        assertEquals(Res.string.advice_try_low_explained, withOffset.tryThis.res)

        val day = advice(lowDay, right, listOf(fliers))
        assertEquals(Res.string.advice_low_day_summary, day.summary.res)
        assertEquals(listOf(LocalDate(2026, 11, 3), 4, 41, 46), day.summary.args)
        assertEquals(Res.string.advice_low_note_fliers, day.note?.res)
        assertEquals(Res.string.advice_low_note_wide, advice(lowDay, left, listOf(wide)).note?.res)
        assertEquals(Res.string.advice_low_note_stretched, advice(lowDay, left, listOf(stretched(Finding.Orientation.VERTICAL))).note?.res)
        // A trend alone does not explain a low result.
        assertNull(advice(lowDay, right, listOf(trend(false))).note)
    }

    @Test
    fun trendsGetALineBothWays() {
        for (hand in listOf(right, left)) {
            val up = advice(trend(true), hand)
            assertEquals(Res.string.advice_trend_up_summary, up.summary.res)
            assertEquals(listOf(5, 20, 8), up.summary.args)
            assertTrue(up.causes.isEmpty())
            val down = advice(trend(false), hand)
            assertEquals(Res.string.advice_trend_down_summary, down.summary.res)
            assertEquals(3, down.causes.size)
            assertEquals(Res.string.advice_try_trend_down, down.tryThis.res)
        }
    }

    @Test
    fun adviceForKeepsTheOrderOfTheFindings() {
        val findings = listOf(lowSeries, offset(225.0), fliers)
        val all = adviceFor(findings, right)
        assertEquals(findings, all.map { it.finding })
        assertEquals(Res.string.advice_low_note_offset, all[0].note?.res)
    }

    @Test
    fun eachSectorPicksItsPatternForBothHands() {
        // Right hand, sectors 0..7 clockwise from 12 o clock; the left hand sees the mirror image.
        val expected = listOf(
            Res.string.advice_try_high, Res.string.advice_try_high, Res.string.advice_try_side,
            Res.string.advice_try_push, Res.string.advice_try_low, Res.string.advice_try_jerk,
            Res.string.advice_try_side, Res.string.advice_try_high,
        )
        for (s in 0..7) {
            assertEquals(expected[s], advice(offset(s * 45.0), right).tryThis.res, "right $s")
            assertEquals(expected[s], advice(offset(360.0 - s * 45.0), left).tryThis.res, "left $s")
        }
    }

    @Test
    fun fewDaysDoNotClaimADayToDayVariation() {
        val a = advice(offset(225.0, days = 2), right)
        assertEquals(Res.string.advice_offset_few_days_summary, a.summary.res)
        assertEquals(Res.string.advice_try_jerk, a.tryThis.res)
        assertEquals(Res.string.advice_offset_varies_summary, advice(offset(225.0, days = 3), right).summary.res)
    }
}
