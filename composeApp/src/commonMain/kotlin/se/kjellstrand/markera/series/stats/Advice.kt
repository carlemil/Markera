package se.kjellstrand.markera.series.stats

import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.Resource
import org.jetbrains.compose.resources.StringResource
import se.kjellstrand.markera.res.*
import se.kjellstrand.markera.ui.settings.ShootingHand

/**
 * A string or plural resource and its arguments, in format order. An argument is an [Int], a
 * [kotlinx.datetime.LocalDate] (the renderer formats it for the language), a [StringResource]
 * (a direction word) or a nested [AdviceText] (a counted phrase such as "5 weeks", its own
 * plural): the renderer must resolve the last three to text before it formats [res], since the
 * format call only prints its arguments. A [PluralStringResource] picks its form by [count]. Pure
 * data, so [advice] is unit-testable.
 */
data class AdviceText(val res: Resource, val args: List<Any> = emptyList(), val count: Int = 0) {
    init {
        require(res is StringResource || res is PluralStringResource) { "not a string: $res" }
    }
}

/** A plural [res] whose form [count] picks; [count] is also one of [args] (the whole list by default). */
internal fun plural(res: PluralStringResource, count: Int, args: List<Any> = listOf(count)) =
    AdviceText(res, args, count)

/** A counted phrase ("5 weeks") for a sentence slot: its own plural, with [n] as `%1$d`. */
internal fun counted(res: PluralStringResource, n: Int) = AdviceText(res, listOf(n), n)

/**
 * The words for one [Finding] (docs/coaching-plan.md): [summary] says what the data shows with its
 * numbers, [causes] are up to three likely causes (shown under "Often caused by"), [tryThis] is the
 * one thing to try and [note] an optional extra line (a low result next to a finding that may
 * explain it). A hint, never a verdict.
 */
data class Advice(
    val finding: Finding,
    val summary: AdviceText,
    val causes: List<AdviceText>,
    val tryThis: AdviceText,
    val note: AdviceText? = null,
)

/** Advice for each finding, in the order given; low results point at the findings that may explain them. */
fun adviceFor(findings: List<Finding>, hand: ShootingHand): List<Advice> =
    findings.map { advice(it, hand, findings) }

/**
 * Pure: one finding and the shooting hand to resource keys and arguments. Where the impact sits is
 * stated as it is on the target; the causes are picked from the side relative to the shooting
 * hand, so a left-hander's mirrored group gets the same causes as a right-hander's.
 */
fun advice(finding: Finding, hand: ShootingHand, all: List<Finding> = emptyList()): Advice = when (finding) {
    is Finding.OffsetGroup -> offsetAdvice(finding, hand)
    is Finding.StretchedGroup -> stretchedAdvice(finding)
    is Finding.Fliers -> Advice(
        finding,
        plural(
            Res.plurals.advice_fliers_summary,
            finding.seriesCount,
            listOf(finding.seriesWithFliers, finding.seriesCount, finding.flierMeanDistanceMm.roundToInt()),
        ),
        texts(Res.string.advice_cause_fliers_1, Res.string.advice_cause_fliers_2, Res.string.advice_cause_fliers_3),
        AdviceText(Res.string.advice_try_fliers),
    )
    is Finding.WideRoundGroup -> Advice(
        finding,
        AdviceText(
            Res.string.advice_wide_summary,
            listOf(
                finding.date,
                percentMore(finding.ratio),
                finding.recentRadiusMm.roundToInt(),
                finding.earlierRadiusMm.roundToInt(),
            ),
        ),
        texts(Res.string.advice_cause_wide_1, Res.string.advice_cause_wide_2, Res.string.advice_cause_wide_3),
        AdviceText(Res.string.advice_try_wide),
    )
    is Finding.LowSeries -> lowAdvice(
        finding,
        AdviceText(
            Res.string.advice_low_series_summary,
            listOf(finding.total, finding.historyMeanTotal.roundToInt(), finding.historyCount),
        ),
        all,
    )
    is Finding.LowDay -> lowAdvice(
        finding,
        plural(
            Res.plurals.advice_low_day_summary,
            finding.seriesCount,
            listOf(
                finding.date,
                finding.seriesCount,
                finding.meanTotal.roundToInt(),
                finding.historyMeanTotal.roundToInt(),
            ),
        ),
        all,
    )
    is Finding.Trend -> trendAdvice(finding)
    is Finding.SessionTrend -> sessionAdvice(finding)
}

private fun texts(vararg res: StringResource) = res.map { AdviceText(it) }

private fun percentMore(ratio: Double): Int = ((ratio - 1) * 100).roundToInt()

/** 0 = up (12 o'clock), 1 = up-right, ... 7 = up-left, for a bearing clockwise from 12 o'clock. */
internal fun sector(bearingDeg: Double): Int = (((bearingDeg % 360 + 360) % 360) / 45).roundToInt() % 8

/** Where the group sits, by [sector]. */
internal val impactWords = listOf(
    Res.string.advice_dir_high, Res.string.advice_dir_high_right, Res.string.advice_dir_right,
    Res.string.advice_dir_low_right, Res.string.advice_dir_low, Res.string.advice_dir_low_left,
    Res.string.advice_dir_left, Res.string.advice_dir_high_left,
)

/** Where to move the group, by [sector] of the move. */
internal val moveWords = listOf(
    Res.string.advice_move_up, Res.string.advice_move_up_right, Res.string.advice_move_right,
    Res.string.advice_move_down_right, Res.string.advice_move_down, Res.string.advice_move_down_left,
    Res.string.advice_move_left, Res.string.advice_move_up_left,
)

/** What the causes of a varying offset depend on, seen from a right-hander (a left-hander is mirrored first). */
internal enum class OffsetPattern { HIGH, LOW, LOW_OPPOSITE, LOW_TRIGGER_SIDE, SIDEWAYS }

internal fun offsetPattern(bearingDeg: Double, hand: ShootingHand): OffsetPattern {
    val b = if (hand == ShootingHand.LEFT) 360 - bearingDeg else bearingDeg
    return when (sector(b)) {
        0, 1, 7 -> OffsetPattern.HIGH
        4 -> OffsetPattern.LOW
        5 -> OffsetPattern.LOW_OPPOSITE
        3 -> OffsetPattern.LOW_TRIGGER_SIDE
        else -> OffsetPattern.SIDEWAYS
    }
}

private fun offsetAdvice(f: Finding.OffsetGroup, hand: ShootingHand): Advice {
    val where = sector(f.bearingDeg)
    val mm = f.distanceMm.roundToInt()
    if (f.stable) {
        return Advice(
            f,
            plural(
                Res.plurals.advice_offset_stable_summary,
                f.hitCount,
                listOf(
                    mm, impactWords[where], f.hitCount, f.daysAgreeing,
                    counted(Res.plurals.advice_offset_stable_days, f.daysChecked),
                ),
            ),
            texts(Res.string.advice_cause_sights_1, Res.string.advice_cause_sights_2),
            // The sights move the impact back to the centre: the opposite way.
            AdviceText(Res.string.advice_try_sights, listOf(mm, moveWords[(where + 4) % 8])),
        )
    }
    // Under three days with enough hits cannot show that the offset changes from day to day.
    val summary = plural(
        if (f.daysChecked >= 3) Res.plurals.advice_offset_varies_summary else Res.plurals.advice_offset_few_days_summary,
        f.hitCount,
        listOf(mm, impactWords[where], f.hitCount),
    )
    return when (offsetPattern(f.bearingDeg, hand)) {
        OffsetPattern.HIGH -> Advice(
            f, summary,
            texts(Res.string.advice_cause_high_1, Res.string.advice_cause_high_2, Res.string.advice_cause_high_3),
            AdviceText(Res.string.advice_try_high),
        )
        OffsetPattern.LOW -> Advice(
            f, summary,
            texts(Res.string.advice_cause_low_1, Res.string.advice_cause_low_2),
            AdviceText(Res.string.advice_try_low),
        )
        OffsetPattern.LOW_OPPOSITE -> Advice(
            f, summary,
            texts(Res.string.advice_cause_jerk_1, Res.string.advice_cause_jerk_2, Res.string.advice_cause_jerk_3),
            AdviceText(Res.string.advice_try_jerk),
        )
        OffsetPattern.LOW_TRIGGER_SIDE -> Advice(
            f, summary,
            texts(Res.string.advice_cause_push_1, Res.string.advice_cause_push_2),
            AdviceText(Res.string.advice_try_push),
        )
        OffsetPattern.SIDEWAYS -> Advice(
            f, summary,
            texts(Res.string.advice_cause_side_1, Res.string.advice_cause_side_2, Res.string.advice_cause_side_3),
            AdviceText(Res.string.advice_try_side),
        )
    }
}

private fun stretchedAdvice(f: Finding.StretchedGroup): Advice {
    val args = listOf(percentMore(f.elongation), f.hitCount)
    return when (f.orientation) {
        Finding.Orientation.VERTICAL -> Advice(
            f, plural(Res.plurals.advice_stretch_vertical_summary, f.hitCount, args),
            texts(Res.string.advice_cause_vertical_1, Res.string.advice_cause_vertical_2, Res.string.advice_cause_vertical_3),
            AdviceText(Res.string.advice_try_vertical),
        )
        Finding.Orientation.HORIZONTAL -> Advice(
            f, plural(Res.plurals.advice_stretch_horizontal_summary, f.hitCount, args),
            texts(Res.string.advice_cause_horizontal_1, Res.string.advice_cause_horizontal_2, Res.string.advice_cause_horizontal_3),
            AdviceText(Res.string.advice_try_horizontal),
        )
        Finding.Orientation.DIAGONAL -> Advice(
            f, plural(Res.plurals.advice_stretch_diagonal_summary, f.hitCount, args),
            texts(Res.string.advice_cause_diagonal_1, Res.string.advice_cause_diagonal_2),
            AdviceText(Res.string.advice_try_diagonal),
        )
    }
}

private fun lowAdvice(f: Finding, summary: AdviceText, all: List<Finding>): Advice {
    val note = all.firstNotNullOfOrNull {
        when (it) {
            is Finding.OffsetGroup -> Res.string.advice_low_note_offset
            is Finding.StretchedGroup -> Res.string.advice_low_note_stretched
            is Finding.Fliers -> Res.string.advice_low_note_fliers
            is Finding.WideRoundGroup -> Res.string.advice_low_note_wide
            else -> null
        }
    }
    return Advice(
        f, summary, emptyList(),
        AdviceText(if (note != null) Res.string.advice_try_low_explained else Res.string.advice_try_low_plain),
        note?.let { AdviceText(it) },
    )
}

private fun trendAdvice(f: Finding.Trend): Advice {
    val points = abs(f.changeOverSpan).roundToInt().coerceAtLeast(1)
    val weeks = (f.spanDays / 7).roundToInt().coerceAtLeast(1)
    val args = listOf(
        points,
        counted(Res.plurals.advice_trend_series, f.seriesCount),
        counted(Res.plurals.advice_trend_weeks, weeks),
    )
    return if (f.improving) {
        Advice(
            f, plural(Res.plurals.advice_trend_up_summary, points, args), emptyList(),
            AdviceText(Res.string.advice_try_trend_up),
        )
    } else {
        Advice(
            f, plural(Res.plurals.advice_trend_down_summary, points, args),
            texts(Res.string.advice_cause_trend_1, Res.string.advice_cause_trend_2, Res.string.advice_cause_trend_3),
            AdviceText(Res.string.advice_try_trend_down),
        )
    }
}

private fun sessionAdvice(f: Finding.SessionTrend): Advice {
    val args = listOf(f.daysCounted, f.daysChecked, f.firstHalfMean.roundToInt(), f.laterHalfMean.roundToInt())
    return if (f.falling) {
        Advice(
            f, plural(Res.plurals.advice_session_down_summary, f.daysChecked, args),
            texts(Res.string.advice_cause_session_1, Res.string.advice_cause_session_2, Res.string.advice_cause_session_3),
            AdviceText(Res.string.advice_try_session_down),
        )
    } else {
        Advice(
            f, plural(Res.plurals.advice_session_up_summary, f.daysChecked, args), emptyList(),
            AdviceText(Res.string.advice_try_session_up),
        )
    }
}
